package com.myname.expensetracker.purchases;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receives incoming SMS even when the app is closed (SMS_RECEIVED is allowed to wake manifest receivers).
 * Multi-part messages are joined per sender before parsing. Requires the RECEIVE_SMS permission.
 */
public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;
        Map<String, StringBuilder> bodies = new LinkedHashMap<>();
        long receivedAt = System.currentTimeMillis();
        for (SmsMessage part : parts) {
            if (part == null) continue;
            String from = part.getDisplayOriginatingAddress();
            if (from == null) from = "";
            StringBuilder sb = bodies.get(from);
            if (sb == null) bodies.put(from, sb = new StringBuilder());
            if (part.getMessageBody() != null) sb.append(part.getMessageBody());
        }
        for (Map.Entry<String, StringBuilder> e : bodies.entrySet()) {
            PurchaseProcessor.onSms(context, e.getKey(), e.getValue().toString(), receivedAt);
        }
    }
}
