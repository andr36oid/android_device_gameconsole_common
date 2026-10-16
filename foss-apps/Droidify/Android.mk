LOCAL_PATH := $(call my-dir)

# Droid-ify: an F-Droid client, to find, install and update free apps. Installed on first
# boot as a normal app by the gameconsole-emu service, see ../README.md. It may install
# apps from the start (firstrun).
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Droidify
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Droidify.apk
LOCAL_SRC_FILES := Droidify.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Droidify-yml foss-Droidify-firstrun
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Droidify-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Droidify.yml
LOCAL_SRC_FILES := Droidify.yml
include $(BUILD_PREBUILT)

# First-start settings, see ../README.md ("First-start settings")
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Droidify-firstrun
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk/.andr36oid/firstrun
LOCAL_MODULE_STEM := droidify.list
LOCAL_SRC_FILES := firstrun/droidify.list
include $(BUILD_PREBUILT)
