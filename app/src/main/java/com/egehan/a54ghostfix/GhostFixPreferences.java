package com.egehan.a54ghostfix;

import android.content.Context;
import android.content.SharedPreferences;

final class GhostFixPreferences {
    static final String PREFS = "ghost_fix_preferences";
    static final String KEY_SHAKE = "shake_trigger";
    static final String KEY_VOLUME_UP = "volume_up_trigger";
    static final String KEY_VOLUME_DOWN = "volume_down_trigger";
    static final String KEY_SIDE_KEY = "side_key_trigger";
    static final String KEY_EMERGENCY_ENABLED = "emergency_control_enabled";
    static final String KEY_EMERGENCY_MODE = "emergency_control_mode";
    static final String KEY_LAUNCHER_REQUEST = "emergency_launcher_request";
    static final String KEY_LAUNCHER_PENDING = "emergency_launcher_pending";

    static final int MODE_GUARDED_TOUCH = 0;
    static final int MODE_KEYPAD = 1;

    private GhostFixPreferences() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean shakeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SHAKE, false);
    }

    static boolean volumeUpEnabled(Context context) {
        return prefs(context).getBoolean(KEY_VOLUME_UP, false);
    }

    static boolean volumeDownEnabled(Context context) {
        return prefs(context).getBoolean(KEY_VOLUME_DOWN, false);
    }

    static boolean sideKeyEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SIDE_KEY, true);
    }

    static boolean emergencyEnabled(Context context) {
        return prefs(context).getBoolean(KEY_EMERGENCY_ENABLED, false);
    }

    static int emergencyMode(Context context) {
        return prefs(context).getInt(KEY_EMERGENCY_MODE, MODE_GUARDED_TOUCH);
    }

    static void setEmergencyEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_EMERGENCY_ENABLED, enabled).apply();
    }

    static void setEmergencyMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_EMERGENCY_MODE, mode).apply();
    }

    static void requestEmergencyLauncher(Context context) {
        SharedPreferences preferences = prefs(context);
        int request = preferences.getInt(KEY_LAUNCHER_REQUEST, 0) + 1;
        preferences.edit()
                .putBoolean(KEY_EMERGENCY_ENABLED, true)
                .putInt(KEY_EMERGENCY_MODE, MODE_KEYPAD)
                .putInt(KEY_LAUNCHER_REQUEST, request)
                .putBoolean(KEY_LAUNCHER_PENDING, true)
                .apply();
    }

    static boolean launcherRequestPending(Context context) {
        return prefs(context).getBoolean(KEY_LAUNCHER_PENDING, false);
    }

    static void clearLauncherRequest(Context context) {
        prefs(context).edit().putBoolean(KEY_LAUNCHER_PENDING, false).apply();
    }
}
