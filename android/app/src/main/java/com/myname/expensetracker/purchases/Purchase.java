package com.myname.expensetracker.purchases;

import org.json.JSONException;
import org.json.JSONObject;

/** A purchase extracted from a bank message. Only these details are kept, never the message itself. */
public final class Purchase {
    public static final String PENDING = "pending";
    public static final String SAVED = "saved";
    public static final String DISMISSED = "dismissed";

    public String id;
    public double amount;
    public String currency;
    public String merchant;   // may be null
    public String card;       // last 4 digits, may be null
    public String date;       // yyyy-MM-dd
    public String time;       // HH:mm, may be null
    public String source;     // "sms" | "notification" | "test"
    public String sender;     // SMS sender id or app package
    public String senderLabel;
    public long receivedAt;
    public String status = PENDING;
    public long resolvedAt;
    public String textHash;   // SHA-256 of the normalized message, only used to spot duplicates

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("amount", amount);
            o.put("currency", currency);
            o.put("merchant", merchant == null ? JSONObject.NULL : merchant);
            o.put("card", card == null ? JSONObject.NULL : card);
            o.put("date", date);
            o.put("time", time == null ? JSONObject.NULL : time);
            o.put("source", source);
            o.put("sender", sender == null ? JSONObject.NULL : sender);
            o.put("senderLabel", senderLabel == null ? JSONObject.NULL : senderLabel);
            o.put("receivedAt", receivedAt);
            o.put("status", status);
            o.put("resolvedAt", resolvedAt);
            if (textHash != null) o.put("textHash", textHash);
        } catch (JSONException ignored) {
            // put() only throws for non-finite numbers, which amount never is.
        }
        return o;
    }

    public static Purchase fromJson(JSONObject o) {
        Purchase p = new Purchase();
        p.id = o.optString("id");
        p.amount = o.optDouble("amount", 0);
        p.currency = o.optString("currency", "SAR");
        p.merchant = optNullable(o, "merchant");
        p.card = optNullable(o, "card");
        p.date = o.optString("date");
        p.time = optNullable(o, "time");
        p.source = o.optString("source");
        p.sender = optNullable(o, "sender");
        p.senderLabel = optNullable(o, "senderLabel");
        p.receivedAt = o.optLong("receivedAt");
        p.status = o.optString("status", PENDING);
        p.resolvedAt = o.optLong("resolvedAt");
        p.textHash = optNullable(o, "textHash");
        return p;
    }

    private static String optNullable(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }
}
