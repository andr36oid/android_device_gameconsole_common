LOCAL_PATH := $(call my-dir)

# Shattered Pixel Dungeon: roguelike dungeon crawler, plays with a controller. Installed on first boot as a normal app by the gameconsole-emu service, see
# ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-ShatteredPD
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := ShatteredPD.apk
LOCAL_SRC_FILES := ShatteredPD.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-ShatteredPD-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-ShatteredPD-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := ShatteredPD.yml
LOCAL_SRC_FILES := ShatteredPD.yml
include $(BUILD_PREBUILT)
