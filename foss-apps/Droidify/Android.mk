LOCAL_PATH := $(call my-dir)

# Droid-ify: an F-Droid client, to find, install and update free apps. Installed on first
# boot as a normal app by the gameconsole-emu service, see ../README.md. It may install
# apps from the start (firstrun).
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Droidify
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := Droidify.apk
LOCAL_SRC_FILES := Droidify.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Droidify-list foss-Droidify-firstrun
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Droidify-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-droidify.list
LOCAL_SRC_FILES := z-foss-droidify.list
include $(BUILD_PREBUILT)

# First-start settings, see ../README.md ("First-start settings")
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Droidify-firstrun
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun
LOCAL_MODULE_STEM := droidify.list
LOCAL_SRC_FILES := firstrun/droidify.list
include $(BUILD_PREBUILT)
