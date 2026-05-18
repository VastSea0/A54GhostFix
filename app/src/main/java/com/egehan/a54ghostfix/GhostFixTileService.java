package com.egehan.a54ghostfix;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class GhostFixTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        update(Tile.STATE_INACTIVE, getString(R.string.tile_label));
    }

    @Override
    public void onClick() {
        super.onClick();
        update(Tile.STATE_ACTIVE, getString(R.string.fixing));
        GhostFixer.run(this, (success, message) -> update(Tile.STATE_INACTIVE, success ? getString(R.string.tile_label) : getString(R.string.tile_label)));
    }

    private void update(int state, String label) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(state);
        tile.setLabel(label);
        tile.updateTile();
    }
}
