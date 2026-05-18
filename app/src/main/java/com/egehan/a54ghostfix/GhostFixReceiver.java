package com.egehan.a54ghostfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class GhostFixReceiver extends BroadcastReceiver {
    static final String ACTION_FIX = "com.egehan.a54ghostfix.ACTION_FIX";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_FIX.equals(intent.getAction())) {
            GhostFixer.run(context, null);
        }
    }
}
