# The root part of Touch controls' "Save layouts to / Load layouts from the SD card": copies
# layout files between /data/system and EASYROMS, started by the app through ctl.start. The
# app itself is in Android.bp.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-touchmap-share
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-touchmap-share.sh
LOCAL_SRC_FILES := helper/andr36oid-touchmap-share.sh
LOCAL_INIT_RC := helper/andr36oid-touchmap-share.rc
include $(BUILD_PREBUILT)
