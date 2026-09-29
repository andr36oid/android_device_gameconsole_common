#
# Preinstalled emulators, see emulators/README.md.
#
# Set before this file gets inherited (device tree or environment):
#   GAMECONSOLE_EMU_NONCOMMERCIAL_CORES := true
# to also ship snes9x2010, Genesis Plus GX and PicoDrive. Their licenses forbid
# commercial use, so leave it off for images that end up on devices for sale.
#

GAMECONSOLE_EMU_NONCOMMERCIAL_CORES ?= false

PRODUCT_PACKAGES += \
    gameconsole-emu \
    RetroArch-preinstall \
    PPSSPP-preinstall \
    libretro-cores-foss

ifeq ($(GAMECONSOLE_EMU_NONCOMMERCIAL_CORES),true)
PRODUCT_PACKAGES += \
    libretro-cores-noncommercial
endif

# How games look in RetroArch (sharp bilinear shader, 3x Game Boy), seeded
# next to a fresh retroarch.cfg by the gameconsole-emu service
GAMECONSOLE_EMU_LOOK := device/gameconsole/common/emulators/seed/retroarch/look
PRODUCT_COPY_FILES += \
    $(call find-copy-subdir-files,*,$(GAMECONSOLE_EMU_LOOK),$(TARGET_COPY_OUT_SYSTEM)/etc/gameconsole/retroarch/look)

# Core options for single games that need them on this chip, written as
# config/<core>/<ROM name>.opt next to a fresh retroarch.cfg (game-compat)
PRODUCT_COPY_FILES += \
    device/gameconsole/common/emulators/seed/retroarch/game-options.txt:$(TARGET_COPY_OUT_SYSTEM)/etc/gameconsole/retroarch/game-options.txt \
    device/gameconsole/common/emulators/seed/game-tips.txt:$(TARGET_COPY_OUT_SYSTEM)/etc/gameconsole/game-tips.txt

# The gameconsole-emu service makes the usual ROM folders on a fresh EASYROMS partition.
# Tells the quick start guide.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.andr36oid.rom_folders=true
