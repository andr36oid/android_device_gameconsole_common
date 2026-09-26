package org.andr36oid.cpuoverclock;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Quick Settings tile switching the overclock on and off at the speed chosen in Settings. */
public class OverclockTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        final Overclock overclock = new Overclock(this);
        if (!overclock.isSupported()) {
            return;
        }
        if (!overclock.setOn(!overclock.isOn())) {
            Toast.makeText(this, R.string.overclock_failed, Toast.LENGTH_SHORT).show();
        }
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final Overclock overclock = new Overclock(this);
        tile.setLabel(getString(R.string.tile_label));
        if (!overclock.isSupported()) {
            tile.setSubtitle(null);
            tile.setState(Tile.STATE_UNAVAILABLE);
        } else if (overclock.isOn()) {
            tile.setSubtitle(overclock.formatSpeed(overclock.getMaxSpeed()));
            tile.setState(Tile.STATE_ACTIVE);
        } else {
            tile.setSubtitle(getString(R.string.tile_off));
            tile.setState(Tile.STATE_INACTIVE);
        }
        tile.updateTile();
    }
}
