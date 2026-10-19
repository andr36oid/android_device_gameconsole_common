package org.andr36oid.perfoverlay;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile switching the overlay on and off. */
public class HudTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        Hud.setEnabled(this, !Hud.isEnabled(this));
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final boolean blocked = Hud.isBlocked();
        final boolean on = !blocked && Hud.isEnabled(this);
        tile.setLabel(getString(R.string.tile_label));
        tile.setSubtitle(getString(blocked ? R.string.tile_blocked
                : on ? R.string.tile_on : R.string.tile_off));
        tile.setState(blocked ? Tile.STATE_UNAVAILABLE
                : on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
