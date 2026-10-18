#
# Preinstalled emulators, see emulators/README.md.
#
# Everything here lands in /system/etc/gameconsole and is provisioned into
# /data by the gameconsole-emu service after boot. Product wiring lives in
# emulators.mk.
#

LOCAL_PATH := $(call my-dir)

# First-boot installer and core seeder: gameconsole-setup, a static Go program
# (setup/, built by setup/build.sh into setup/prebuilt), started by init
include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-emu
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := gameconsole-setup
LOCAL_SRC_FILES := setup/prebuilt/gameconsole-setup
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
LOCAL_INIT_RC := seed/gameconsole-emu.rc
LOCAL_REQUIRED_MODULES := gameconsole-preinstall-yml-RetroArch gameconsole-preinstall-yml-PPSSPP gameconsole-rom-folders \
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
LOCAL_MODULE := gameconsole-preinstall-yml-RetroArch
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := RetroArch.yml
LOCAL_SRC_FILES := preinstall/RetroArch.yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-preinstall-yml-PPSSPP
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := PPSSPP.yml
LOCAL_SRC_FILES := preinstall/PPSSPP.yml
include $(BUILD_PREBUILT)

# Emulator APKs. Shipped as plain files (not as system apps) on purpose: the
# APK stays byte-identical to F-Droid's, so its signature survives and F-Droid
# can update it, and nothing gets extracted or dexpreopted into /system.
include $(CLEAR_VARS)
LOCAL_MODULE := RetroArch-preinstall
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
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
LOCAL_REQUIRED_MODULES := gameconsole-retroarch-rgui-fonts
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
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := PPSSPP.apk
LOCAL_SRC_FILES := preinstall/PPSSPP.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/preinstall/NOTICE-PPSSPP.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu gameconsole-ppsspp-ini gameconsole-ppsspp-controls \
    gameconsole-ppsspp-compat
include $(BUILD_PREBUILT)

# PPSSPP settings and button map for a fresh install, seeded by the
# gameconsole-emu service
include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-ppsspp-ini
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/ppsspp
LOCAL_MODULE_STEM := ppsspp.ini
LOCAL_SRC_FILES := seed/ppsspp/ppsspp.ini
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-ppsspp-controls
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/ppsspp
LOCAL_MODULE_STEM := controls.ini
LOCAL_SRC_FILES := seed/ppsspp/controls.ini
include $(BUILD_PREBUILT)

# per-game fixes by disc ID for this slow chip (PPSSPP's user compat.ini)
include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-ppsspp-compat
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/ppsspp
LOCAL_MODULE_STEM := compat.ini
LOCAL_SRC_FILES := seed/ppsspp/compat.ini
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

# RetroArch cheat files from libretro-database (scripts/build-cheats.sh),
# unpacked into RetroArch's cheat folder by the gameconsole-emu service
include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-retroarch-cheats
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/libretro
LOCAL_MODULE_STEM := cheats.tar.gz
LOCAL_SRC_FILES := libretro/cheats.tar.gz
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/libretro/NOTICE-cheats.txt
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

# RetroArch menu (RGUI) fonts for Russian, Chinese, Polish and Turkish, from
# libretro retroarch-assets. Seeded into RetroArch's assets folder by the
# gameconsole-emu service. One module per file, all with the same NOTICE.
GAMECONSOLE_RGUI_FONTS := \
    bitmap6x10_eng.bin bitmap6x10_lse.bin \
    bitmap10x10_eng.bin bitmap10x10_rus.bin \
    bitmap10x10_chn.bin bitmap10x10_jpn.bin bitmap10x10_kor.bin

define gameconsole-rgui-font
include $$(CLEAR_VARS)
LOCAL_MODULE := gameconsole-rgui-font-$(basename $(1))
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/retroarch/rgui-font
LOCAL_MODULE_STEM := $(1)
LOCAL_SRC_FILES := seed/retroarch/rgui-font/$(1)
LOCAL_NOTICE_FILE := $$(LOCAL_PATH)/seed/retroarch/rgui-font/NOTICE
include $$(BUILD_PREBUILT)
endef
$(foreach f,$(GAMECONSOLE_RGUI_FONTS),$(eval $(call gameconsole-rgui-font,$(f))))

include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-retroarch-rgui-fonts
LOCAL_REQUIRED_MODULES := $(foreach f,$(GAMECONSOLE_RGUI_FONTS),gameconsole-rgui-font-$(basename $(f)))
include $(BUILD_PHONY_PACKAGE)
