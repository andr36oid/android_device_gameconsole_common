package org.andr36oid.cpuoverclock;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Quick Settings tile stepping through Battery saver, Balanced and Performance. */
public class ProfileTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        final PerformanceProfiles profiles = new PerformanceProfiles(this);
        if (!profiles.set(profiles.next())) {
            Toast.makeText(this, R.string.profile_failed, Toast.LENGTH_SHORT).show();
        }
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final PerformanceProfiles profiles = new PerformanceProfiles(this);
        final int profile = profiles.get();
        tile.setLabel(getString(R.string.profile_tile_label));
        final int cap = profiles.getActiveCpuCap();
        // Mention the maximum CPU speed while it holds the CPU below the profile.
        tile.setSubtitle(cap > 0
                ? getString(R.string.profile_tile_capped, profiles.getName(profile),
                        profiles.formatMhz(cap))
                : profiles.getName(profile));
        // Balanced is the normal state, the other two stand out.
        tile.setState(profile == PerformanceProfiles.BALANCED
                ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.updateTile();
    }
}
