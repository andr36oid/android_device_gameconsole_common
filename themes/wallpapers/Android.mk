# The andr36oid wallpapers, drawn by make-wallpapers.py (original art).
# A wallpaper "partner" package: Styles & wallpapers lists them under
# On-device wallpapers.
# Replaces LineageOS' Backgrounds (1080p photos, 2.7 MB on mini builds), as the
# wallpaper picker only reads one partner package.
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := Andr36oidWallpapers
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_USE_AAPT2 := true
LOCAL_PRODUCT_MODULE := true
LOCAL_SDK_VERSION := current
LOCAL_OVERRIDES_PACKAGES := Backgrounds

include $(BUILD_PACKAGE)
