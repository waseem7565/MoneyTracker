// Monefy CSV import: parsing, planning and applying. No DOM access, so it runs in the app and in Node tests.
//
// Monefy export columns (read by position, because two of them are called "currency"):
//   0 date (DD/MM/YYYY) · 1 account · 2 category · 3 amount · 4 currency · 5 converted amount · 6 converted currency · 7 description
// Amounts are strings like "5,000" or "-1,500"; negative is money out. The converted amount is what gets imported.
// Categories with special meaning: "Initial balance '<account>'", "To '<account>'" and "From '<account>'".
(function (root) {
  'use strict';

  const BASE_CURRENCY = 'SAR';
  const EPS = 0.005;
  const round2 = (n) => Math.round(n * 100) / 100;
  const same = (a, b) => Math.abs(a - b) < EPS;

  // ---------- CSV ----------

  /** RFC 4180-style CSV: quoted fields, doubled quotes, CRLF or LF line ends, optional BOM. */
  function parseCsv(text) {
    const rows = [];
    let row = [], field = '', quoted = false, i = 0;
    text = String(text).replace(/^﻿/, '');
    while (i < text.length) {
      const c = text[i];
      if (quoted) {
        if (c === '"') {
          if (text[i + 1] === '"') { field += '"'; i += 2; continue; }
          quoted = false; i++; continue;
        }
        field += c; i++; continue;
      }
      if (c === '"') { quoted = true; i++; continue; }
      if (c === ',') { row.push(field); field = ''; i++; continue; }
      if (c === '\r' || c === '\n') {
        row.push(field); rows.push(row); row = []; field = '';
        i += c === '\r' && text[i + 1] === '\n' ? 2 : 1;
        continue;
      }
      field += c; i++;
    }
    if (field !== '' || row.length) { row.push(field); rows.push(row); }
    return rows.filter((r) => r.some((f) => f.trim() !== ''));
  }

  function parseAmount(s) {
    const t = String(s || '').trim().replace(/[,\s ]/g, '');
    if (!/^[-+]?\d+(\.\d+)?$/.test(t)) return NaN;
    return Number(t);
  }

  function parseDate(s) {
    const m = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(String(s || '').trim());
    if (!m) return null;
    const d = +m[1], mo = +m[2], y = +m[3];
    const dt = new Date(y, mo - 1, d);
    if (dt.getFullYear() !== y || dt.getMonth() !== mo - 1 || dt.getDate() !== d) return null;
    return `${y}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
  }

  // ---------- Monefy file ----------

  const INITIAL = /^Initial balance '(.*)'$/;
  const TO = /^To '(.*)'$/;
  const FROM = /^From '(.*)'$/;

  /**
   * Parses a Monefy export. Returns { rows, errors }. Each row:
   * { line, date, account, category, amount, currency, converted, convCurrency, description, kind, other, key }
   * kind: 'initial' | 'out' (To) | 'in' (From) | 'regular'; other: the account named inside the quotes.
   * key: a stable id for duplicate detection (identical rows get an occurrence number).
   */
  function parseMonefy(text) {
    const table = parseCsv(text);
    const errors = [];
    if (!table.length) return { rows: [], errors: [{ line: 0, reason: 'The file is empty.' }] };
    const header = table[0].map((h) => h.trim().toLowerCase());
    if (header[0] !== 'date' || header[1] !== 'account' || header[2] !== 'category' || header.length < 7) {
      return { rows: [], errors: [{ line: 1, reason: 'This doesn’t look like a Monefy export (expected columns: date, account, category, amount, currency, converted amount, currency, description).' }] };
    }
    const rows = [];
    const seen = new Map();
    for (let i = 1; i < table.length; i++) {
      const f = table[i];
      const line = i + 1;
      const date = parseDate(f[0]);
      const account = (f[1] || '').trim();
      const category = (f[2] || '').trim();
      const amount = parseAmount(f[3]);
      const converted = parseAmount(f[5]);
      const currency = (f[4] || '').trim().toUpperCase();
      const convCurrency = (f[6] || '').trim().toUpperCase();
      const description = (f[7] || '').trim();
      if (!date) { errors.push({ line, reason: `Unreadable date “${f[0] || ''}”.` }); continue; }
      if (!account) { errors.push({ line, reason: 'No account.' }); continue; }
      if (Number.isNaN(converted)) { errors.push({ line, reason: `Unreadable converted amount “${f[5] || ''}”.` }); continue; }
      if (convCurrency && convCurrency !== BASE_CURRENCY) { errors.push({ line, reason: `Converted amount is in ${convCurrency}, not ${BASE_CURRENCY}.` }); continue; }
      let kind = 'regular', other = null, m;
      if ((m = INITIAL.exec(category))) { kind = 'initial'; other = m[1]; }
      else if ((m = TO.exec(category))) { kind = 'out'; other = m[1]; }
      else if ((m = FROM.exec(category))) { kind = 'in'; other = m[1]; }
      const base = ['monefy', date, account, category, converted, description].join('|');
      const n = (seen.get(base) || 0) + 1;
      seen.set(base, n);
      rows.push({
        line, date, account, category, amount, currency, converted, convCurrency, description, kind, other, key: `${base}|${n}`,
        // Original currency isn't SAR, yet the "converted" amount is the same number: Monefy didn't convert it.
        flagged: currency !== '' && currency !== BASE_CURRENCY && !Number.isNaN(amount) && same(amount, converted) && converted !== 0
      });
    }
    return { rows, errors };
  }

  // ---------- Plan (what the import screen shows) ----------

  const catKey = (s) => String(s).trim().toLowerCase();
  // "Gifts" and "Gift", "Bills" and "Bill": same stem → suggest merging.
  const catStem = (s) => catKey(s).replace(/[^\p{L}\p{N}]+/gu, '').replace(/(es|s)$/, '');

  /**
   * Groups the parsed rows into what will be imported:
   * { rows, errors, accounts, initial, transfers, unmatched, regular, categories, flagged, dateRange, monefyBalances }
   */
  function buildPlan(parsed) {
    const rows = parsed.rows;
    const accountNames = [];
    const addAccount = (n) => { if (!accountNames.includes(n)) accountNames.push(n); };
    rows.forEach((r) => addAccount(r.account));
    rows.filter((r) => r.kind === 'initial').forEach((r) => addAccount(r.other));

    // Pair "To 'B'" in A with "From 'A'" in B: same date, same amount with opposite sign.
    const outs = rows.filter((r) => r.kind === 'out');
    const ins = rows.filter((r) => r.kind === 'in');
    const usedIn = new Set();
    const transfers = [];
    const unmatchedRows = [];
    for (const o of outs) {
      const match = ins.find((i) => !usedIn.has(i) && i.date === o.date && i.account === o.other && i.other === o.account && same(i.converted, -o.converted));
      if (match) { usedIn.add(match); transfers.push({ out: o, in: match }); }
      else unmatchedRows.push(o);
    }
    ins.filter((i) => !usedIn.has(i)).forEach((i) => unmatchedRows.push(i));
    unmatchedRows.sort((a, b) => a.line - b.line);

    // Unmatched transfers, grouped by the account they point to.
    const unmatched = [];
    for (const r of unmatchedRows) {
      let g = unmatched.find((u) => u.name === r.other);
      if (!g) unmatched.push(g = { name: r.other, inFile: accountNames.includes(r.other), rows: [] });
      g.rows.push(r);
    }

    const regular = rows.filter((r) => r.kind === 'regular');
    const categories = [];
    for (const r of regular) {
      let c = categories.find((x) => x.name === r.category);
      if (!c) categories.push(c = { name: r.category, count: 0 });
      c.count++;
    }
    categories.sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));

    // What Monefy shows for each account: its starting balance plus every row in it.
    const monefyBalances = {};
    accountNames.forEach((n) => { monefyBalances[n] = 0; });
    rows.forEach((r) => { monefyBalances[r.account] = round2((monefyBalances[r.account] || 0) + r.converted); });

    const dates = rows.map((r) => r.date).sort();
    return {
      rows, errors: parsed.errors, accounts: accountNames,
      initial: rows.filter((r) => r.kind === 'initial'),
      transfers, unmatched, regular, categories,
      flagged: rows.filter((r) => r.flagged),
      dateRange: dates.length ? { from: dates[0], to: dates[dates.length - 1] } : null,
      monefyBalances
    };
  }

  /** Default choices: match existing accounts/categories by name (ignoring case) and suggest merging near-duplicates. */
  function defaultChoices(plan, existing) {
    const accounts = {};
    const accountTarget = (name) => {
      const hit = existing.accounts.find((a) => catKey(a.name) === catKey(name));
      return hit ? { target: hit.id } : { target: 'new', name };
    };
    plan.accounts.forEach((n) => { accounts[n] = accountTarget(n); });

    const categories = {};
    const byStem = {};
    for (const c of plan.categories) {
      const exact = existing.categories.find((x) => catKey(x) === catKey(c.name));
      if (exact) { categories[c.name] = { action: 'existing', name: exact }; continue; }
      const near = existing.categories.find((x) => catStem(x) === catStem(c.name));
      if (near) { categories[c.name] = { action: 'existing', name: near, suggested: true }; continue; }
      const stem = catStem(c.name);
      if (byStem[stem]) { categories[c.name] = { action: 'merge', into: byStem[stem], suggested: true }; continue; }
      byStem[stem] = c.name; // categories are sorted by use, so the most-used spelling wins
      categories[c.name] = { action: 'new', name: c.name };
    }

    const unmatched = {};
    plan.unmatched.forEach((u) => {
      unmatched[u.name] = { mode: 'account', category: existing.categories.find((x) => catKey(x) === 'other') || 'Other' };
      if (!accounts[u.name]) accounts[u.name] = accountTarget(u.name);
    });
    return { accounts, categories, unmatched, corrections: {} };
  }

  function resolveCategory(choices, name) {
    let c = choices.categories[name];
    for (let hops = 0; c && c.action === 'merge' && hops < 20; hops++) c = choices.categories[c.into];
    if (!c) return name;
    return c.name;
  }

  // ---------- Apply ----------

  /**
   * Adds the planned rows to `state` (mutated in place). `helpers`: { uid(), color(i), accountType(name) }.
   * Returns a summary: { imported: {...counts}, skipped: [{ line, reason }], failed: [{ line, reason }], accountIds: { monefyName: appId } }.
   */
  function applyImport(state, plan, choices, helpers) {
    const summary = { imported: { transactions: 0, transfers: 0, startBalances: 0, accounts: 0, categories: 0 }, skipped: [], failed: [], accountIds: {} };
    const existingKeys = new Set();
    state.transactions.forEach((t) => (t.importKeys || []).forEach((k) => existingKeys.add(k)));
    state.accounts.forEach((a) => (a.importKeys || []).forEach((k) => existingKeys.add(k)));
    const isDup = (...rows) => rows.some((r) => existingKeys.has(r.key));
    const dup = (r) => summary.skipped.push({ line: r.line, reason: 'Already imported' });
    const amountOf = (r) => {
      if (!r.flagged) return r.converted;
      const fixed = choices.corrections[r.line];
      return fixed === undefined || fixed === null || fixed === '' || Number.isNaN(Number(fixed)) ? NaN : Math.sign(r.converted || 1) * Math.abs(Number(fixed));
    };
    const needsAmount = (r) => summary.failed.push({ line: r.line, reason: `Flagged ${r.currency} amount: no SAR amount was entered.` });

    // Accounts are created on first use, so re-importing a file doesn't add empty accounts.
    let colorIndex = state.accounts.length;
    const accountId = (name) => {
      if (summary.accountIds[name]) return summary.accountIds[name];
      const choice = choices.accounts[name] || { target: 'new', name };
      if (choice.target !== 'new' && state.accounts.some((a) => a.id === choice.target)) return (summary.accountIds[name] = choice.target);
      const newName = String(choice.name || name).trim() || name;
      const clash = state.accounts.find((a) => catKey(a.name) === catKey(newName));
      if (clash) return (summary.accountIds[name] = clash.id);
      const acc = { id: helpers.uid(), name: newName, type: helpers.accountType(newName), startBalance: 0, color: helpers.color(colorIndex++), cards: [], importKeys: [] };
      state.accounts.push(acc);
      summary.imported.accounts++;
      return (summary.accountIds[name] = acc.id);
    };

    const categoryName = (monefyName) => {
      const name = resolveCategory(choices, monefyName) || 'Other';
      const hit = state.categories.find((c) => catKey(c) === catKey(name));
      if (hit) return hit;
      const i = state.categories.indexOf('Other');
      state.categories.splice(i < 0 ? state.categories.length : i, 0, name);
      summary.imported.categories++;
      return name;
    };

    // Starting balances
    for (const r of plan.initial) {
      if (isDup(r)) { dup(r); continue; }
      const amt = amountOf(r);
      if (Number.isNaN(amt)) { needsAmount(r); continue; }
      const id = accountId(r.other);
      const acc = state.accounts.find((a) => a.id === id);
      acc.startBalance = round2(amt);
      acc.importKeys = (acc.importKeys || []).concat(r.key);
      summary.imported.startBalances++;
    }

    const addTransfer = (rows, fromName, toName, amount, date, description) => {
      const from = accountId(fromName), to = accountId(toName);
      if (from === to) { rows.forEach((r) => summary.skipped.push({ line: r.line, reason: 'Both sides of the transfer map to the same account' })); return; }
      state.transactions.push({ id: helpers.uid(), type: 'transfer', amount: round2(Math.abs(amount)), date, accountId: from, toAccountId: to, category: '', description, source: 'monefy', importKeys: rows.map((r) => r.key) });
      summary.imported.transfers++;
    };
    const addRegular = (r, amount, category, description) => {
      if (same(amount, 0)) { summary.failed.push({ line: r.line, reason: 'Amount is zero.' }); return; }
      state.transactions.push({
        id: helpers.uid(), type: amount < 0 ? 'expense' : 'income', amount: round2(Math.abs(amount)), date: r.date,
        accountId: accountId(r.account), category, description, source: 'monefy', importKeys: [r.key]
      });
      summary.imported.transactions++;
    };

    // Matched transfers
    for (const t of plan.transfers) {
      if (isDup(t.out, t.in)) { dup(t.out); dup(t.in); continue; }
      const amt = t.out.flagged ? amountOf(t.out) : t.in.flagged ? amountOf(t.in) : t.out.converted;
      if (Number.isNaN(amt)) { needsAmount(t.out.flagged ? t.out : t.in); continue; }
      addTransfer([t.out, t.in], t.out.account, t.in.account, amt, t.out.date, t.out.description || t.in.description);
    }

    // Unmatched transfers: to/from an account that's created (or chosen), or plain expense/income
    for (const g of plan.unmatched) {
      const choice = choices.unmatched[g.name] || { mode: 'account' };
      for (const r of g.rows) {
        if (isDup(r)) { dup(r); continue; }
        const amt = amountOf(r);
        if (Number.isNaN(amt)) { needsAmount(r); continue; }
        if (choice.mode === 'account') {
          if (r.kind === 'out') addTransfer([r], r.account, g.name, amt, r.date, r.description);
          else addTransfer([r], g.name, r.account, amt, r.date, r.description);
        } else {
          addRegular(r, amt, categoryName(choice.category || 'Other'), r.description || `${r.kind === 'out' ? 'To' : 'From'} ${g.name}`);
        }
      }
    }

    // Everything else
    for (const r of plan.regular) {
      if (isDup(r)) { dup(r); continue; }
      const amt = amountOf(r);
      if (Number.isNaN(amt)) { needsAmount(r); continue; }
      addRegular(r, amt, categoryName(r.category || 'Other'), r.description);
    }

    plan.errors.forEach((e) => summary.failed.push(e));
    summary.failed.sort((a, b) => a.line - b.line);
    return summary;
  }

  /** Balance an account shows in the app: starting balance plus every transaction and transfer that touches it. */
  function accountBalance(state, id) {
    const acc = state.accounts.find((a) => a.id === id);
    let b = Number(acc && acc.startBalance) || 0;
    for (const t of state.transactions) {
      if (t.type === 'transfer') b += (t.toAccountId === id ? t.amount : 0) - (t.accountId === id ? t.amount : 0);
      else if (t.accountId === id) b += t.type === 'income' ? t.amount : -t.amount;
    }
    return round2(b);
  }

  const api = { parseCsv, parseMonefy, buildPlan, defaultChoices, applyImport, accountBalance, resolveCategory, catStem };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.MonefyImport = api;
})(typeof window !== 'undefined' ? window : globalThis);
