LOCAL_PATH := $(call my-dir)

# Flycast: Dreamcast, NAOMI and Atomiswave emulator.
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Flycast
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := Flycast.apk
LOCAL_SRC_FILES := Flycast.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Flycast-list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Flycast-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-flycast.list
LOCAL_SRC_FILES := z-foss-flycast.list
include $(BUILD_PREBUILT)
