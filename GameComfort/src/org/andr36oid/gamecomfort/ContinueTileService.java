package org.andr36oid.gamecomfort;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.andr36oid.gamecomfort.resume.Target;

/** Quick Settings tile "Continue", with the last game's name below. */
public class ContinueTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        startActivityAndCollapse(new Intent(this, ContinueActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final Target target = Continue.cached(this);
        tile.setLabel(getString(R.string.continue_title));
        tile.setSubtitle(target != null ? target.label : getString(R.string.continue_tile_none));
        // Pressing it still looks the game up, the cached one may be out of date
        tile.setState(target != null ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
