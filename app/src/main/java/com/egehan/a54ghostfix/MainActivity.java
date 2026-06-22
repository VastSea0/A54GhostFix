package com.egehan.a54ghostfix;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private TextView statusText;
    private MaterialButton permissionButton;
    private MaterialButton fixButton;
    private MaterialButton accessibilityButton;
    private MaterialButton sideKeyButton;
    private MaterialButton emergencyButton;
    private MaterialButton emergencyLauncherButton;
    private MaterialButtonToggleGroup emergencyModeGroup;
    private SwitchMaterial shakeSwitch;
    private SwitchMaterial volumeUpSwitch;
    private SwitchMaterial volumeDownSwitch;
    private SwitchMaterial sideKeySwitch;
    private SharedPreferences preferences;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::refresh;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refresh;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> refresh();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        permissionButton = findViewById(R.id.permission_button);
        fixButton = findViewById(R.id.fix_button);
        accessibilityButton = findViewById(R.id.accessibility_button);
        sideKeyButton = findViewById(R.id.side_key_button);
        emergencyButton = findViewById(R.id.emergency_button);
        emergencyLauncherButton = findViewById(R.id.emergency_launcher_button);
        emergencyModeGroup = findViewById(R.id.emergency_mode_group);
        shakeSwitch = findViewById(R.id.shake_switch);
        volumeUpSwitch = findViewById(R.id.volume_up_switch);
        volumeDownSwitch = findViewById(R.id.volume_down_switch);
        sideKeySwitch = findViewById(R.id.side_key_switch);
        preferences = GhostFixPreferences.prefs(this);

        permissionButton.setOnClickListener(view -> GhostFixer.requestPermission());
        fixButton.setOnClickListener(view -> runFix());
        accessibilityButton.setOnClickListener(view -> openAccessibilitySettings());
        sideKeyButton.setOnClickListener(view -> openSideKeySettings());
        emergencyButton.setOnClickListener(view -> toggleEmergencyControl());
        emergencyLauncherButton.setOnClickListener(view -> openEmergencyLauncher());
        emergencyModeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            GhostFixPreferences.setEmergencyMode(
                    this,
                    checkedId == R.id.keypad_mode_button
                            ? GhostFixPreferences.MODE_KEYPAD
                            : GhostFixPreferences.MODE_GUARDED_TOUCH
            );
        });

        bindSwitch(shakeSwitch, GhostFixPreferences.KEY_SHAKE);
        bindSwitch(volumeUpSwitch, GhostFixPreferences.KEY_VOLUME_UP);
        bindSwitch(volumeDownSwitch, GhostFixPreferences.KEY_VOLUME_DOWN);
        bindSwitch(sideKeySwitch, GhostFixPreferences.KEY_SIDE_KEY, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        refresh();
        refreshEmergencyControls();
    }

    @Override
    protected void onPause() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        super.onPause();
    }

    private void refresh() {
        boolean ready = GhostFixer.isShizukuReady();
        boolean permitted = GhostFixer.hasPermission();

        if (!ready) {
            statusText.setText(R.string.status_shizuku_missing);
        } else if (!permitted) {
            statusText.setText(R.string.status_permission_needed);
        } else {
            statusText.setText(R.string.status_ready);
        }

        permissionButton.setVisibility(ready && !permitted ? View.VISIBLE : View.GONE);
        fixButton.setEnabled(ready && permitted);
    }

    private void runFix() {
        fixButton.setEnabled(false);
        statusText.setText(R.string.fixing);
        GhostFixer.run(this, (success, message) -> {
            statusText.setText(message);
            fixButton.setEnabled(GhostFixer.isShizukuReady() && GhostFixer.hasPermission());
        });
    }

    private void bindSwitch(SwitchMaterial switchView, String key) {
        bindSwitch(switchView, key, false);
    }

    private void bindSwitch(SwitchMaterial switchView, String key, boolean defaultValue) {
        switchView.setChecked(preferences.getBoolean(key, defaultValue));
        switchView.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(key, isChecked).apply());
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void toggleEmergencyControl() {
        if (!EmergencyControl.isAccessibilityServiceEnabled(this)) {
            openAccessibilitySettings();
            return;
        }
        EmergencyControl.toggle(this);
        refreshEmergencyControls();
    }

    private void refreshEmergencyControls() {
        int mode = GhostFixPreferences.emergencyMode(this);
        emergencyModeGroup.check(mode == GhostFixPreferences.MODE_KEYPAD
                ? R.id.keypad_mode_button
                : R.id.guarded_mode_button);
        emergencyButton.setText(GhostFixPreferences.emergencyEnabled(this)
                ? R.string.stop_emergency_control
                : R.string.start_emergency_control);
    }

    private void openEmergencyLauncher() {
        if (!EmergencyControl.isAccessibilityServiceEnabled(this)) {
            openAccessibilitySettings();
            return;
        }
        GhostFixPreferences.requestEmergencyLauncher(this);
        GhostTouchBypassService.openEmergencyLauncherIfRunning();
        moveTaskToBack(true);
    }

    private void openSideKeySettings() {
        Intent samsungSideKey = new Intent("com.samsung.android.settings.action.SIDE_KEY_SETTINGS");
        try {
            startActivity(samsungSideKey);
        } catch (ActivityNotFoundException notFound) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }
}
