package com.myname.expensetracker;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.myname.expensetracker.purchases.PurchaseDetectionPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(PurchaseDetectionPlugin.class); // local plugin, must be registered before super.onCreate
        super.onCreate(savedInstanceState);
    }
}
