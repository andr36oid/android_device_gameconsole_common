package org.andr36oid.cpuoverclock;

/** Quick Settings tile for the GPU overclock, see GpuRamTile. */
public class GpuTileService extends GpuRamTile {

    @Override
    String device() {
        return GpuRamOverclock.GPU;
    }

    @Override
    int label() {
        return R.string.gpu_tile_label;
    }
}
