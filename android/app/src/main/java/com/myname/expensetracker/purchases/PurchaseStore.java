package com.myname.expensetracker.purchases;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * On-device storage (private SharedPreferences) for the detection settings and detected purchases.
 * Only extracted details are stored, never message text. Saved and dismissed purchases are kept for
 * two weeks as tombstones, so the same purchase arriving again (SMS + app notification, or a re-sent
 * notification) isn't offered twice.
 */
public final class PurchaseStore {
    private static final String PREFS = "purchase_detection";
    private static final String KEY_PURCHASES = "purchases";
    private static final long KEEP_RESOLVED_MS = 14L * 86400_000;
    private static final long SAME_PURCHASE_WINDOW_MS = 20L * 60_000;
    private static final int MAX_PENDING = 200;

    private final SharedPreferences prefs;

    public PurchaseStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- Settings ----------

    public boolean isEnabled() { return prefs.getBoolean("enabled", false); }
    public boolean isSmsEnabled() { return prefs.getBoolean("smsEnabled", true); }
    public boolean isNotificationsEnabled() { return prefs.getBoolean("notificationsEnabled", false); }
    public List<String> getSenders() { return readList("senders"); }
    public JSONArray getApps() { return readArray("apps"); } // [{package, label}]

    public void setEnabled(boolean v) { prefs.edit().putBoolean("enabled", v).apply(); }
    public void setSmsEnabled(boolean v) { prefs.edit().putBoolean("smsEnabled", v).apply(); }
    public void setNotificationsEnabled(boolean v) { prefs.edit().putBoolean("notificationsEnabled", v).apply(); }
    public void setSenders(JSONArray senders) { prefs.edit().putString("senders", senders.toString()).apply(); }
    public void setApps(JSONArray apps) { prefs.edit().putString("apps", apps.toString()).apply(); }

    public boolean isSenderWatched(String sender) {
        String s = PurchaseParser.normalizeSender(sender);
        if (s.isEmpty()) return false;
        for (String w : getSenders()) if (s.equals(PurchaseParser.normalizeSender(w))) return true;
        return false;
    }

    public String watchedAppLabel(String packageName) {
        JSONArray apps = getApps();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a != null && packageName.equalsIgnoreCase(a.optString("package"))) return a.optString("label", packageName);
        }
        return null;
    }

    // ---------- Purchases ----------

    public synchronized List<Purchase> all() {
        List<Purchase> out = new ArrayList<>();
        JSONArray arr = readArray(KEY_PURCHASES);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(Purchase.fromJson(o));
        }
        return out;
    }

    public synchronized List<Purchase> pending() {
        List<Purchase> out = new ArrayList<>();
        for (Purchase p : all()) if (Purchase.PENDING.equals(p.status)) out.add(p);
        return out;
    }

    public synchronized Purchase get(String id) {
        for (Purchase p : all()) if (p.id.equals(id)) return p;
        return null;
    }

    /**
     * Stores a newly detected purchase unless it's one we already have. When it's a duplicate, details the
     * earlier copy was missing (merchant, card, time) are filled in. Returns the new purchase, or null if duplicate.
     */
    public synchronized Purchase addIfNew(Purchase p) {
        List<Purchase> list = all();
        long now = System.currentTimeMillis();
        for (Purchase q : list) {
            if (isSame(p, q)) {
                boolean changed = false;
                if (q.merchant == null && p.merchant != null) { q.merchant = p.merchant; changed = true; }
                if (q.card == null && p.card != null) { q.card = p.card; changed = true; }
                if (q.time == null && p.time != null) { q.time = p.time; changed = true; }
                if (changed) write(list);
                return null;
            }
        }
        p.id = UUID.randomUUID().toString();
        p.status = Purchase.PENDING;
        list.add(p);
        // Forget old tombstones, and cap the pending list.
        List<Purchase> kept = new ArrayList<>();
        int pendingCount = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            Purchase q = list.get(i);
            boolean pending = Purchase.PENDING.equals(q.status);
            if (pending ? ++pendingCount <= MAX_PENDING : now - q.resolvedAt < KEEP_RESOLVED_MS) kept.add(0, q);
        }
        write(kept);
        return p;
    }

    public synchronized Purchase resolve(String id, String status) {
        List<Purchase> list = all();
        Purchase hit = null;
        for (Purchase q : list) {
            if (q.id.equals(id)) {
                q.status = status;
                q.resolvedAt = System.currentTimeMillis();
                hit = q;
            }
        }
        if (hit != null) write(list);
        return hit;
    }

    static boolean isSame(Purchase a, Purchase b) {
        if (a.textHash != null && a.textHash.equals(b.textHash) && Math.abs(a.receivedAt - b.receivedAt) < 3L * 86400_000) return true;
        if (Math.abs(a.amount - b.amount) > 0.005 || !eq(a.currency, b.currency)) return false;
        if (a.card != null && b.card != null && !a.card.equals(b.card)) return false;
        if (!similarMerchant(a.merchant, b.merchant)) return false;
        if (a.time != null && b.time != null) return a.date.equals(b.date) && minutesApart(a.time, b.time) <= 2;
        return Math.abs(a.receivedAt - b.receivedAt) <= SAME_PURCHASE_WINDOW_MS;
    }

    private static boolean similarMerchant(String a, String b) {
        if (a == null || b == null) return true;
        String x = key(a), y = key(b);
        if (x.isEmpty() || y.isEmpty()) return true;
        return x.startsWith(y) || y.startsWith(x) || x.regionMatches(0, y, 0, Math.min(5, Math.min(x.length(), y.length())));
    }

    private static String key(String s) {
        return TextFold.fold(s).replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
    }

    private static int minutesApart(String t1, String t2) {
        try {
            String[] a = t1.split(":"), b = t2.split(":");
            return Math.abs(Integer.parseInt(a[0]) * 60 + Integer.parseInt(a[1]) - Integer.parseInt(b[0]) * 60 - Integer.parseInt(b[1]));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    private void write(List<Purchase> list) {
        JSONArray arr = new JSONArray();
        for (Purchase q : list) arr.put(q.toJson());
        prefs.edit().putString(KEY_PURCHASES, arr.toString()).apply();
    }

    private JSONArray readArray(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private List<String> readList(String key) {
        List<String> out = new ArrayList<>();
        JSONArray arr = readArray(key);
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
