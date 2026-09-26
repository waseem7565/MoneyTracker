package com.myname.expensetracker.purchases;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Bridge between the web app and on-device purchase detection.
 * Events: "purchaseDetected" (a new pending purchase), "purchaseTapped" (the user tapped its notification),
 * "purchasesChanged" (something was dismissed from a notification).
 */
@CapacitorPlugin(
    name = "PurchaseDetection",
    permissions = { @Permission(alias = "sms", strings = { Manifest.permission.RECEIVE_SMS }) }
)
public class PurchaseDetectionPlugin extends Plugin {
    private static WeakReference<PurchaseDetectionPlugin> instance = new WeakReference<>(null);
    private PurchaseStore store;

    @Override
    public void load() {
        store = new PurchaseStore(getContext());
        instance = new WeakReference<>(this);
        PurchaseProcessor.ensureChannel(getContext());
        handleLaunchIntent(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        handleLaunchIntent(intent);
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null) return;
        String id = intent.getStringExtra(PurchaseProcessor.EXTRA_PURCHASE_ID);
        if (id == null) return;
        intent.removeExtra(PurchaseProcessor.EXTRA_PURCHASE_ID); // don't reopen it on rotation / relaunch
        JSObject data = new JSObject();
        data.put("id", id);
        notifyListeners("purchaseTapped", data, true); // kept until the web app's listener is attached
    }

    static void onPurchaseDetected(Purchase p) {
        PurchaseDetectionPlugin pl = instance.get();
        if (pl != null) pl.notifyListeners("purchaseDetected", toJs(p));
    }

    static void onPurchasesChanged() {
        PurchaseDetectionPlugin pl = instance.get();
        if (pl != null) pl.notifyListeners("purchasesChanged", new JSObject());
    }

    // ---------- Settings ----------

    @PluginMethod
    public void getConfig(PluginCall call) {
        JSObject r = new JSObject();
        r.put("enabled", store.isEnabled());
        r.put("smsEnabled", store.isSmsEnabled());
        r.put("notificationsEnabled", store.isNotificationsEnabled());
        r.put("senders", new JSArray(store.getSenders()));
        r.put("apps", toJsArray(store.getApps()));
        call.resolve(r);
    }

    @PluginMethod
    public void setConfig(PluginCall call) {
        if (call.getData().has("enabled")) store.setEnabled(call.getBoolean("enabled", false));
        if (call.getData().has("smsEnabled")) store.setSmsEnabled(call.getBoolean("smsEnabled", true));
        if (call.getData().has("notificationsEnabled")) store.setNotificationsEnabled(call.getBoolean("notificationsEnabled", false));
        JSArray senders = call.getArray("senders");
        if (senders != null) store.setSenders(senders);
        JSArray apps = call.getArray("apps");
        if (apps != null) store.setApps(apps);
        getConfig(call);
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject r = new JSObject();
        r.put("sms", getPermissionState("sms").toString().toLowerCase(Locale.ROOT));
        r.put("notificationAccess", notificationAccessEnabled());
        r.put("postNotifications", NotificationManagerCompat.from(getContext()).areNotificationsEnabled());
        r.put("hasTelephony", getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY));
        call.resolve(r);
    }

    private boolean notificationAccessEnabled() {
        return NotificationManagerCompat.getEnabledListenerPackages(getContext()).contains(getContext().getPackageName());
    }

    @PluginMethod
    public void openNotificationAccessSettings(PluginCall call) {
        Intent i;
        if (Build.VERSION.SDK_INT >= 30) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(getContext(), BankNotificationListener.class).flattenToString());
        } else {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        startSettings(i, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), call);
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        startSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getContext().getPackageName(), null)),
                new Intent(Settings.ACTION_SETTINGS), call);
    }

    private void startSettings(Intent i, Intent fallback, PluginCall call) {
        try {
            getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            // Some phones don't have the specific screen.
            getContext().startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        call.resolve();
    }

    /** Apps that appear in the launcher, so the user can pick their bank apps. Only names are read. */
    @PluginMethod
    public void listApps(PluginCall call) {
        PackageManager pm = getContext().getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        List<JSObject> apps = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ResolveInfo ri : infos) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getContext().getPackageName()) || seen.contains(pkg)) continue;
            seen.add(pkg);
            JSObject a = new JSObject();
            a.put("package", pkg);
            a.put("label", String.valueOf(ri.loadLabel(pm)));
            apps.add(a);
        }
        Collections.sort(apps, (x, y) -> x.getString("label", "").compareToIgnoreCase(y.getString("label", "")));
        JSObject r = new JSObject();
        r.put("apps", new JSArray(apps));
        call.resolve(r);
    }

    // ---------- Purchases ----------

    @PluginMethod
    public void getPending(PluginCall call) {
        JSArray arr = new JSArray();
        for (Purchase p : store.pending()) arr.put(toJs(p));
        JSObject r = new JSObject();
        r.put("purchases", arr);
        call.resolve(r);
    }

    @PluginMethod
    public void getPurchase(PluginCall call) {
        Purchase p = store.get(call.getString("id", ""));
        JSObject r = new JSObject();
        r.put("purchase", p == null ? null : toJs(p));
        call.resolve(r);
    }

    /** Marks a purchase as saved or dismissed and removes its notification. */
    @PluginMethod
    public void resolvePending(PluginCall call) {
        String id = call.getString("id");
        String status = call.getString("status", Purchase.DISMISSED);
        if (id == null || !(Purchase.SAVED.equals(status) || Purchase.DISMISSED.equals(status))) {
            call.reject("id and a status of \"saved\" or \"dismissed\" are required");
            return;
        }
        store.resolve(id, status);
        PurchaseProcessor.cancelNotification(getContext(), id);
        call.resolve();
    }

    /** Parser test from settings. The text is parsed in memory and never stored. */
    @PluginMethod
    public void parse(PluginCall call) {
        String text = call.getString("text", "");
        String sender = call.getString("sender", "");
        JSObject r = new JSObject();
        try {
            PurchaseParser.Result res = PurchaseProcessor.parser(getContext()).parse(text, sender.isEmpty() ? null : sender, null, System.currentTimeMillis());
            r.put("ok", res.purchase != null);
            r.put("reason", res.reason == null ? null : res.reason.name().toLowerCase(Locale.ROOT));
            r.put("bank", res.bankName);
            r.put("senderWatched", !sender.isEmpty() && store.isSenderWatched(sender));
            if (res.purchase != null) {
                res.purchase.source = "test";
                r.put("purchase", toJs(res.purchase));
            }
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Could not load the parsing rules", e);
        }
    }

    // ---------- Helpers ----------

    private static JSObject toJs(Purchase p) {
        JSONObject o = p.toJson();
        o.remove("textHash");
        try {
            return new JSObject(o.toString());
        } catch (JSONException e) {
            return new JSObject();
        }
    }

    private static JSArray toJsArray(JSONArray a) {
        try {
            return new JSArray(a.toString());
        } catch (JSONException e) {
            return new JSArray();
        }
    }
}
