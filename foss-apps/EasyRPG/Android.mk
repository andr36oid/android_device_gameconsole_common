LOCAL_PATH := $(call my-dir)

# EasyRPG Player: plays RPG Maker 2000 and 2003 games.
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-EasyRPG
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := EasyRPG.apk
LOCAL_SRC_FILES := EasyRPG.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-EasyRPG-list foss-EasyRPG-firstrun
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-EasyRPG-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-easyrpg.list
LOCAL_SRC_FILES := z-foss-easyrpg.list
include $(BUILD_PREBUILT)

# First-start settings, see ../README.md ("First-start settings")
include $(CLEAR_VARS)
LOCAL_MODULE := foss-EasyRPG-firstrun
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun
LOCAL_MODULE_STEM := easyrpg.list
LOCAL_SRC_FILES := firstrun/easyrpg.list
LOCAL_REQUIRED_MODULES := foss-EasyRPG-firstrun-config foss-EasyRPG-firstrun-prefs
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-EasyRPG-firstrun-config
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun/easyrpg
LOCAL_MODULE_STEM := config.ini
LOCAL_SRC_FILES := firstrun/easyrpg/config.ini
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-EasyRPG-firstrun-prefs
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun/easyrpg
LOCAL_MODULE_STEM := prefs.xml
LOCAL_SRC_FILES := firstrun/easyrpg/prefs.xml
include $(BUILD_PREBUILT)
