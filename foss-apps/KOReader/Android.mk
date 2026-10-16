LOCAL_PATH := $(call my-dir)

# KOReader: e-book and document reader (EPUB, PDF, CBZ, ...), works with the buttons.
# Installed on first boot as a normal app by the gameconsole-emu service, see
# ../README.md. Gets all files access from the start (firstrun).
include $(CLEAR_VARS)
LOCAL_MODULE := foss-KOReader
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := KOReader.apk
LOCAL_SRC_FILES := KOReader.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-KOReader-list foss-KOReader-firstrun
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-KOReader-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-koreader.list
LOCAL_SRC_FILES := z-foss-koreader.list
include $(BUILD_PREBUILT)

# First-start settings, see ../README.md ("First-start settings")
include $(CLEAR_VARS)
LOCAL_MODULE := foss-KOReader-firstrun
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun
LOCAL_MODULE_STEM := koreader.list
LOCAL_SRC_FILES := firstrun/koreader.list
include $(BUILD_PREBUILT)
