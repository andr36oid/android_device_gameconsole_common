# The root part of the Problem report page: collects the logs, started by the app
# through ctl.start. The app itself is in Android.bp.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-report
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-report.sh
LOCAL_SRC_FILES := helper/andr36oid-report.sh
LOCAL_INIT_RC := helper/andr36oid-report.rc
include $(BUILD_PREBUILT)
