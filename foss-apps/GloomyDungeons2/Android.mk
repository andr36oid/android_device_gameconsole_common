LOCAL_PATH := $(call my-dir)

# Gloomy Dungeons II: old-school 3D shooter. Installed on first boot as a normal app by the gameconsole-emu service, see
# ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-GloomyDungeons2
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := GloomyDungeons2.apk
LOCAL_SRC_FILES := GloomyDungeons2.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-GloomyDungeons2-list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-GloomyDungeons2-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-gloomydungeons2.list
LOCAL_SRC_FILES := z-foss-gloomydungeons2.list
include $(BUILD_PREBUILT)
