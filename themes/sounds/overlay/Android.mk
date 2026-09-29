# Retro UI sounds: a runtime overlay (off by default) that points Android's
# touch and d-pad sounds at the files in ../audio, installed to
# /product/media/audio/ui/andr36oid/ by themes.mk. Android reads the sound list
# once per boot, so switching takes effect after a restart.
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_RRO_THEME := Andr36oidRetroSounds
LOCAL_PRODUCT_MODULE := true
LOCAL_CERTIFICATE := platform
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_PACKAGE_NAME := Andr36oidRetroSounds
LOCAL_PRIVATE_PLATFORM_APIS := true

include $(BUILD_RRO_PACKAGE)
