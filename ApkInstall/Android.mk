# The root part of the APK installer: lists EASYROMS/apk and runs pm install, started by
# the app through ctl.start. The app itself is in Android.bp.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-apkinstall
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-apkinstall.sh
LOCAL_SRC_FILES := helper/andr36oid-apkinstall.sh
LOCAL_INIT_RC := helper/andr36oid-apkinstall.rc
include $(BUILD_PREBUILT)
