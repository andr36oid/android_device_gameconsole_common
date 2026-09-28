package org.andr36oid.gamecomfort;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile switching game mode on and off by hand. */
public class GameModeTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        GameMode.setActive(this, !GameMode.isActive(this), false);
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final boolean on = GameMode.isActive(this);
        tile.setLabel(getString(R.string.game_mode_title));
        tile.setSubtitle(getString(on ? R.string.tile_on
                : GameMode.isAutomatic(this) ? R.string.tile_automatic : R.string.tile_off));
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
