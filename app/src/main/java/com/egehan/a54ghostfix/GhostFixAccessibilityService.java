package com.egehan.a54ghostfix;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class GhostFixAccessibilityService extends AccessibilityService implements SensorEventListener {
    private static final long TRIGGER_DEBOUNCE_MS = 3500;
    private static final float SHAKE_THRESHOLD = 19.5f;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private SharedPreferences preferences;
    private long lastTriggerAt;
    private boolean listeningForShake;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener =
            (sharedPreferences, key) -> refreshSensors();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);

        preferences = GhostFixPreferences.prefs(this);
        preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        refreshSensors();
    }

    @Override
    public void onDestroy() {
        if (preferences != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
        unregisterShake();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return false;
        }

        int keyCode = event.getKeyCode();
        boolean shouldRun =
                (keyCode == KeyEvent.KEYCODE_VOLUME_UP && GhostFixPreferences.volumeUpEnabled(this)) ||
                (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && GhostFixPreferences.volumeDownEnabled(this));

        if (shouldRun) {
            triggerFix();
        }
        return shouldRun;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!GhostFixPreferences.shakeEnabled(this) || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        double force = Math.sqrt(x * x + y * y + z * z);
        if (force >= SHAKE_THRESHOLD) {
            triggerFix();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void triggerFix() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTriggerAt < TRIGGER_DEBOUNCE_MS) {
            return;
        }
        lastTriggerAt = now;
        vibrate();
        GhostFixer.run(this, null);
    }

    private void refreshSensors() {
        if (GhostFixPreferences.shakeEnabled(this)) {
            registerShake();
        } else {
            unregisterShake();
        }
    }

    private void registerShake() {
        if (listeningForShake || sensorManager == null || accelerometer == null) {
            return;
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        listeningForShake = true;
    }

    private void unregisterShake() {
        if (!listeningForShake || sensorManager == null) {
            return;
        }
        sensorManager.unregisterListener(this);
        listeningForShake = false;
    }

    @SuppressWarnings("deprecation")
    private void vibrate() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(60);
        }
    }
}
