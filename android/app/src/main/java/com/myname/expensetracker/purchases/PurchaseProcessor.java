package com.myname.expensetracker.purchases;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.myname.expensetracker.MainActivity;
import com.myname.expensetracker.R;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Shared pipeline for SMS and bank-app notifications: sender filter → parse → de-duplicate → store → notify.
 * Nothing leaves the phone, and the message text is never stored or logged.
 */
public final class PurchaseProcessor {
    private static final String TAG = "PurchaseDetection";
    static final String CHANNEL_ID = "detected_purchases";
    public static final String EXTRA_PURCHASE_ID = "com.myname.expensetracker.PURCHASE_ID";
    static final String ACTION_OPEN = "com.myname.expensetracker.OPEN_PURCHASE";

    private static PurchaseParser parser;

    private PurchaseProcessor() {}

    static synchronized PurchaseParser parser(Context context) throws Exception {
        if (parser == null) {
            try (InputStream in = context.getAssets().open("purchase_rules.json")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
                parser = new PurchaseParser(new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)));
            }
        }
        return parser;
    }

    /** Called for every incoming SMS. */
    static void onSms(Context context, String sender, String body, long receivedAt) {
        PurchaseStore store = new PurchaseStore(context);
        if (!store.isEnabled() || !store.isSmsEnabled() || !store.isSenderWatched(sender)) return;
        handle(context, store, body, sender, null, sender, "sms", receivedAt);
    }

    /** Called for every notification posted by another app. */
    static void onNotification(Context context, String packageName, String text, long postedAt) {
        PurchaseStore store = new PurchaseStore(context);
        if (!store.isEnabled() || !store.isNotificationsEnabled()) return;
        String label = store.watchedAppLabel(packageName);
        if (label == null) return;
        handle(context, store, text, null, packageName, label, "notification", postedAt);
    }

    private static void handle(Context context, PurchaseStore store, String text, String sender, String pkg, String label, String source, long receivedAt) {
        try {
            PurchaseParser.Result r = parser(context).parse(text, sender, pkg, receivedAt);
            if (r.purchase == null) {
                Log.d(TAG, "Message from a watched " + source + " sender ignored: " + r.reason); // reason only, never the text
                return;
            }
            Purchase p = r.purchase;
            p.source = source;
            p.senderLabel = label;
            Purchase added = store.addIfNew(p);
            if (added == null) {
                Log.d(TAG, "Duplicate purchase ignored");
                return;
            }
            showNotification(context, added);
            PurchaseDetectionPlugin.onPurchaseDetected(added);
        } catch (Exception e) {
            Log.e(TAG, "Could not process a bank message", e);
        }
    }

    // ---------- Notification ----------

    static String describe(Purchase p) {
        String amount = String.format(Locale.US, "%,.2f %s", p.amount, p.currency);
        return p.merchant != null ? amount + " at " + p.merchant : amount;
    }

    static int notificationId(String purchaseId) {
        return 0x50000000 | (purchaseId.hashCode() & 0x0FFFFFFF);
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Detected purchases", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Card purchases found in your bank messages, ready to add as expenses");
        nm.createNotificationChannel(ch);
    }

    private static void showNotification(Context context, Purchase p) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return; // Still listed under "Pending purchases" in the app.
        }
        ensureChannel(context);
        int id = notificationId(p.id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        Intent open = new Intent(context, MainActivity.class)
                .setAction(ACTION_OPEN)
                .putExtra(EXTRA_PURCHASE_ID, p.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(context, id, open, flags);

        Intent dismiss = new Intent(context, PurchaseActionReceiver.class)
                .setAction(PurchaseActionReceiver.ACTION_DISMISS)
                .putExtra(EXTRA_PURCHASE_ID, p.id);
        PendingIntent dismissPi = PendingIntent.getBroadcast(context, id, dismiss, flags);

        String body = "Tap to add it as an expense" + (p.card != null ? " · card •" + p.card : "");
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(0xFF2A78D6)
                .setContentTitle("New purchase: " + describe(p))
                .setContentText(body)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(0, "Dismiss", dismissPi);
        try {
            NotificationManagerCompat.from(context).notify(id, b.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission missing");
        }
    }

    static void cancelNotification(Context context, String purchaseId) {
        NotificationManagerCompat.from(context).cancel(notificationId(purchaseId));
    }
}
