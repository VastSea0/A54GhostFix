package com.egehan.a54ghostfix;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class EmergencyControlTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        update();
    }

    @Override
    @SuppressLint("StartActivityAndCollapseDeprecated")
    public void onClick() {
        super.onClick();
        if (!EmergencyControl.isAccessibilityServiceEnabled(this)) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                startActivityAndCollapse(pendingIntent);
            } else {
                startActivityAndCollapse(intent);
            }
            return;
        }
        EmergencyControl.toggle(this);
        update();
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean serviceEnabled = EmergencyControl.isAccessibilityServiceEnabled(this);
        boolean enabled = serviceEnabled && GhostFixPreferences.emergencyEnabled(this);
        tile.setState(serviceEnabled
                ? (enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE)
                : Tile.STATE_UNAVAILABLE);
        tile.setLabel(getString(enabled
                ? R.string.emergency_tile_active
                : R.string.emergency_tile_label));
        tile.updateTile();
    }
}
