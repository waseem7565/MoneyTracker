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

## Android app (Capacitor)

The same `index.html` is packaged as an Android app with [Capacitor](https://capacitorjs.com/). `npm run build:web` copies it into `www/`. In the app bundle, Chart.js ships inside the app instead of loading from the CDN, so charts work offline. Inside the app:

- **Budget alerts** are sent as Android notifications through `@capacitor/local-notifications`. Tapping one opens that budget.
- **Export CSV** saves the file with `@capacitor/filesystem` and opens the Android share sheet (`@capacitor/share`), so you can save it to Files/Drive or send it.
- **The back button** closes the open dialog or panel first, then returns to the dashboard, then exits.

### Get the APK from GitHub Actions

Every push that changes the app runs `.github/workflows/android-apk.yml`, which builds a debug APK. Open the repository's **Actions** tab, select the latest **Android APK** run, and download the `MoneyTracker-debug-apk` artifact (a zip containing `app-debug.apk`). You can also start a build by hand with **Run workflow**.

### Build locally

You need Node 22, JDK 21 and the Android SDK (Android Studio installs it; set `ANDROID_HOME`).

```bash
npm install
npm run android:debug       # builds android/app/build/outputs/apk/debug/app-debug.apk
npm run android:open        # or open the project in Android Studio
```

After editing `index.html`, run `npm run sync` to copy the change into the Android project.

The debug APK is signed with a debug key, so you can sideload it onto a phone (allow "install unknown apps"). To publish on Google Play, build a signed release (`./gradlew bundleRelease` with your own keystore) instead.

App icons and splash screens are generated from `assets/` with `npx @capacitor/assets generate --android`.

### Automatic purchase detection (Android)

The app can detect card purchases in bank SMS, and optionally in bank-app notifications, then offer them as ready-to-save expenses. Turn it on under **Accounts → Automatic purchase detection** and add your bank's SMS sender name.

- Native code lives in `android/app/src/main/java/com/myname/expensetracker/purchases/`: `SmsReceiver` (works while the app is closed), `BankNotificationListener` (optional; needs "Notification access"), `PurchaseParser`, `PurchaseStore` (on-device storage and de-duplication) and `PurchaseDetectionPlugin` (the bridge to `index.html`).
- Only messages from the senders and apps you choose are read. One-time passwords and verification codes are never processed. Messages never leave the phone, and only the extracted details (amount, currency, merchant, card's last 4 digits, date/time) are stored.
- **Adding a bank:** parsing rules are in `android/app/src/main/assets/purchase_rules.json`. The generic rules handle most Arabic and English alerts. If your bank's format is parsed wrongly, add an entry to `banks` with its sender IDs and a regular expression that uses named groups (`amount`, `currency`, `merchant`, `card`, `date`, `time`), then add a sample message to `android/app/src/test/.../PurchaseParserTest.java` and run `./gradlew testDebugUnitTest` in `android/`. You can also try a message on the phone with **Test with a sample message** in settings.

## Currencies

The app works in SAR, but each account can have its own currency (Accounts → Edit → Currency; the list is `CURRENCIES` in `index.html`).

- A transaction's `amount` is always SAR, so totals, charts, budgets and alerts never convert. On other-currency accounts the original amount and the rate used are saved too (`origAmount`/`currency`/`rate`, or `fromAmount`/`toAmount`/`rate` for transfers), so past transactions don't change when the rate does.
- Rates (units per 1 SAR) come from a manual rate in **Accounts → Exchange rates**, else a free daily fetch (open.er-api.com, with the fawazahmed0 currency API as a fallback) cached for offline use, else the latest transaction's rate. Rates are only fetched when an account uses another currency.
- Account balances show in the account's currency with the SAR equivalent at today's rate; the total balance uses that SAR value.

## Import from Monefy

**Accounts → Import & backup → Import…** reads a Monefy CSV export. It shows a preview, lets you map accounts, categories and unmatched transfers, backs up your current data, then imports and compares each account's balance with Monefy's.

- Parsing, transfer pairing and duplicate detection live in `monefy-import.js`, which doesn't touch the page, so it's tested in Node: `npm test`.
- Columns are read by position (Monefy has two columns named "currency"), and the converted SAR amount is what gets imported. `Initial balance '…'` rows set starting balances. Matching `To '…'` / `From '…'` rows become one transfer, and transfers never count as spending or income.
- Re-importing a file skips rows that were already imported. The backup (restore it under **Import & backup**) is only replaced by an import that changes something.
- Keep real exports in `import-data/` (git-ignored). `npm run check:monefy` dry-runs `import-data/monefy.csv` and prints the balance check.
