LOCAL_PATH := $(call my-dir)

# Waterfox: web browser based on Firefox (Fenix), unticked on the setup's app page. Installed on first boot as a
# normal app by the gameconsole-emu service, see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Waterfox
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Waterfox.apk
LOCAL_SRC_FILES := Waterfox.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Waterfox-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Waterfox-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Waterfox.yml
LOCAL_SRC_FILES := Waterfox.yml
include $(BUILD_PREBUILT)
