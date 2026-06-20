package com.egehan.a54ghostfix;

import android.content.Intent;
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
    public void onClick() {
        super.onClick();
        if (!EmergencyControl.isAccessibilityServiceEnabled(this)) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(intent);
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
