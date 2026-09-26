# MoneyTracker

A personal expense tracker in a single self-contained HTML file (`index.html`). Open it in any modern browser; there is no build step.

- **Accounts:** checking, savings, credit card and cash, each with a starting balance and a color you choose.
- **Transactions:** income and expenses with categories. You can add your own categories. The list can be filtered by account, category, type and date range, searched, and sorted.
- **Insights:** built with Chart.js. They include spending by category, spending by month (or by day for the current month), and each account's balance over time. You can switch between all accounts or one account, and between this month, the last 3 months or this year.
- **Summary:** total balance, amount spent this month and the biggest category this month.
- **Other:** amounts are shown in SAR, data is saved in your browser (localStorage), you can export to CSV, and there's a light/dark theme. Sample data loads on first visit and can be cleared with one click.

Chart.js loads from cdnjs, so the charts need an internet connection. Everything else works offline.
