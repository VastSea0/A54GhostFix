package com.egehan.a54ghostfix;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

final class EmergencyControl {
    private EmergencyControl() {
    }

    static boolean toggle(Context context) {
        boolean enabled = !GhostFixPreferences.emergencyEnabled(context);
        GhostFixPreferences.setEmergencyEnabled(context, enabled);
        return enabled;
    }

    static boolean isAccessibilityServiceEnabled(Context context) {
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (TextUtils.isEmpty(enabledServices)) {
            return false;
        }

        String expected = new ComponentName(
                context,
                GhostFixAccessibilityService.class
        ).flattenToString();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            if (expected.equalsIgnoreCase(splitter.next())) {
                return true;
            }
        }
        return false;
    }
}
