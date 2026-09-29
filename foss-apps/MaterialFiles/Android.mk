LOCAL_PATH := $(call my-dir)

# Material Files: file manager for the card, USB drives and network shares (SMB, FTP, SFTP, WebDAV).
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-MaterialFiles
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := MaterialFiles.apk
LOCAL_SRC_FILES := MaterialFiles.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-MaterialFiles-list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-MaterialFiles-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-materialfiles.list
LOCAL_SRC_FILES := z-foss-materialfiles.list
include $(BUILD_PREBUILT)
