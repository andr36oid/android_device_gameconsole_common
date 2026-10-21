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

# Waits at start-up for the SD card an eMMC install keeps Android's data on (see
# cmd/andr36oid-datacard); run by init.rk30board.rc on late-fs
include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-datacard
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := prebuilt/andr36oid-datacard
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
include $(BUILD_PREBUILT)
