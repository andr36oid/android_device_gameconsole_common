LOCAL_PATH := $(call my-dir)

# Mupen64Plus AE: Nintendo 64 emulator. Daijishou's N64 platform has a player for it (org.mupen64plusae.v3.alpha).
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Mupen64PlusAE
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := Mupen64PlusAE.apk
LOCAL_SRC_FILES := Mupen64PlusAE.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Mupen64PlusAE-list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Mupen64PlusAE-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-mupen64plusae.list
LOCAL_SRC_FILES := z-foss-mupen64plusae.list
include $(BUILD_PREBUILT)
