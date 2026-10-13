# First start on a fresh card: andr36oid-firstresize.sh adds userdata and EASYROMS to
# the running system, no separate resizer kernel and no restart.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-firstresize
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-firstresize.sh
LOCAL_SRC_FILES := andr36oid-firstresize.sh
LOCAL_REQUIRED_MODULES := andr36oid-addpart
include $(BUILD_PREBUILT)
