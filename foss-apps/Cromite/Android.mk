LOCAL_PATH := $(call my-dir)

# Cromite: the web browser (Chromium with ad blocking), instead of LineageOS's Jelly. Installed on first boot as a
# normal app by the gameconsole-emu service, see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Cromite
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Cromite.apk
LOCAL_SRC_FILES := Cromite.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Cromite-yml
LOCAL_OVERRIDES_MODULES := Jelly
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Cromite-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Cromite.yml
LOCAL_SRC_FILES := Cromite.yml
include $(BUILD_PREBUILT)
