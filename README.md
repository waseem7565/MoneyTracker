# MoneyTracker

A personal expense tracker in a single self-contained HTML file (`index.html`). Open it in any modern browser; there is no build step.

- **Accounts:** checking, savings, credit card and cash, each with a starting balance and a color you choose.
- **Transactions:** income and expenses with categories. You can add your own categories. The list can be filtered by account, category, type and date range, searched, and sorted.
- **Insights:** built with Chart.js. They include spending by category, spending by month (or by day for the current month), and each account's balance over time. You can switch between all accounts or one account, and between this month, the last 3 months or this year.
- **Summary:** total balance, amount spent this month and the biggest category this month.
- **Monthly budgets:** each budget has a limit, a category (or all spending) and one, several or all accounts.
  - Progress bars are green under 75%, yellow from 75% to 99% and red at 100% or more.
  - Budgets reset automatically on the 1st of each month. Each finished month is kept in the budget's history, with the limit, the amount spent and whether it stayed within budget.
  - The dashboard shows a budget summary and a budget vs. actual chart.
- **Budget alerts:** you're alerted at 80% and again at 100%, once per budget per month.
  - Alerts show as pop-ups in the app and are kept under a notification bell, where you can mark them read or clear them. They survive a reload.
  - If you allow it, alerts are also sent as browser notifications.
- **Other:** amounts are shown in SAR, data is saved in your browser (localStorage), you can export to CSV, and there's a light/dark theme. Sample data (including budgets) loads on first visit and can be cleared with one click.

All data, including budgets and notifications, is stored in one localStorage key (`moneytracker.v1`). A budget's spending is always recalculated from your transactions, so editing or deleting transactions, accounts or categories updates your budgets straight away.

Chart.js loads from cdnjs, so the charts need an internet connection. Everything else works offline.
