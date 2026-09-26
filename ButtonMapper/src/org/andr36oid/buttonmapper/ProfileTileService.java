package org.andr36oid.buttonmapper;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Quick Settings tile showing the button profile in use, tapping it switches to the next. */
public class ProfileTileService extends TileService {

    private ButtonMapping mMapping;

    @Override
    public void onStartListening() {
        mMapping = new ButtonMapping(this);
        updateTile();
    }

    @Override
    public void onClick() {
        if (mMapping == null) {
            mMapping = new ButtonMapping(this);
        } else {
            mMapping.reload();
        }
        // Only profiles that keep the console usable, see LayoutValidator.
        final ButtonProfiles.Profile next = mMapping.findNextUsableProfile();
        if (next == null) {
            // Nothing to switch to yet, open the screen where profiles are made.
            startActivityAndCollapse(new Intent(this, ButtonMappingActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        mMapping.activate(next.id);
        updateTile();
        Toast.makeText(this, getString(R.string.profile_button, mMapping.getName(next)),
                Toast.LENGTH_SHORT).show();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final ButtonProfiles.Profile active = mMapping.getActiveProfile();
        tile.setLabel(getString(R.string.tile_label));
        tile.setSubtitle(mMapping.getName(active));
        tile.setState(active.isStandard() ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.updateTile();
    }
}
