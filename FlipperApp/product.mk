# Keep the module list valid when the optional standalone APK is absent.
ifneq ($(wildcard device/gameconsole/common/FlipperApp/prebuilt/FlipperEmulator-r36s.apk),)
PRODUCT_PACKAGES += FlipperEmulator
endif
