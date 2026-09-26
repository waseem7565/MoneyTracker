package com.myname.expensetracker.purchases;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the "Dismiss" button on a detected-purchase notification. */
public class PurchaseActionReceiver extends BroadcastReceiver {
    static final String ACTION_DISMISS = "com.myname.expensetracker.DISMISS_PURCHASE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_DISMISS.equals(intent.getAction())) return;
        String id = intent.getStringExtra(PurchaseProcessor.EXTRA_PURCHASE_ID);
        if (id == null) return;
        new PurchaseStore(context).resolve(id, Purchase.DISMISSED);
        PurchaseProcessor.cancelNotification(context, id);
        PurchaseDetectionPlugin.onPurchasesChanged();
    }
}
