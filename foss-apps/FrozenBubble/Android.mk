LOCAL_PATH := $(call my-dir)

# Frozen Bubble: shoot coloured bubbles to clear the board. Installed on first boot as a
# normal app by the gameconsole-emu service, see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-FrozenBubble
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := FrozenBubble.apk
LOCAL_SRC_FILES := FrozenBubble.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-FrozenBubble-list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-FrozenBubble-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-frozenbubble.list
LOCAL_SRC_FILES := z-foss-frozenbubble.list
include $(BUILD_PREBUILT)
