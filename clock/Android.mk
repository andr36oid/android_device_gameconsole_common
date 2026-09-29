# Keeps the clock sane after the battery ran completely empty: the RK817 PMIC clock has
# no backup cell. See andr36oid-clock.sh.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-clock
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-clock.sh
LOCAL_SRC_FILES := andr36oid-clock.sh
LOCAL_INIT_RC := andr36oid-clock.rc
include $(BUILD_PREBUILT)
