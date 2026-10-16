LOCAL_PATH := $(call my-dir)

# 2048: the sliding number puzzle, offline. Installed on first boot as a normal app by the gameconsole-emu service, see
# ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Game2048
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := Game2048.apk
LOCAL_SRC_FILES := Game2048.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Game2048-list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Game2048-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-game2048.list
LOCAL_SRC_FILES := z-foss-game2048.list
include $(BUILD_PREBUILT)
