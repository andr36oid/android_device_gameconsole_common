#
# Free games for a fresh EASYROMS card, see README.md. The gameconsole-emu
# service copies them into the ROM folders it makes on a new card.
#

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := gameconsole-free-games
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole
LOCAL_MODULE_STEM := free-games.tar.gz
LOCAL_SRC_FILES := free-games.tar.gz
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
include $(BUILD_PREBUILT)
