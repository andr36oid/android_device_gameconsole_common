# DTB hint: at every start, helper/andr36oid-dtbhint.sh looks for signs that the
# DTB on BOOT is for another board and writes BOOT/dtb-hint.txt with the folder to try.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-dtbhint
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-dtbhint.sh
LOCAL_SRC_FILES := helper/andr36oid-dtbhint.sh
LOCAL_INIT_RC := helper/andr36oid-dtbhint.rc
include $(BUILD_PREBUILT)
