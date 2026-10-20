# The eMMC installer (Settings > System > eMMC Installation): a static Go
# program, built by build.sh into prebuilt/ and committed.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-emmcinstall
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := prebuilt/andr36oid-emmcinstall
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
LOCAL_INIT_RC := andr36oid-emmcinstall.rc
include $(BUILD_PREBUILT)
