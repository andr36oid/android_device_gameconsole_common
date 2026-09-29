# The root part of Free up space: looks at how EASYROMS is mounted and runs the scan and
# delete engine (from the app's APK) as root, started by the app through ctl.start. The
# app itself is in Android.bp.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-storagecare
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-storagecare.sh
LOCAL_SRC_FILES := helper/andr36oid-storagecare.sh
LOCAL_INIT_RC := helper/andr36oid-storagecare.rc
include $(BUILD_PREBUILT)
