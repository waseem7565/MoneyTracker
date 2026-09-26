package com.myname.expensetracker.quickadd;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * "Add Transaction" launcher: opens Expense Tracker's quick-add screen and closes. Allowed over the lock screen so
 * the side-button double press works while locked (the quick-add screen itself only shows the add form).
 */
public class LaunchQuickAdd extends Activity {
    private static final String APP = "com.myname.expensetracker";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        super.onCreate(savedInstanceState);
        try {
            startActivity(new Intent().setClassName(APP, APP + ".QuickAddActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Install Expense Tracker first.", Toast.LENGTH_LONG).show();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
