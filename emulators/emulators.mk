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
    gameconsole-sqlite3 \
    RetroArch-preinstall \
    PPSSPP-preinstall \
    libretro-cores-foss

ifeq ($(GAMECONSOLE_EMU_NONCOMMERCIAL_CORES),true)
PRODUCT_PACKAGES += \
    libretro-cores-noncommercial
endif

# The gameconsole-emu service makes the usual ROM folders on a fresh EASYROMS partition.
# Tells the quick start guide.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.andr36oid.rom_folders=true

# The gameconsole-emu service gives Daijishou a platform per ROM folder on the first start
# (needs sqlite3 to write its library). Tells the quick start guide.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.andr36oid.daijishou_platforms=true

# Daijishou's systems get a wallpaper and thumbnail on the first start: the COLORFUL
# pack, Daijishou's own default (by viking, from LaunchBox's "Colorful resources",
# edited by iShade, indexed by TapiocaFox), shrunk for the screen by
# scripts/fetch-daijishou-art.py. See daijishou/art/README.
PRODUCT_COPY_FILES += \
    $(foreach f,$(notdir $(wildcard device/gameconsole/common/emulators/daijishou/art/*.jpg)),\
        device/gameconsole/common/emulators/daijishou/art/$(f):$(TARGET_COPY_OUT_SYSTEM)/etc/gameconsole/daijishou/art/$(f)) \
    device/gameconsole/common/emulators/daijishou/art/README:$(TARGET_COPY_OUT_SYSTEM)/etc/gameconsole/daijishou/art/README
