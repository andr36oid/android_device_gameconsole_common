#
# Preinstalled emulators, see emulators/README.md.
#
# Set before this file gets inherited (device tree or environment):
#   GAMECONSOLE_EMU_NONCOMMERCIAL_CORES := true
# to also ship snes9x2010, Genesis Plus GX and PicoDrive. Their licenses forbid
# commercial use, so leave it off for images that end up on devices for sale.
#

# A few free games (FreeGames/games.txt) for the ROM folders on a fresh card.
# Set GAMECONSOLE_FREE_GAMES := false to leave them out (saves ~10 MB on /system).
GAMECONSOLE_FREE_GAMES ?= true
ifeq ($(GAMECONSOLE_FREE_GAMES),true)
PRODUCT_PACKAGES += \
    gameconsole-free-games
endif

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

# The gameconsole-emu service makes the usual ROM folders on a fresh EASYROMS partition.
# Tells the quick start guide.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.andr36oid.rom_folders=true
