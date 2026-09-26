// Dry-runs the Monefy importer on a local export and prints what would be imported and the resulting balances.
// Usage: node scripts/check-monefy.mjs [path] [line=SAR ...]   (default path: import-data/monefy.csv, which is git-ignored)
// e.g. `node scripts/check-monefy.mjs 174=1135` enters 1135 SAR for flagged line 174.
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const M = require('../monefy-import.js');
const args = process.argv.slice(2);
const fixes = Object.fromEntries(args.filter((a) => /^\d+=/.test(a)).map((a) => a.split('=')));
const file = args.find((a) => !/^\d+=/.test(a)) || new URL('../import-data/monefy.csv', import.meta.url);
const plan = M.buildPlan(M.parseMonefy(readFileSync(file, 'utf8')));

console.log(`Rows: ${plan.rows.length}, parse errors: ${plan.errors.length}`);
console.log(`Dates: ${plan.dateRange.from} → ${plan.dateRange.to}`);
console.log(`Accounts (${plan.accounts.length}): ${plan.accounts.join(' | ')}`);
console.log(`Starting balances: ${plan.initial.length}, transfers: ${plan.transfers.length}, regular: ${plan.regular.length}`);
console.log('Categories:', plan.categories.map((c) => `${c.name} (${c.count})`).join(', '));
console.log('Unmatched transfers:', plan.unmatched.map((u) => `${u.name}${u.inFile ? ' [in file]' : ''}: lines ${u.rows.map((r) => r.line).join(', ')}`).join('; '));
console.log('Flagged rows:', plan.flagged.map((r) => `line ${r.line}: ${r.amount} ${r.currency} → ${r.converted} SAR`).join('; ') || 'none');

const state = { accounts: [], transactions: [], categories: ['Food', 'Transport', 'Shopping', 'Bills', 'Entertainment', 'Health', 'Other'] };
const choices = M.defaultChoices(plan, state);
Object.assign(choices.corrections, fixes);
console.log('Account currencies:', Object.entries(plan.accountCurrencies).filter(([, c]) => c !== 'SAR').map(([n, c]) => `${n}: ${c}`).join(', ') || 'all SAR');
console.log('Category choices:', Object.entries(choices.categories).map(([k, v]) => `${k} → ${v.action === 'merge' ? 'merge into ' + v.into : v.action + ' ' + v.name}`).join(', '));
let n = 0;
const helpers = { uid: () => `id${++n}`, color: () => '#888', accountType: () => 'checking' };
const summary = M.applyImport(state, plan, choices, helpers);
console.log('Imported:', summary.imported, 'skipped:', summary.skipped.length, 'failed:', summary.failed);

console.log('\nBalance check (Monefy file vs app):');
let mismatches = 0;
for (const name of plan.accounts) {
  const app = M.accountBalance(state, summary.accountIds[name]);
  const ok = Math.abs(app - plan.monefyBalances[name]) < 0.005;
  const flagged = plan.flagged.some((r) => r.account === name); // expected: the flagged row wasn't imported in this dry run
  if (!ok && !flagged) mismatches++;
  console.log(`  ${ok ? 'OK  ' : flagged ? 'FLAG' : 'DIFF'} ${name.padEnd(26)} Monefy ${plan.monefyBalances[name].toFixed(2).padStart(11)}  app ${app.toFixed(2).padStart(11)}`);
}
const again = M.applyImport(state, plan, M.defaultChoices(plan, state), helpers);
console.log(`\nRe-import: imported ${JSON.stringify(again.imported)}, skipped ${again.skipped.length}, failed ${again.failed.length}`);
const spend = state.transactions.filter((t) => t.type === 'expense').reduce((s, t) => s + t.amount, 0);
console.log(`Total expenses ${spend.toFixed(2)} (transfers excluded: ${state.transactions.filter((t) => t.type === 'transfer').length})`);
process.exitCode = mismatches ? 1 : 0;
