package org.andr36oid.wifitransfer;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: turns Wi-Fi transfer on and off and shows the address while on. */
public class TransferTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        if (TransferService.running() != null) {
            TransferService.stop(this);
        } else {
            TransferService.start(this);
        }
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final TransferService service = TransferService.running();
        final String url = service != null ? service.url() : null;
        tile.setLabel(getString(R.string.tile_label));
        if (service == null) {
            tile.setSubtitle(getString(R.string.tile_off));
        } else if (url != null) {
            tile.setSubtitle(url.substring("http://".length()) + "  PIN " + service.pin());
        } else {
            tile.setSubtitle(getString(R.string.tile_on));
        }
        tile.setState(service != null ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
