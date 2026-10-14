# The root part of Find an unknown button (Buttons on this console): reads every GPIO
# level and ADC channel for a few seconds, started by the app through ctl.start. The app
# itself is in Android.bp.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-gpio-scan
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-gpio-scan.sh
LOCAL_SRC_FILES := helper/andr36oid-gpio-scan.sh
LOCAL_INIT_RC := helper/andr36oid-gpio-scan.rc
include $(BUILD_PREBUILT)
