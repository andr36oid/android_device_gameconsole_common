package org.andr36oid.usbmode;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Quick Settings tile switching the OTG port between accessories and a computer. */
public class UsbModeTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        final UsbMode usbMode = new UsbMode(this);
        if (!usbMode.isSupported()) {
            return;
        }
        if (!usbMode.setDevice(!usbMode.isDevice())) {
            Toast.makeText(this, R.string.usb_mode_failed, Toast.LENGTH_SHORT).show();
        }
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final UsbMode usbMode = new UsbMode(this);
        tile.setLabel(getString(R.string.tile_label));
        if (!usbMode.isSupported()) {
            tile.setSubtitle(null);
            tile.setState(Tile.STATE_UNAVAILABLE);
        } else if (usbMode.isDevice()) {
            tile.setSubtitle(getString(R.string.tile_device));
            tile.setState(Tile.STATE_ACTIVE);
        } else {
            tile.setSubtitle(getString(R.string.tile_host));
            tile.setState(Tile.STATE_INACTIVE);
        }
        tile.updateTile();
    }
}
