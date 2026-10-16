package org.andr36oid.cpuoverclock;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * Quick Settings tile switching the GPU or memory overclock on and off. On means the last
 * speed picked on the Performance page (the experimental memory speed only if it was picked
 * there, after its warning), else the lowest one.
 */
abstract class GpuRamTile extends TileService {

    abstract String device();

    abstract int label();

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        final GpuRamOverclock oc = new GpuRamOverclock(this);
        if (GpuRamOverclock.getSpeeds(device()).length == 0) {
            return;
        }
        oc.setChosen(device(), oc.getChosen(device()) > 0 ? 0 : oc.getLastOrLowest(device()));
        if (!new PerformanceProfiles(this).apply()) {
            Toast.makeText(this, R.string.gpu_ram_failed, Toast.LENGTH_SHORT).show();
        }
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final GpuRamOverclock oc = new GpuRamOverclock(this);
        final int hz = oc.getChosen(device());
        tile.setLabel(getString(label()));
        if (GpuRamOverclock.getSpeeds(device()).length == 0) {
            tile.setSubtitle(null);
            tile.setState(Tile.STATE_UNAVAILABLE);
        } else if (hz > 0) {
            tile.setSubtitle(getString(R.string.speed_mhz, GpuRamOverclock.realMhz(hz)));
            tile.setState(Tile.STATE_ACTIVE);
        } else {
            tile.setSubtitle(getString(R.string.tile_off));
            tile.setState(Tile.STATE_INACTIVE);
        }
        tile.updateTile();
    }
}
