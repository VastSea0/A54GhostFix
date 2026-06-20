package com.egehan.a54ghostfix;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class GhostTouchBypassService extends AccessibilityService {
    private static final String TAG = "GhostTouchBypass";

    public static final String[] LAUNCHER_PACKAGES = {
            "com.whatsapp",
            "com.zhiliaoapp.musically", // TikTok
            "com.instagram.android",
            "com.sec.android.app.camera", // Samsung Camera
            "com.google.android.youtube",
            "com.android.chrome"
    };
    public static final String[] LAUNCHER_NAMES = {
            "WhatsApp", "TikTok", "Instagram", "Kamera", "YouTube", "Chrome"
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "onServiceConnected: GhostTouchBypassService connected!");
        vibrate(100);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Implement focus/window changes in later steps
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "onInterrupt: service interrupted");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        Log.d(TAG, "onKeyEvent: keyCode=" + KeyEvent.keyCodeToString(keyCode) + ", action=" + (action == KeyEvent.ACTION_DOWN ? "DOWN" : "UP"));

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "Volume key pressed: " + KeyEvent.keyCodeToString(keyCode));
                vibrate(50);
            }
            return true; // Intercept key event
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long durationMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vibratorManager != null) {
                vibratorManager.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } else {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(durationMs);
            }
        }
    }
}
