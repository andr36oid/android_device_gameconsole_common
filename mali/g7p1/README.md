# Mali g7p1 blobs with Vulkan (experimental)

Only used when `TARGET_GAMECONSOLE_VULKAN := true` (see `BoardConfig.mk`). The
default build keeps the GLES-only r21p0 blob in `mali/lib*/egl`.

These are Rockchip's Android 12 user-space drivers for Mali-G31 boards
("MaliTDVx", RK3326/PX30), DDK g7p1-01bet0, 32-bit and 64-bit, with Vulkan.
The 64-bit library is built for plain ARMv8.0: it has no LSE atomics (the
Android 11 "MaliG52" g2p0 build had about 10,000 and died with SIGILL on the
Cortex-A35), no FP16 arithmetic and no dot-product instructions.

Source: https://github.com/rockchip-toybrick/rk_platform_vendor_rockchip_common
branch develop-12.1, commit 035777b3ae1a617ef9550911a282d5aeeb303b63, directory
`gpu/MaliTDVx/lib`. The files are unmodified Rockchip/Arm binaries under
Rockchip's vendor license.

| File here | Upstream file | sha256 |
|---|---|---|
| lib/egl/libGLES_mali.so | gpu/MaliTDVx/lib/arm/libGLES_mali.so | 3033d08f117dcaf86fa9eab5242b27fdccca7ed252496d7b847c9fb7dd9df07e |
| lib/hw/vulkan.mali.so | gpu/MaliTDVx/lib/arm/vulkan.mali.so | 5d6fb133ed8ebdffcac580abbb153bb50db3ae35cc13061c7c08e20491695320 |
| lib64/egl/libGLES_mali.so | gpu/MaliTDVx/lib/arm64/libGLES_mali.so | cd576b9a9e94739308d99cc1a5c8d9bdf63c4fcfe4ded97375ee7f565f9f69af |
| lib64/hw/vulkan.mali.so | gpu/MaliTDVx/lib/arm64/vulkan.mali.so | 9b8698e2cb8b2e67e64787e2100122a618686d060205dad97ee2dcffce698900 |

What they need on this Android 11 build:

- The g7p1-01bet0 kbase (UK 11.31) in the kernel. The kernel has it in
  `drivers/gpu/arm/bifrost_g7p1` (from rockchip-linux/kernel develop-4.19),
  selected by `arch/arm64/configs/gameconsole_vulkan.config`, which
  `BoardConfig.mk` passes as `TARGET_KERNEL_ADDITIONAL_CONFIG`.
- Gralloc 4 (IMapper 4.0 with the `arm.graphics` metadata), same as the g2p0
  blobs before. The GPU capability mask they export for gralloc is the same as
  g2p0's apart from one bit gralloc does not use.
- `libdmabufheap.so` and `AHardwareBuffer_getId()`, both Android 12 only.
  `shim/` builds a vendor `libdmabufheap.so` that provides both. The driver
  only uses libdmabufheap for protected memory, which this device does not
  have, so the shim reports no allocator. `AHardwareBuffer_getId()` returns
  the GraphicBuffer id like Android 12 does.

Everything else they link (libutilscallstack, libhardware, mapper@4.0,
libgralloctypes, libhidlbase, ...) is VNDK-SP or LLNDK on Android 11, and the
newest libc symbol version they ask for is LIBC_R.

`vulkan.mali.so` is a small stub. It links `libGLES_mali.so`, which holds the
actual Vulkan driver and its `HMI` symbol. `mali/Android.mk` installs it as
`/vendor/lib*/hw/vulkan.rk3326.so`, the name the Vulkan loader looks for.
