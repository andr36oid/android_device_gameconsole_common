LOCAL_PATH := $(call my-dir)

# BlockDrop: falling blocks with pad bindings (Settings > Controller). Installed on first boot as a
# normal app by the gameconsole-emu service, see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-BlockDrop
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := BlockDrop.apk
LOCAL_SRC_FILES := BlockDrop.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-BlockDrop-list foss-BlockDrop-firstrun
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-BlockDrop-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-blockdrop.list
LOCAL_SRC_FILES := z-foss-blockdrop.list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-BlockDrop-firstrun
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun
LOCAL_MODULE_STEM := blockdrop.list
LOCAL_SRC_FILES := firstrun/blockdrop.list
LOCAL_REQUIRED_MODULES := foss-BlockDrop-firstrun-prefs
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-BlockDrop-firstrun-prefs
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun/blockdrop
LOCAL_MODULE_STEM := prefs.xml
LOCAL_SRC_FILES := firstrun/blockdrop/prefs.xml
include $(BUILD_PREBUILT)
