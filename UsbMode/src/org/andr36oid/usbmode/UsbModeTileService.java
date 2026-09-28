package org.andr36oid.usbmode;

import android.content.Intent;
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
        final boolean device = !usbMode.isDevice();
        if (device && (!usbMode.isConfirmedThisBoot() || usbMode.getWifiOnOtgPort() != null)) {
            // The warning about built-in Wi-Fi needs a real screen with a controller-friendly
            // dialog, so the tile opens USB mode and asks there. Once confirmed, the tile
            // switches directly until the next restart, unless Wi-Fi shows up on the port.
            startActivityAndCollapse(new Intent(this, UsbModeActivity.class)
                    .putExtra(UsbModeFragment.EXTRA_ASK_DEVICE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            return;
        }
        if (!usbMode.setDevice(device)) {
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
