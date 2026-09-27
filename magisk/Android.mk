LOCAL_PATH := $(call my-dir)

# The Magisk app, the same APK the device mkimg.sh takes magiskinit and the
# payload from. It is installed as a normal app on first boot (see
# emulators/seed/gameconsole-emu.sh): a system app gets no native libs
# extracted, and Magisk runs its binaries from there. As a normal app it also
# updates itself.
ifneq ($(wildcard $(LOCAL_PATH)/Magisk.apk),)
include $(CLEAR_VARS)
LOCAL_MODULE := MagiskApp
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := Magisk.apk
LOCAL_SRC_FILES := Magisk.apk
LOCAL_REQUIRED_MODULES := gameconsole-emu MagiskApp-list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := MagiskApp-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := magisk.list
LOCAL_SRC_FILES := magisk.list
include $(BUILD_PREBUILT)
endif
