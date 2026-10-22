LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-easyroms
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-easyroms.sh
LOCAL_SRC_FILES := andr36oid-easyroms.sh
include $(BUILD_PREBUILT)
