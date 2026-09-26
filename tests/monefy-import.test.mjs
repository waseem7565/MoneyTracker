// Tests for monefy-import.js. Run with `npm test`. Uses made-up data in Monefy's export format
// (real exports belong in the git-ignored import-data/ folder; see scripts/check-monefy.mjs).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const M = require('../monefy-import.js');

const CSV = [
  'date,account,category,amount,currency,converted amount,currency,description',
  "01/06/2026,Bank,Initial balance 'Bank',\"1,000\",SAR,\"1,000\",SAR,",
  "01/06/2026,Wallet,Initial balance 'Wallet',50.5,SAR,50.5,SAR,",
  '02/06/2026,Wallet,Food,-20,SAR,-20,SAR,Lunch ',
  '02/06/2026,Wallet,Food,-20,SAR,-20,SAR,Lunch ',
  '03/06/2026,Bank,Salary,"5,000",SAR,"5,000",SAR,Salary',
  "03/06/2026,Bank,To 'Wallet',-300,SAR,-300,SAR,",
  "03/06/2026,Wallet,From 'Bank',300,SAR,300,SAR,",
  "04/06/2026,Bank,To 'Friend',\"-1,500\",SAR,\"-1,500\",SAR,",
  "20/06/2026,Wallet,From 'Friend',\"1,500\",SAR,\"1,500\",SAR,",
  '05/06/2026,Wallet,gift,-10,SAR,-10,SAR,"Flowers, roses"',
  '06/06/2026,Wallet,Gifts,-30,SAR,-30,SAR,',
  '06/06/2026,Wallet,Gift,-5,SAR,-5,SAR,',
  '07/06/2026,Bank,Deposits,"100,000",LKR,"100,000",SAR,Loan back',
  '08/06/2026,Bank,Travel,-50,USD,-187.5,SAR,Hotel',
  '31/02/2026,Bank,Food,-1,SAR,-1,SAR,bad date'
].join('\r\n');

const helpers = () => { let n = 0; return { uid: () => `id${++n}`, color: () => '#888', accountType: () => 'checking' }; };
const freshState = () => ({ accounts: [], transactions: [], categories: ['Food', 'Gifts', 'Other'] });

test('parses by position, with quoted thousands, DD/MM/YYYY dates and trimmed text', () => {
  const { rows, errors } = M.parseMonefy('﻿' + CSV);
  assert.equal(rows.length, 14);
  assert.deepEqual(errors, [{ line: 16, reason: 'Unreadable date “31/02/2026”.' }]);
  const salary = rows.find((r) => r.category === 'Salary');
  assert.equal(salary.date, '2026-06-03');
  assert.equal(salary.converted, 5000);
  assert.equal(rows.find((r) => r.category === 'gift').description, 'Flowers, roses');
  assert.equal(rows.find((r) => r.category === 'Travel').converted, -187.5); // converted column, not the USD amount
  assert.equal(rows[2].description, 'Lunch');
});

test('identical rows get distinct keys, so both are imported', () => {
  const { rows } = M.parseMonefy(CSV);
  const lunches = rows.filter((r) => r.description === 'Lunch');
  assert.equal(lunches.length, 2);
  assert.notEqual(lunches[0].key, lunches[1].key);
});

test('plan: starting balances, paired and unmatched transfers, flagged rows, categories', () => {
  const plan = M.buildPlan(M.parseMonefy(CSV));
  assert.deepEqual(plan.accounts, ['Bank', 'Wallet']);
  assert.equal(plan.initial.length, 2);
  assert.equal(plan.transfers.length, 1);
  assert.equal(plan.transfers[0].out.account, 'Bank');
  assert.deepEqual(plan.unmatched.map((u) => [u.name, u.inFile, u.rows.length]), [['Friend', false, 2]]);
  assert.deepEqual(plan.flagged.map((r) => r.line), [14]); // LKR recorded 1:1 as SAR; USD row was converted properly
  assert.deepEqual(plan.dateRange, { from: '2026-06-01', to: '2026-06-20' });
  assert.equal(plan.monefyBalances.Bank, 1000 + 5000 - 300 - 1500 + 100000 - 187.5);
});

test('default choices match case-insensitively and suggest merging near-duplicates', () => {
  const plan = M.buildPlan(M.parseMonefy(CSV));
  const c = M.defaultChoices(plan, freshState());
  assert.deepEqual(c.categories.Food, { action: 'existing', name: 'Food' });
  assert.deepEqual(c.categories.Gifts, { action: 'existing', name: 'Gifts' });
  assert.equal(c.categories.gift.action, 'existing'); // "gift" → existing "Gifts" by stem
  assert.equal(c.categories.gift.suggested, true);
  assert.deepEqual(c.accounts.Bank, { target: 'new', name: 'Bank' });
  assert.equal(c.unmatched.Friend.mode, 'account');
});

test('import: balances match Monefy, transfers are not expenses, duplicates are skipped', () => {
  const plan = M.buildPlan(M.parseMonefy(CSV));
  const state = freshState();
  const choices = M.defaultChoices(plan, state);
  choices.corrections[14] = '1000'; // the LKR row, in SAR
  const s = M.applyImport(state, plan, choices, helpers());

  assert.deepEqual(s.imported, { transactions: 8, transfers: 3, startBalances: 2, accounts: 3, categories: 3 });
  assert.deepEqual(s.failed, [{ line: 16, reason: 'Unreadable date “31/02/2026”.' }]);
  const bank = s.accountIds.Bank, wallet = s.accountIds.Wallet, friend = s.accountIds.Friend;
  assert.equal(M.accountBalance(state, wallet), plan.monefyBalances.Wallet);
  assert.equal(M.accountBalance(state, bank), plan.monefyBalances.Bank - 100000 + 1000); // corrected row
  assert.equal(M.accountBalance(state, friend), 0); // 1,500 in from Bank, 1,500 out to Wallet
  assert.equal(state.transactions.filter((t) => t.type === 'transfer').length, 3);
  assert.ok(state.transactions.every((t) => t.type !== 'transfer' || t.category === ''));
  const expenses = state.transactions.filter((t) => t.type === 'expense').reduce((a, t) => a + t.amount, 0);
  assert.equal(expenses, 20 + 20 + 10 + 30 + 5 + 187.5);
  assert.equal(state.accounts.find((a) => a.id === wallet).startBalance, 50.5);
  assert.ok(state.categories.includes('Salary') && state.categories.includes('Travel'));
  assert.equal(state.categories[state.categories.length - 1], 'Other'); // new categories go before "Other"

  // After importing, the flagged row counts as imported, so the import screen doesn't ask for its SAR amount again.
  const done = M.importedKeys(state);
  assert.equal(plan.flagged.filter((r) => !done.has(r.key)).length, 0);
  assert.equal(plan.rows.filter((r) => done.has(r.key)).length, plan.rows.length);

  const count = state.transactions.length;
  const again = M.applyImport(state, plan, M.defaultChoices(plan, state), helpers());
  assert.equal(state.transactions.length, count);
  assert.equal(again.imported.transactions + again.imported.transfers + again.imported.startBalances + again.imported.accounts, 0);
  assert.equal(again.skipped.length, 14);
});

test('unmatched transfers can be imported as regular expense / income instead', () => {
  const plan = M.buildPlan(M.parseMonefy(CSV));
  const state = freshState();
  const choices = M.defaultChoices(plan, state);
  choices.unmatched.Friend = { mode: 'regular', category: 'Other' };
  const s = M.applyImport(state, plan, choices, helpers());
  assert.equal(s.accountIds.Friend, undefined);
  const friendRows = state.transactions.filter((t) => t.description === 'To Friend' || t.description === 'From Friend');
  assert.deepEqual(friendRows.map((t) => [t.type, t.amount, t.category]), [['expense', 1500, 'Other'], ['income', 1500, 'Other']]);
  assert.deepEqual(s.failed.map((f) => f.line), [14, 16]); // no SAR amount entered for the flagged row
});

test('merged categories and existing-account mapping', () => {
  const plan = M.buildPlan(M.parseMonefy(CSV));
  const state = freshState();
  state.accounts.push({ id: 'mine', name: 'My wallet', type: 'cash', startBalance: 5, color: '#000' });
  const choices = M.defaultChoices(plan, state);
  choices.accounts.Wallet = { target: 'mine' };
  choices.categories.Gifts = { action: 'new', name: 'Presents' };
  choices.categories.gift = { action: 'merge', into: 'Gifts' };
  choices.categories.Gift = { action: 'merge', into: 'Gifts' };
  M.applyImport(state, plan, choices, helpers());
  assert.equal(state.accounts.find((a) => a.id === 'mine').startBalance, 50.5);
  const gifts = state.transactions.filter((t) => t.category === 'Presents');
  assert.equal(gifts.length, 3);
  assert.ok(state.transactions.filter((t) => t.accountId === 'mine').length > 0);
});

test('LKR accounts keep their LKR amounts, with the rate worked out from the SAR amount', () => {
  const csv = [
    'date,account,category,amount,currency,converted amount,currency,description',
    "10/06/2026,Home LK,Initial balance 'Home LK',\"50,000\",LKR,625,SAR,",
    "10/06/2026,Bank,Initial balance 'Bank',\"2,000\",SAR,\"2,000\",SAR,",
    '11/06/2026,Home LK,Food,"-8,000",LKR,-100,SAR,Rice',
    "12/06/2026,Bank,To 'Home LK',\"-1,000\",SAR,\"-1,000\",SAR,",
    "12/06/2026,Home LK,From 'Bank',\"80,000\",LKR,\"1,000\",SAR,",
    '13/06/2026,Home LK,Deposits,"5,000",LKR,"5,000",SAR,Refund'
  ].join('\n');
  const plan = M.buildPlan(M.parseMonefy(csv));
  assert.deepEqual(plan.accountCurrencies, { 'Home LK': 'LKR', Bank: 'SAR' });
  assert.equal(plan.monefyBalances['Home LK'], 50000 - 8000 + 80000 + 5000); // in LKR, like Monefy shows it
  assert.deepEqual(plan.flagged.map((r) => r.line), [7]); // converted amount equals the LKR amount

  const state = freshState();
  const choices = M.defaultChoices(plan, state);
  choices.corrections[7] = '62.5';
  const s = M.applyImport(state, plan, choices, helpers());
  const lk = state.accounts.find((a) => a.id === s.accountIds['Home LK']);
  assert.equal(lk.currency, 'LKR');
  assert.equal(lk.startBalance, 50000);
  const rice = state.transactions.find((t) => t.description === 'Rice');
  assert.deepEqual([rice.amount, rice.origAmount, rice.currency, rice.rate], [100, 8000, 'LKR', 80]);
  const refund = state.transactions.find((t) => t.description === 'Refund');
  assert.deepEqual([refund.type, refund.amount, refund.origAmount, refund.rate], ['income', 62.5, 5000, 80]);
  const tr = state.transactions.find((t) => t.type === 'transfer');
  assert.deepEqual([tr.amount, tr.fromAmount, tr.toAmount, tr.rate], [1000, undefined, 80000, 80]);
  assert.equal(M.accountBalance(state, lk.id), plan.monefyBalances['Home LK']);
  assert.equal(M.accountBalance(state, s.accountIds.Bank), 1000);
});

test('rejects files that are not Monefy exports', () => {
  const r = M.parseMonefy('Date,Description,Amount\n2026-01-01,x,1');
  assert.equal(r.rows.length, 0);
  assert.match(r.errors[0].reason, /Monefy export/);
});
