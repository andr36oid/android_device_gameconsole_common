LOCAL_PATH := $(call my-dir)

# HyperRogue: roguelike in hyperbolic space. Installed on first boot as a
# normal app by the gameconsole-emu service, see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-HyperRogue
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := HyperRogue.apk
LOCAL_SRC_FILES := HyperRogue.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-HyperRogue-list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-HyperRogue-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-hyperrogue.list
LOCAL_SRC_FILES := z-foss-hyperrogue.list
include $(BUILD_PREBUILT)
