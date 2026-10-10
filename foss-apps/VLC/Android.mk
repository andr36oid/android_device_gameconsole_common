LOCAL_PATH := $(call my-dir)

# VLC: video and music player for files on the card or the network.
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-VLC
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := VLC.apk
LOCAL_SRC_FILES := VLC.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-VLC-list foss-VLC-firstrun
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-VLC-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-vlc.list
LOCAL_SRC_FILES := z-foss-vlc.list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-VLC-firstrun
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun
LOCAL_MODULE_STEM := vlc.list
LOCAL_SRC_FILES := firstrun/vlc.list
LOCAL_REQUIRED_MODULES := foss-VLC-firstrun-prefs
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-VLC-firstrun-prefs
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun/vlc
LOCAL_MODULE_STEM := prefs.xml
LOCAL_SRC_FILES := firstrun/vlc/prefs.xml
include $(BUILD_PREBUILT)
