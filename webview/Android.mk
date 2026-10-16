LOCAL_PATH := $(call my-dir)

# Thorium WebView by Alex313031 (Android build by gz83, BSD-3-Clause): the system
# WebView instead of LineageOS's (external/chromium-webview). A system app, the
# only way Android takes a WebView; it keeps gz83's signature, so their later
# releases install over it as updates. Its libraries are stored uncompressed and
# page-aligned in the APK and load from there (extractNativeLibs="false").
# overlay/.../xml/config_webview_packages.xml names it as the provider.
include $(CLEAR_VARS)
LOCAL_MODULE := ThoriumWebView
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_TAGS := optional
LOCAL_PRODUCT_MODULE := true
LOCAL_MULTILIB := both
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_SRC_FILES := ThoriumWebView.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_OVERRIDES_PACKAGES := webview
LOCAL_REQUIRED_MODULES := \
	libwebviewchromium_loader \
	libwebviewchromium_plat_support
include $(BUILD_PREBUILT)
