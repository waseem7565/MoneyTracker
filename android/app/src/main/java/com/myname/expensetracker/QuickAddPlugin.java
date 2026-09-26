package com.myname.expensetracker;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Small helpers for the "Add Transaction" launcher: close it, open the keyboard, and tell whether the phone is locked. */
@CapacitorPlugin(name = "QuickAdd")
public class QuickAddPlugin extends Plugin {

    /** Closes the quick-add screen and returns to wherever the phone was (another app, home or the lock screen). */
    @PluginMethod
    public void close(PluginCall call) {
        call.resolve();
        Activity activity = getActivity();
        activity.runOnUiThread(activity::finishAndRemoveTask);
    }

    /** Opens the on-screen keyboard for the focused field (a web page can't always do this on its own). */
    @PluginMethod
    public void showKeyboard(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            View webView = getBridge().getWebView();
            webView.requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
            call.resolve();
        });
    }

    @PluginMethod
    public void isLocked(PluginCall call) {
        KeyguardManager km = (KeyguardManager) getContext().getSystemService(Context.KEYGUARD_SERVICE);
        JSObject r = new JSObject();
        r.put("locked", km != null && km.isKeyguardLocked());
        call.resolve(r);
    }
}
