package org.andr36oid.cpuoverclock;

/** Quick Settings tile for the memory overclock, see GpuRamTile. */
public class RamTileService extends GpuRamTile {

    @Override
    String device() {
        return GpuRamOverclock.DMC;
    }

    @Override
    int label() {
        return R.string.ram_tile_label;
    }
}
