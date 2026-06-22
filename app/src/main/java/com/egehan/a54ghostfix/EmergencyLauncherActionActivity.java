package com.egehan.a54ghostfix;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

public class EmergencyLauncherActionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!EmergencyControl.isAccessibilityServiceEnabled(this)) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            finish();
            return;
        }
        GhostFixPreferences.requestEmergencyLauncher(this);
        GhostTouchBypassService.openEmergencyLauncherIfRunning();
        finish();
    }
}
