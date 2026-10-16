LOCAL_PATH := $(call my-dir)

# Thorium: the web browser (a fast Chromium fork), instead of LineageOS's Jelly. Installed on first boot as a
# normal app by the gameconsole-emu service, see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Thorium
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Thorium.apk
LOCAL_SRC_FILES := Thorium.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Thorium-yml
LOCAL_OVERRIDES_MODULES := Jelly
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Thorium-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Thorium.yml
LOCAL_SRC_FILES := Thorium.yml
include $(BUILD_PREBUILT)
