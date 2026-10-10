# The root part of "Continue playing": reads RetroArch's and PPSSPP's files for the app,
# started by it through ctl.start. The app itself is in Android.bp.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-gamecomfort
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-gamecomfort.sh
LOCAL_SRC_FILES := helper/andr36oid-gamecomfort.sh
LOCAL_INIT_RC := helper/andr36oid-gamecomfort.rc
include $(BUILD_PREBUILT)
