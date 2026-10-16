LOCAL_PATH := $(call my-dir)

# Universal Installer: installs XAPK, APKS and APKM app bundles that Android's own
# installer can't open. Installed on first boot as a normal app by the gameconsole-emu
# service, see ../README.md. Its onboarding comes done (firstrun).
include $(CLEAR_VARS)
LOCAL_MODULE := foss-UniversalInstaller
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := UniversalInstaller.apk
LOCAL_SRC_FILES := UniversalInstaller.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-UniversalInstaller-yml foss-UniversalInstaller-firstrun
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-UniversalInstaller-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := UniversalInstaller.yml
LOCAL_SRC_FILES := UniversalInstaller.yml
include $(BUILD_PREBUILT)

# First-start settings, see ../README.md ("First-start settings")
include $(CLEAR_VARS)
LOCAL_MODULE := foss-UniversalInstaller-firstrun
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk/.andr36oid/firstrun
LOCAL_MODULE_STEM := universalinstaller.list
LOCAL_SRC_FILES := firstrun/universalinstaller.list
LOCAL_REQUIRED_MODULES := foss-UniversalInstaller-firstrun-settings
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-UniversalInstaller-firstrun-settings
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk/.andr36oid/firstrun/universalinstaller
LOCAL_MODULE_STEM := settings.preferences_pb
LOCAL_SRC_FILES := firstrun/universalinstaller/settings.preferences_pb
include $(BUILD_PREBUILT)
