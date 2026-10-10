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
    sqlite3 \
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

# The gameconsole-emu service gives Daijishou a platform per ROM folder on the first start
# (needs sqlite3 to write its library). Tells the quick start guide.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.andr36oid.daijishou_platforms=true

# RetroArch pad profiles for external controllers, seeded into RetroArch's autoconfig
# folder by gameconsole-emu next to the built-in pad's: ours (andr36oid_*.cfg, for the
# kernel drivers and key layouts of this system) and the android set of
# libretro/retroarch-joypad-autoconfig at 3579625f (MIT, autoconfig/upstream/COPYING),
# with spaces and brackets in the file names turned into _. RetroArch only unpacks its
# own copy when it is opened from its icon, never when Daijishou starts a game.
GAMECONSOLE_EMU_AUTOCONFIG := device/gameconsole/common/emulators/seed/retroarch/autoconfig
PRODUCT_COPY_FILES += \
    $(foreach f,$(notdir $(wildcard $(GAMECONSOLE_EMU_AUTOCONFIG)/*.cfg)),\
        $(GAMECONSOLE_EMU_AUTOCONFIG)/$(f):$(TARGET_COPY_OUT_SYSTEM)/etc/gameconsole/retroarch/autoconfig/$(f)) \
    $(foreach f,$(notdir $(wildcard $(GAMECONSOLE_EMU_AUTOCONFIG)/upstream/*.cfg)),\
        $(GAMECONSOLE_EMU_AUTOCONFIG)/upstream/$(f):$(TARGET_COPY_OUT_SYSTEM)/etc/gameconsole/retroarch/autoconfig/$(f)) \
    $(GAMECONSOLE_EMU_AUTOCONFIG)/upstream/COPYING:$(TARGET_COPY_OUT_SYSTEM)/etc/gameconsole/retroarch/autoconfig/COPYING.retroarch-joypad-autoconfig
