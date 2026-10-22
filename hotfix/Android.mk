# Hotfixes on BOOT, reapplied before zygote at every start.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-hotfix-unpack
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := prebuilt/andr36oid-hotfix-unpack
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-hotfix
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-hotfix.sh
LOCAL_SRC_FILES := helper/andr36oid-hotfix.sh
LOCAL_REQUIRED_MODULES := andr36oid-hotfix-unpack andr36oid-hotfix-lib
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-hotfix-lib
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-hotfix-lib.sh
LOCAL_SRC_FILES := helper/andr36oid-hotfix-lib.sh
include $(BUILD_PREBUILT)
