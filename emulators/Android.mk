#
# Preinstalled emulators, see emulators/README.md.
#
# Everything here lands in /system/etc/gameconsole and is provisioned into
# /data by the gameconsole-emu service after boot. Product wiring lives in
# emulators.mk.
#

LOCAL_PATH := $(call my-dir)

# First-boot installer and core seeder
include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-emu
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole
LOCAL_MODULE_STEM := gameconsole-emu.sh
LOCAL_SRC_FILES := seed/gameconsole-emu.sh
LOCAL_INIT_RC := seed/gameconsole-emu.rc
LOCAL_REQUIRED_MODULES := gameconsole-preinstall-list gameconsole-rom-folders \
    gameconsole-daijishou-seed gameconsole-daijishou-schema \
    gameconsole-daijishou-widget-schema
include $(BUILD_PREBUILT)

# ROM folders the service makes on a fresh EASYROMS partition
include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-rom-folders
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole
LOCAL_MODULE_STEM := rom-folders.txt
LOCAL_SRC_FILES := seed/rom-folders.txt
include $(BUILD_PREBUILT)

# Daijishou platforms and players for the first start (made by
# scripts/gen-daijishou.py from the official platform files, MIT) and the
# library schema of the Daijishou we ship, see README.md "Daijishou platforms"
include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-daijishou-seed
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/daijishou
LOCAL_MODULE_STEM := daijishou-seed.txt
LOCAL_SRC_FILES := daijishou/daijishou-seed.txt
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/daijishou/official/LICENSE
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-daijishou-schema
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/daijishou
LOCAL_MODULE_STEM := schema.sql
LOCAL_SRC_FILES := daijishou/schema.sql
include $(BUILD_PREBUILT)

# Daijishou's widget database schema, for the home page's app shortcuts
include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-daijishou-widget-schema
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/daijishou
LOCAL_MODULE_STEM := widget-schema.sql
LOCAL_SRC_FILES := daijishou/widget-schema.sql
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-preinstall-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := preinstall.list
LOCAL_SRC_FILES := preinstall/preinstall.list
include $(BUILD_PREBUILT)

# Emulator APKs. Shipped as plain files (not as system apps) on purpose: the
# APK stays byte-identical to F-Droid's, so its signature survives and F-Droid
# can update it, and nothing gets extracted or dexpreopted into /system.
include $(CLEAR_VARS)
LOCAL_MODULE := RetroArch-preinstall
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := RetroArch.apk
LOCAL_SRC_FILES := preinstall/RetroArch.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/preinstall/NOTICE-RetroArch.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu gameconsole-retroarch-cfg gameconsole-retroarch-pad \
    gameconsole-retroarch-core-options
include $(BUILD_PREBUILT)

# RetroArch settings for a fresh install and the built-in pad's profile,
# seeded by the gameconsole-emu service
include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-retroarch-cfg
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/retroarch
LOCAL_MODULE_STEM := retroarch.cfg
LOCAL_SRC_FILES := seed/retroarch/retroarch.cfg
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-retroarch-pad
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/retroarch/autoconfig
LOCAL_MODULE_STEM := GO-Super_Gamepad.cfg
LOCAL_SRC_FILES := seed/retroarch/GO-Super_Gamepad.cfg
include $(BUILD_PREBUILT)

# Core option defaults for the Cortex-A35 (N64 at 320x240, threaded
# renderers, ...), read by RetroArch for cores without their own options file
include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-retroarch-core-options
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/retroarch
LOCAL_MODULE_STEM := retroarch-core-options.cfg
LOCAL_SRC_FILES := seed/retroarch/retroarch-core-options.cfg
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := PPSSPP-preinstall
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := PPSSPP.apk
LOCAL_SRC_FILES := preinstall/PPSSPP.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/preinstall/NOTICE-PPSSPP.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu
include $(BUILD_PREBUILT)

# libretro cores, built from pinned sources by scripts/build-cores.sh
include $(CLEAR_VARS)
LOCAL_MODULE := libretro-cores-foss
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/libretro
LOCAL_MODULE_STEM := cores-foss.tar.gz
LOCAL_SRC_FILES := libretro/cores-foss.tar.gz
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/libretro/NOTICE-foss.txt
LOCAL_REQUIRED_MODULES := RetroArch-preinstall
include $(BUILD_PREBUILT)

# Non-commercial licenses (snes9x, Genesis Plus GX, PicoDrive). Only pulled in
# with GAMECONSOLE_EMU_NONCOMMERCIAL_CORES := true, see emulators.mk.
include $(CLEAR_VARS)
LOCAL_MODULE := libretro-cores-noncommercial
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/libretro
LOCAL_MODULE_STEM := cores-noncommercial.tar.gz
LOCAL_SRC_FILES := libretro/cores-noncommercial.tar.gz
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/libretro/NOTICE-noncommercial.txt
LOCAL_REQUIRED_MODULES := RetroArch-preinstall
include $(BUILD_PREBUILT)
