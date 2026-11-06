package org.andr36oid.usbmode;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Optional launch-only shortcut. The normal wizard owns all USB changes. */
public final class UsbFunctionsTileService extends TileService {
    @Override public void onStartListening() {
        final Tile tile = getQsTile();
        if (tile == null) return;
        tile.setLabel(getString(R.string.usb_functions_title));
        tile.setState(new UsbMode(this).isSupported() ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE);
        tile.updateTile();
    }

    @Override public void onClick() {
        if (!new UsbMode(this).isSupported()) return;
        startActivityAndCollapse(new Intent(this, UsbFunctionsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
}
