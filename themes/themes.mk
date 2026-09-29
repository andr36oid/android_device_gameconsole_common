# Optional looks, all off by default (see themes/Android.mk).

# Accent colours matched to the R36S shells (picked like the LineageOS accents)
PRODUCT_PACKAGES += \
	Andr36oidBlackAccent \
	Andr36oidWhiteAccent \
	Andr36oidPurpleAccent \
	Andr36oidBlueAccent \
	Andr36oidYellowAccent \
	Andr36oidRedAccent \
	Andr36oidGreenAccent \
	Andr36oidOrangeAccent

# Wallpapers drawn for 640x480 and 720x720 (replaces LineageOS' Backgrounds)
PRODUCT_PACKAGES += \
	Andr36oidWallpapers

# Retro UI sounds (d-pad moves, A, keyboard, charger), off by default:
# the Andr36oidRetroSounds overlay switches Android's touch sounds over to them
PRODUCT_PACKAGES += \
	Andr36oidRetroSounds
PRODUCT_COPY_FILES += \
	$(foreach f,move select key key_space key_delete key_return key_invalid charging,\
		device/gameconsole/common/themes/sounds/audio/$(f).ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ui/andr36oid/$(f).ogg)

# Retro boot animation, chosen in Settings > Display > Console look
# (persist.andr36oid.bootanimation=retro; stock LineageOS animation otherwise)
PRODUCT_COPY_FILES += \
	device/gameconsole/common/themes/bootanimation/bootanimation-retro.zip:$(TARGET_COPY_OUT_SYSTEM)/media/bootanimation-retro.zip \
	device/gameconsole/common/themes/bootanimation/andr36oid-bootanim.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/andr36oid-bootanim.rc
