LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := joyMouse
LOCAL_SRC_FILES := joyMouse.cpp
LOCAL_CFLAGS    := -std=c++17 -O2
LOCAL_LDLIBS    := -llog

include $(BUILD_EXECUTABLE)