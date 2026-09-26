package com.myname.expensetracker.purchases;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Optional second detection method: reads notifications posted by the bank apps chosen in settings.
 * Android only runs this after the user grants "Notification access" in system settings.
 */
public class BankNotificationListener extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Bundle x = n.extras;
        if (x == null) return;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence big = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence text = big != null ? big : x.getCharSequence(Notification.EXTRA_TEXT);
        if (text == null) return;
        String full = (title != null ? title + "\n" : "") + text;
        PurchaseProcessor.onNotification(this, sbn.getPackageName(), full, sbn.getPostTime());
    }
}
