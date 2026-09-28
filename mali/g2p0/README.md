# Mali g2p0 blobs with Vulkan (experimental)

Only used when `TARGET_GAMECONSOLE_VULKAN := true` (see `BoardConfig.mk`). The
default build keeps the GLES-only r21p0 blob in `mali/lib*/egl`.

These are Rockchip's Android 11 Mali Bifrost user-space drivers, DDK
g2p0-01eac0. That DDK matches the bifrost kernel driver in our kernel. They need
gralloc 4 (IMapper 4.0), which the switch turns on.

Source: https://github.com/rockchip-toybrick/rk_platform_vendor_rockchip_common
branch develop-11.0, commit 83ed2f5ddd08c83723795c6d1c5dcc4de51f6a88, directory
`gpu/MaliG52/lib`. The files are unmodified Rockchip/Arm binaries under
Rockchip's vendor license.

| File here | Upstream file | sha256 |
|---|---|---|
| lib/egl/libGLES_mali.so | gpu/MaliG52/lib/arm/libGLES_mali.so | c53467edfff234b05846354b13b1db457f0dc4653e2630535950def12f24d2b4 |
| lib/hw/vulkan.mali.so | gpu/MaliG52/lib/arm/vulkan.mali.so | 93e756e285459c1adf4c810dbd1c021400f839d6aa568157838caf435dd9a018 |
| lib64/egl/libGLES_mali.so | gpu/MaliG52/lib/arm64/libGLES_mali.so | 1c70cc174a8f648d5487c2ce6da89e887deb455f15ad253542583bd4a9332a1e |
| lib64/hw/vulkan.mali.so | gpu/MaliG52/lib/arm64/vulkan.mali.so | 5c1c41cfbe85d1e8691e4efde3509ed3011f79ba93c4567e0c0964586457de15 |

Rockchip only ships a 32-bit g2p0 build for Mali-G31 boards (`gpu/MaliTDVx`),
its 64-bit TDVx blob is still r21p0 without Vulkan. The `MaliG52` build is the
same DDK, supports Mali-G31 too, and comes as a matched 32/64-bit pair, so we
use it for both.

`vulkan.mali.so` is a small stub. It links `libGLES_mali.so`, which holds the
actual Vulkan driver and its `HMI` symbol. `mali/Android.mk` installs it as
`/vendor/lib*/hw/vulkan.rk3326.so`, the name the Vulkan loader looks for.
