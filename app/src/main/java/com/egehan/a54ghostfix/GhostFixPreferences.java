package com.egehan.a54ghostfix;

import android.content.Context;
import android.content.SharedPreferences;

final class GhostFixPreferences {
    static final String PREFS = "ghost_fix_preferences";
    static final String KEY_SHAKE = "shake_trigger";
    static final String KEY_VOLUME_UP = "volume_up_trigger";
    static final String KEY_VOLUME_DOWN = "volume_down_trigger";
    static final String KEY_SIDE_KEY = "side_key_trigger";

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
}
