LOCAL_PATH := $(call my-dir)

# ScummVM: plays classic point-and-click adventure games (Monkey Island, Day of the Tentacle, ...).
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-ScummVM
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := ScummVM.apk
LOCAL_SRC_FILES := ScummVM.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-ScummVM-list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-ScummVM-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-scummvm.list
LOCAL_SRC_FILES := z-foss-scummvm.list
include $(BUILD_PREBUILT)
