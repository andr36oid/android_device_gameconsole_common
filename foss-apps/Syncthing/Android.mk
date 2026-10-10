LOCAL_PATH := $(call my-dir)

# Syncthing: keeps folders in sync with a PC, phone or another console over the
# network, no cloud. Settings > Save backup > Sync saves sets it up for the saves.
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Syncthing
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := Syncthing.apk
LOCAL_SRC_FILES := Syncthing.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Syncthing-list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Syncthing-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-syncthing.list
LOCAL_SRC_FILES := z-foss-syncthing.list
include $(BUILD_PREBUILT)
