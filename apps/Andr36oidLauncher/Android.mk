# andr36oid launcher: our fork of SproutLauncher by Indiegesindel (GPL-3.0,
# github.com/Indiegesindel/SproutLauncher). The second home screen next to
# Daijishou; the setup wizard asks which one to use (SetupWizard
# HomeChoiceActivity, QuickStart HomeChoice.java).
#
# The APK is built outside the tree from the fork (build-andr36oid.sh in the
# fork's repo, output andr36oid-launcher.apk) and copied here as
# Andr36oidLauncher.apk. *.apk is stored with Git LFS in this repo, like
# Daijishou.apk. It is re-signed with the platform key, like Daijishou.
#
# Nothing is built while the APK isn't here, so this can be merged before the
# fork is ready: device.mk only adds the module and ro.andr36oid.launcher_home
# when the APK exists.
LOCAL_PATH := $(call my-dir)

ifneq ($(wildcard $(LOCAL_PATH)/Andr36oidLauncher.apk),)
include $(CLEAR_VARS)
LOCAL_MODULE := Andr36oidLauncher
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_TAGS := optional
LOCAL_BUILT_MODULE_STEM := package.apk
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_CERTIFICATE := platform
LOCAL_SRC_FILES := $(LOCAL_MODULE).apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/LICENSE
include $(BUILD_PREBUILT)
endif
