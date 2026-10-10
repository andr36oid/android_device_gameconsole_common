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

# Retro UI sounds (d-pad moves, A, keyboard, charger), on by default. With
# persist.andr36oid.ui_sounds=retro the audio service reads audio_assets_retro.xml
# (frameworks/base SoundEffectsHelper) instead of the framework's sound list; the
# charger sound's default is in the SettingsProvider overlay. Settings > Personalization
# switches to Android's (=android, kept over this default).
PRODUCT_PRODUCT_PROPERTIES += \
	persist.andr36oid.ui_sounds=retro

PRODUCT_COPY_FILES += \
	device/gameconsole/common/themes/sounds/audio_assets_retro.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/andr36oid/audio_assets_retro.xml \
	$(foreach f,move select key key_space key_delete key_return key_invalid charging,\
		device/gameconsole/common/themes/sounds/audio/$(f).ogg:$(TARGET_COPY_OUT_PRODUCT)/media/audio/ui/andr36oid/$(f).ogg)

# Retro boot animation, chosen in Settings > Personalization
# (persist.andr36oid.bootanimation=retro; stock LineageOS animation otherwise)
PRODUCT_COPY_FILES += \
	device/gameconsole/common/themes/bootanimation/bootanimation-retro.zip:$(TARGET_COPY_OUT_SYSTEM)/media/bootanimation-retro.zip \
	device/gameconsole/common/themes/bootanimation/andr36oid-bootanim.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/andr36oid-bootanim.rc

# Settings > Personalization: accent colour, UI sounds, boot animation
PRODUCT_PACKAGES += \
	ConsoleLook
