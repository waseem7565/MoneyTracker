package com.myname.expensetracker.purchases;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a bank SMS or notification into a {@link Purchase}. The rules (keywords, currencies, per-bank patterns)
 * come from assets/purchase_rules.json, so supporting a new bank is usually a data change, not a code change.
 * Pure Java (plus org.json), so it can be unit-tested off the device.
 */
public final class PurchaseParser {

    /** Why a message was not turned into a purchase. */
    public enum Reason { EMPTY, OTP, NOT_PURCHASE, NO_AMOUNT }

    public static final class Result {
        public final Purchase purchase; // null when ignored
        public final Reason reason;     // null when parsed
        public final String bankName;   // matching bank rule, if any

        Result(Purchase purchase, Reason reason, String bankName) {
            this.purchase = purchase;
            this.reason = reason;
            this.bankName = bankName;
        }
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final String NUM = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)";
    private static final Pattern LABELED_LINE = Pattern.compile("^\\s*([^:：\\n]{1,30}?)\\s*[:：]\\s*(.+?)\\s*$", FLAGS | Pattern.MULTILINE);
    private static final Pattern LAST4 = Pattern.compile("(\\d{4})(?!\\d)");
    private static final Pattern TIME = Pattern.compile("(?<![\\d:])(\\d{1,2}):(\\d{2})(?::\\d{2})?(?:\\s*(am|pm|ص|م)(?!\\p{L}))?", FLAGS);
    private static final Pattern DATE_YMD = Pattern.compile("(?<![\\d/.-])(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?![\\d/.-]|:)", FLAGS);
    private static final Pattern DATE_XYZ = Pattern.compile("(?<![\\d/.-])(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2}|\\d{4})(?![\\d/.-]|:)", FLAGS);
    private static final Pattern DATE_DM = Pattern.compile("(?<![\\d/.-])(\\d{1,2})/(\\d{1,2})(?![\\d/.-])", FLAGS);
    private static final String[][] MONTHS = {
        {"jan", "يناير"}, {"feb", "فبراير"}, {"mar", "مارس"}, {"apr", "ابريل"}, {"may", "مايو"}, {"jun", "يونيو"},
        {"jul", "يوليو"}, {"aug", "اغسطس"}, {"sep", "سبتمبر"}, {"oct", "اكتوبر"}, {"nov", "نوفمبر"}, {"dec", "ديسمبر"}
    };
    private static final Pattern DATE_D_MON;
    private static final Pattern DATE_MON_D;
    static {
        StringBuilder alt = new StringBuilder();
        for (String[] m : MONTHS) alt.append(alt.length() == 0 ? "" : "|").append(m[0]).append("[a-z]*|").append(m[1]);
        DATE_D_MON = Pattern.compile("(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?[\\s-]*(" + alt + ")\\.?[\\s,-]*(\\d{4}|\\d{2})?(?![\\d:])", FLAGS);
        DATE_MON_D = Pattern.compile("(?<!\\p{L})(" + alt + ")\\.?\\s*(\\d{1,2})(?!\\d)(?:,?\\s*(\\d{4}))?", FLAGS);
    }

    private final String defaultCurrency;
    private final List<Pattern> otp, purchase, ignore, balance, amountLabels, merchantInline, merchantStop, merchantReject, cardPatterns;
    private final List<String> merchantLabels, cardLabels;
    private final Map<String, String> currencyByAlias = new HashMap<>();
    private final Pattern curBefore, curAfter, labeledNoCurrency;
    private final List<Bank> banks = new ArrayList<>();

    static final class Bank {
        String name;
        final List<String> senders = new ArrayList<>();
        final List<String> packages = new ArrayList<>();
        final List<Pattern> patterns = new ArrayList<>();
    }

    public PurchaseParser(JSONObject rules) throws JSONException {
        defaultCurrency = rules.optString("defaultCurrency", "SAR");
        otp = patterns(rules, "otpPatterns");
        purchase = patterns(rules, "purchasePatterns");
        ignore = patterns(rules, "ignorePatterns");
        balance = patterns(rules, "balancePatterns");
        amountLabels = patterns(rules, "amountLabels");
        merchantInline = new ArrayList<>();
        JSONArray inl = rules.optJSONArray("merchantInlinePatterns");
        if (inl != null) for (int i = 0; i < inl.length(); i++) {
            // Whole words only (so Arabic "من" doesn't match inside "ضمن"), then capture the rest of the line.
            merchantInline.add(compile("(?<![\\p{L}\\p{N}])(?:" + inl.getString(i) + ")(?![\\p{L}\\p{N}])\\s*[:：]?\\s*([^\\n]+)"));
        }
        merchantStop = patterns(rules, "merchantStopPatterns");
        merchantReject = patterns(rules, "merchantRejectPatterns");
        cardPatterns = patterns(rules, "cardPatterns");
        merchantLabels = foldedStrings(rules, "merchantLabels");
        cardLabels = foldedStrings(rules, "cardLabels");

        List<String> aliases = new ArrayList<>();
        JSONObject cur = rules.getJSONObject("currencies");
        for (Iterator<String> it = cur.keys(); it.hasNext(); ) {
            String code = it.next();
            JSONArray list = cur.getJSONArray(code);
            for (int i = 0; i < list.length(); i++) {
                String a = TextFold.fold(list.getString(i)).trim();
                currencyByAlias.put(a, code);
                aliases.add(a);
            }
        }
        Collections.sort(aliases, (a, b) -> b.length() - a.length()); // longest first: "ريال سعودي" before "ريال"
        StringBuilder alt = new StringBuilder();
        for (String a : aliases) {
            if (alt.length() > 0) alt.append('|');
            boolean latin = a.matches("[a-z.$€£]+");
            alt.append(latin ? "(?<![a-z])" : "(?<!\\p{L})").append(Pattern.quote(a)).append(latin ? "(?![a-z])" : "(?!\\p{L})");
        }
        String c = "(" + alt + ")";
        curBefore = Pattern.compile(c + "\\s*[:.]?\\s*" + NUM + "(?![\\d/:])", FLAGS);
        curAfter = Pattern.compile("(?<![\\d.,])" + NUM + "\\s*" + c, FLAGS);
        labeledNoCurrency = Pattern.compile("(?:amount|\\bamt\\b|مبلغ|بمبلغ|المبلغ|قيمه|بقيمه)\\s*[:：]?\\s*" + NUM + "(?![\\d/:])", FLAGS);

        JSONArray bankList = rules.optJSONArray("banks");
        if (bankList != null) {
            for (int i = 0; i < bankList.length(); i++) {
                JSONObject b = bankList.getJSONObject(i);
                Bank bank = new Bank();
                bank.name = b.optString("name");
                addSenders(b.optJSONArray("senders"), bank.senders);
                addPackages(b.optJSONArray("packages"), bank.packages);
                JSONArray ps = b.optJSONArray("patterns");
                if (ps != null) for (int j = 0; j < ps.length(); j++) bank.patterns.add(compile(ps.getString(j)));
                banks.add(bank);
            }
        }
    }

    // ---------- Public API ----------

    public Result parse(String text, String sender, String packageName, long receivedAt) {
        if (text == null || text.trim().isEmpty()) return new Result(null, Reason.EMPTY, null);
        TextFold t = TextFold.of(text.trim());
        String f = t.folded;
        if (any(otp, f)) return new Result(null, Reason.OTP, null);

        Bank bank = bankFor(sender, packageName);
        Matcher bm = null;
        if (bank != null) {
            for (Pattern p : bank.patterns) {
                Matcher m = p.matcher(f);
                if (m.find()) { bm = m; break; }
            }
        }
        String bankName = bank == null ? null : bank.name;
        if (bm == null && (any(ignore, f) || !any(purchase, f))) return new Result(null, Reason.NOT_PURCHASE, bankName);

        Purchase p = new Purchase();
        p.receivedAt = receivedAt;
        p.sender = sender != null ? sender : packageName;
        p.textHash = sha256(f.replaceAll("\\s+", " "));

        // Amount and currency
        String amountStr = group(bm, "amount");
        if (amountStr != null) {
            p.amount = toAmount(amountStr);
            String cur = group(bm, "currency");
            p.currency = cur != null ? currencyCode(cur) : null;
        }
        if (!(p.amount > 0)) {
            Amount a = findAmount(f);
            if (a == null) return new Result(null, Reason.NO_AMOUNT, bankName);
            p.amount = a.value;
            if (p.currency == null) p.currency = a.currency;
        }
        if (p.currency == null) {
            Amount a = findAmount(f);
            p.currency = a != null && a.currency != null ? a.currency : defaultCurrency;
        }

        // Merchant
        if (bm != null && hasGroup(bm, "merchant") && bm.group("merchant") != null) {
            p.merchant = cleanMerchant(t, bm.start("merchant"), bm.end("merchant"));
        }
        if (p.merchant == null) p.merchant = findMerchant(t);

        // Card
        String card = group(bm, "card");
        if (card != null) {
            Matcher m = LAST4.matcher(card);
            String last = null;
            while (m.find()) last = m.group(1);
            p.card = last;
        }
        if (p.card == null) p.card = findCard(f);

        // Date and time
        Calendar received = Calendar.getInstance();
        received.setTimeInMillis(receivedAt);
        String dateText = group(bm, "date");
        String timeText = group(bm, "time");
        int[] ymd = findDate(dateText != null ? dateText : f, received);
        int[] hm = findTime(timeText != null ? timeText : f);
        if (ymd == null) ymd = new int[] {received.get(Calendar.YEAR), received.get(Calendar.MONTH) + 1, received.get(Calendar.DAY_OF_MONTH)};
        p.date = String.format(Locale.US, "%04d-%02d-%02d", ymd[0], ymd[1], ymd[2]);
        if (hm == null && ymd[0] == received.get(Calendar.YEAR) && ymd[1] == received.get(Calendar.MONTH) + 1 && ymd[2] == received.get(Calendar.DAY_OF_MONTH)) {
            hm = new int[] {received.get(Calendar.HOUR_OF_DAY), received.get(Calendar.MINUTE)};
        }
        p.time = hm == null ? null : String.format(Locale.US, "%02d:%02d", hm[0], hm[1]);
        return new Result(p, null, bankName);
    }

    /** Normalizes a sender id / phone number / package so "AlRajhiBank", "alrajhibank" and "AL-RAJHI BANK" compare equal. */
    public static String normalizeSender(String s) {
        if (s == null) return "";
        String f = TextFold.fold(s).replaceAll("[^\\p{L}\\p{N}.]", "");
        return f.matches("\\d{10,}") ? f.substring(f.length() - 9) : f; // phone numbers: compare the last 9 digits (+9665… vs 05…)
    }

    // ---------- Amount ----------

    static final class Amount {
        double value;
        String currency;
    }

    private Amount findAmount(String f) {
        List<int[]> spans = new ArrayList<>();
        List<Amount> found = new ArrayList<>();
        List<Boolean> labeled = new ArrayList<>();
        collect(curBefore.matcher(f), 2, 1, false, f, spans, found, labeled);
        collect(curAfter.matcher(f), 1, 2, false, f, spans, found, labeled);
        collect(labeledNoCurrency.matcher(f), 1, -1, true, f, spans, found, labeled);
        Amount best = null;
        int bestPos = Integer.MAX_VALUE;
        boolean bestLabeled = false;
        for (int i = 0; i < found.size(); i++) {
            int pos = spans.get(i)[0];
            boolean lab = labeled.get(i);
            if ((lab && !bestLabeled) || (lab == bestLabeled && pos < bestPos)) {
                best = found.get(i); bestPos = pos; bestLabeled = lab;
            }
        }
        return best;
    }

    private void collect(Matcher m, int numGroup, int curGroup, boolean isLabeled, String f, List<int[]> spans, List<Amount> found, List<Boolean> labeled) {
        outer:
        while (m.find()) {
            int s = m.start(numGroup), e = m.end(numGroup);
            for (int[] sp : spans) if (s < sp[1] && e > sp[0]) continue outer; // same number already seen
            double v = toAmount(m.group(numGroup));
            if (!(v > 0) || v >= 1e7) continue;
            String ctx = context(f, m.start());
            if (any(balance, ctx)) continue;
            Amount a = new Amount();
            a.value = v;
            a.currency = curGroup > 0 ? currencyCode(m.group(curGroup)) : null;
            spans.add(new int[] {s, e});
            found.add(a);
            labeled.add(isLabeled || any(amountLabels, ctx));
        }
    }

    /** The words just before {@code pos}: up to 25 characters on the same line, and nothing before an earlier number. */
    private static String context(String f, int pos) {
        int from = Math.max(f.lastIndexOf('\n', pos - 1) + 1, pos - 25);
        for (int k = pos - 1; k >= from; k--) {
            if (Character.isDigit(f.charAt(k))) { from = k + 1; break; }
        }
        return f.substring(from, pos);
    }

    private String currencyCode(String alias) {
        String code = currencyByAlias.get(TextFold.fold(alias).trim());
        if (code != null) return code;
        String a = alias.trim().toUpperCase(Locale.ROOT);
        return a.matches("[A-Z]{3}") ? a : defaultCurrency;
    }

    private static double toAmount(String s) {
        try {
            return Math.round(Double.parseDouble(TextFold.fold(s).replace(",", "")) * 1000) / 1000.0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------- Merchant ----------

    private String findMerchant(TextFold t) {
        String f = t.folded;
        Matcher lm = LABELED_LINE.matcher(f);
        while (lm.find()) {
            if (merchantLabels.contains(lm.group(1).trim())) {
                String m = cleanMerchant(t, lm.start(2), lm.end(2));
                if (m != null) return m;
            }
        }
        for (Pattern inline : merchantInline) {
            Matcher m = inline.matcher(f);
            while (m.find()) {
                int s = m.start(1), e = m.end(1);
                for (Pattern stop : merchantStop) {
                    Matcher sm = stop.matcher(f).region(s, e);
                    if (sm.find()) e = Math.min(e, sm.start());
                }
                for (Pattern amt : new Pattern[] {curBefore, curAfter}) { // "at STARBUCKS SAR 45.00"
                    Matcher am = amt.matcher(f).region(s, e);
                    if (am.find()) e = Math.min(e, am.start());
                }
                String v = cleanMerchant(t, s, e);
                if (v != null) return v;
            }
        }
        return null;
    }

    private String cleanMerchant(TextFold t, int s, int e) {
        String folded = t.folded.substring(s, e).trim();
        for (Pattern r : merchantReject) if (r.matcher(folded).find()) return null;
        String v = t.originalRange(s, e)
                .replaceAll("[\\u200E\\u200F\\u061C\\u202A-\\u202E\\u2066-\\u2069]", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\s\"'“”«»:：\\-–*]+|[\\s\"'“”«».,;:：،؛\\-–*]+$", "");
        if (v.isEmpty() || !v.matches("(?s).*\\p{L}.*")) return null;
        if (v.length() > 40) {
            int cut = v.lastIndexOf(' ', 40);
            v = v.substring(0, cut > 15 ? cut : 40).trim();
        }
        return v;
    }

    // ---------- Card ----------

    private String findCard(String f) {
        Matcher lm = LABELED_LINE.matcher(f);
        while (lm.find()) {
            if (cardLabels.contains(lm.group(1).trim())) {
                Matcher d = LAST4.matcher(lm.group(2));
                String last = null;
                while (d.find()) last = d.group(1);
                if (last != null) return last;
            }
        }
        for (Pattern p : cardPatterns) {
            Matcher m = p.matcher(f);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    // ---------- Date & time ----------

    private static int[] findTime(String f) {
        Matcher m = TIME.matcher(f);
        while (m.find()) {
            int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
            String ap = m.group(3);
            if (min > 59) continue;
            if (ap != null) {
                if (h < 1 || h > 12) continue;
                boolean pm = ap.equalsIgnoreCase("pm") || ap.equals("م");
                h = (h % 12) + (pm ? 12 : 0);
            } else if (h > 23) continue;
            return new int[] {h, min};
        }
        return null;
    }

    /** Finds the date in the text that is closest to when the message arrived (within the last week), or null. */
    static int[] findDate(String f, Calendar received) {
        List<int[]> cands = new ArrayList<>();
        int ry = received.get(Calendar.YEAR);
        Matcher m = DATE_YMD.matcher(f);
        while (m.find()) cands.add(new int[] {i(m, 1), i(m, 2), i(m, 3)});
        m = DATE_XYZ.matcher(f);
        while (m.find()) {
            int a = i(m, 1), b = i(m, 2), c = i(m, 3);
            int y = c < 100 ? 2000 + c : c;
            cands.add(new int[] {y, b, a});            // day/month/year
            cands.add(new int[] {y, a, b});            // month/day/year
            if (c < 100) cands.add(new int[] {2000 + a, b, c}); // yy-mm-dd
        }
        m = DATE_D_MON.matcher(f);
        while (m.find()) cands.add(new int[] {year(m.group(3), ry), month(m.group(2)), i(m, 1)});
        m = DATE_MON_D.matcher(f);
        while (m.find()) cands.add(new int[] {year(m.group(3), ry), month(m.group(1)), i(m, 2)});
        if (cands.isEmpty()) {
            m = DATE_DM.matcher(f);
            while (m.find()) {
                cands.add(new int[] {ry, i(m, 2), i(m, 1)});
                cands.add(new int[] {ry, i(m, 1), i(m, 2)});
            }
        }
        long now = received.getTimeInMillis();
        int[] best = null;
        long bestDiff = Long.MAX_VALUE;
        for (int[] c : cands) {
            if (c[1] < 1 || c[1] > 12 || c[2] < 1 || c[2] > 31) continue;
            Calendar cal = Calendar.getInstance();
            cal.setLenient(false);
            cal.clear();
            cal.set(c[0], c[1] - 1, c[2], 12, 0, 0);
            long ms;
            try { ms = cal.getTimeInMillis(); } catch (IllegalArgumentException e) { continue; } // e.g. 31 Feb
            long diff = now - ms;
            if (diff < -36L * 3600_000 || diff > 8L * 86400_000) continue; // at most a day ahead or a week back
            if (Math.abs(diff) < bestDiff) { bestDiff = Math.abs(diff); best = c; }
        }
        return best;
    }

    private static int i(Matcher m, int g) { return Integer.parseInt(m.group(g)); }

    private static int year(String y, int fallback) {
        if (y == null) return fallback;
        int v = Integer.parseInt(y);
        return v < 100 ? 2000 + v : v;
    }

    private static int month(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (int k = 0; k < MONTHS.length; k++) if (n.startsWith(MONTHS[k][0]) || n.equals(MONTHS[k][1])) return k + 1;
        return 0;
    }

    // ---------- Helpers ----------

    private Bank bankFor(String sender, String pkg) {
        String s = normalizeSender(sender), p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        for (Bank b : banks) {
            if ((!s.isEmpty() && b.senders.contains(s)) || (!p.isEmpty() && b.packages.contains(p))) return b;
        }
        return null;
    }

    private static void addSenders(JSONArray arr, List<String> out) throws JSONException {
        if (arr != null) for (int i = 0; i < arr.length(); i++) out.add(normalizeSender(arr.getString(i)));
    }

    private static void addPackages(JSONArray arr, List<String> out) throws JSONException {
        if (arr != null) for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i).trim().toLowerCase(Locale.ROOT));
    }

    private static List<Pattern> patterns(JSONObject rules, String key) throws JSONException {
        List<Pattern> out = new ArrayList<>();
        JSONArray arr = rules.optJSONArray(key);
        if (arr != null) for (int i = 0; i < arr.length(); i++) out.add(compile(arr.getString(i)));
        return out;
    }

    private static List<String> foldedStrings(JSONObject rules, String key) throws JSONException {
        List<String> out = new ArrayList<>();
        JSONArray arr = rules.optJSONArray(key);
        if (arr != null) for (int i = 0; i < arr.length(); i++) out.add(TextFold.fold(arr.getString(i)).trim());
        return out;
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(TextFold.foldArabic(regex), FLAGS);
    }

    private static boolean any(List<Pattern> ps, String s) {
        for (Pattern p : ps) if (p.matcher(s).find()) return true;
        return false;
    }

    private static boolean hasGroup(Matcher m, String name) {
        try { m.start(name); return true; } catch (IllegalArgumentException e) { return false; }
    }

    private static String group(Matcher m, String name) {
        if (m == null || !hasGroup(m, name)) return null;
        String g = m.group(name);
        return g == null || g.trim().isEmpty() ? null : g.trim();
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
