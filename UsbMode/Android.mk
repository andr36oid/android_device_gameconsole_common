LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-usb-drive
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-usb-drive.sh
LOCAL_SRC_FILES := andr36oid-usb-drive.sh
LOCAL_REQUIRED_MODULES := andr36oid-virtual-drive
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-virtual-drive
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-virtual-drive.sh
LOCAL_SRC_FILES := andr36oid-virtual-drive.sh
include $(BUILD_PREBUILT)
