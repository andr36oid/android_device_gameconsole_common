LOCAL_PATH := $(call my-dir)

# Shattered Pixel Dungeon: turn-based dungeon crawler (roguelike), full controller support.
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-ShatteredPixelDungeon
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := ShatteredPixelDungeon.apk
LOCAL_SRC_FILES := ShatteredPixelDungeon.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-ShatteredPixelDungeon-list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-ShatteredPixelDungeon-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-shatteredpixeldungeon.list
LOCAL_SRC_FILES := z-foss-shatteredpixeldungeon.list
include $(BUILD_PREBUILT)
