LOCAL_PATH := $(call my-dir)

# Syncthing: keeps folders in sync with a PC, phone or another console over the
# network, no cloud. Settings > Save backup > Sync saves sets it up for the saves.
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Syncthing
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Syncthing.apk
LOCAL_SRC_FILES := Syncthing.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Syncthing-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Syncthing-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Syncthing.yml
LOCAL_SRC_FILES := Syncthing.yml
include $(BUILD_PREBUILT)

# "Syncthing" instead of "Syncthing-Fork" as the app's name, in every language the
# app has a name for: overlay/AndroidManifest.xml
include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := Andr36oidSyncthingName
LOCAL_MANIFEST_FILE := overlay/AndroidManifest.xml
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/overlay/res
LOCAL_PRODUCT_MODULE := true
LOCAL_CERTIFICATE := platform
LOCAL_SDK_VERSION := current
include $(BUILD_RRO_PACKAGE)
