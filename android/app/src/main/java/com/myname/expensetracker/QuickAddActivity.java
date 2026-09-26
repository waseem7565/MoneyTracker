package com.myname.expensetracker;

import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.CapConfig;
import com.getcapacitor.Logger;

import java.lang.reflect.Field;

/**
 * Second launcher entry, "Add Transaction": opens the app straight into the add-transaction pop-up (the web app
 * sees ?quickAdd=1 and shows nothing else). It runs as its own task, can show over the lock screen, and closes
 * itself after saving or cancelling, so the phone goes back to wherever it was.
 */
public class QuickAddActivity extends BridgeActivity {
    static final String START_PATH = "/?quickAdd=1";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(QuickAddPlugin.class);
        config = quickAddConfig();
        if (!START_PATH.equals(config.getStartPath())) {
            // Without the quick-add start path this would be the full app (balances and history), possibly over the
            // lock screen. Fail closed: no lock-screen flags, and close straight away.
            super.onCreate(savedInstanceState);
            Toast.makeText(this, "Add Transaction isn't available. Open Expense Tracker instead.", Toast.LENGTH_LONG).show();
            finishAndRemoveTask();
            return;
        }

        // Show over the lock screen (only the add form is ever shown in this mode) and wake the screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        super.onCreate(savedInstanceState);
    }

    /**
     * The app's normal Capacitor config (plugins, server settings), but starting at the quick-add URL. Capacitor has no
     * public setter for it, so a private field is set; onCreate checks that this worked (a Capacitor update could break it).
     */
    private CapConfig quickAddConfig() {
        CapConfig cfg = CapConfig.loadDefault(this);
        try {
            Field startPath = CapConfig.class.getDeclaredField("startPath");
            startPath.setAccessible(true);
            startPath.set(cfg, START_PATH);
        } catch (ReflectiveOperationException e) {
            Logger.error("QuickAdd", "Couldn't set the quick-add start path", e);
        }
        return cfg;
    }
}
