# Blueprint loading assets

The input is the approved PNG directory. This pipeline does not invoke the art generator, redraw, quantize, or resize the source PNGs. Every selected size must contain sheet-01 through sheet-36. `all` discovers sizes in the directory (currently eight); a subset can be selected explicitly.

Before an Android source snapshot/system-image build:

```sh
ANDR36OID_LOADING_PNG_DIR=/root/r36splash/blueprint ./build.sh
```

The normal `/andr36oid/work/build-image.sh` host preflight runs this step. Other snapshot/build drivers should run it before copying the source tree. Requires host Python3, Pillow, NumPy and python-lz4. No Python or PNG decoding is added to Android.

For an Ultra-only asset or hotfix input:

```sh
ANDR36OID_LOADING_SIZES=720x720 ./build.sh --output /path/to/ultra-loading.pack
```

The default output is `prebuilt/andr36oid-loading.pack`; device.mk installs it as the existing `/system/etc/andr36oid-splash.raw`. Its generated JSON records the actual PNG inputs for that build, not a frozen allowlist. Re-running consumes changed PNG bytes automatically. The first unchanged 640x480 BGRA logo frame is an independent fallback and lets an older helper still show its current logo.

The pack contains one exact RGB palette per size and one independent LZ4 block per sheet. Palettes use all original RGB values, typically 16–21k distinct colours across 36 sheets. No 256-colour quantization is used. A future size with more than 65,536 colours automatically uses exact 32-bit RGB+LZ4 instead. Non-opaque art is rejected rather than silently composited onto a new background.

At runtime the helper reads actual fbdev geometry and rotation. It selects the matching logical size, otherwise the nearest aspect/area size and scales/centres it, then falls back to the existing logo if optional metadata/assets cannot be loaded. Only one palette and one compressed sheet are read. A boot-volatile shuffle bag in `/dev/.andr36oid-loading-last` uses all36 sheet IDs before repeating and excludes an immediate cycle-boundary repeat. `A36_LOADING_STATE` can select an isolated state path for host/VM tests. The state requires no disk flush or additional process.

KMS ownership, activation, scanout proof remain the accepted SPLASH implementation. `persist.andr36oid.subsystem.kms_splash` now defaults ON; explicit `0` remains an opt-out. Prestage and the separate fast-return animation experiment remain OFF by default. The art payload does not change display service ordering, backlight/suspend policy or the kernel. Native-size 32-bit panels use a direct frame write, avoiding the old per-pixel rescale loop.
