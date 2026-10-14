# The root part of Kernel Tweaks: notes the kernel defaults at boot and writes the tweaks,
# started by init at boot and by the app through ctl.start. The app itself is in Android.bp.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-ktweaks
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-ktweaks.sh
LOCAL_SRC_FILES := helper/andr36oid-ktweaks.sh
LOCAL_INIT_RC := helper/andr36oid-ktweaks.rc
include $(BUILD_PREBUILT)

# The root part of the 5 second refresh rate preview in Settings > Display > Panel
# overclocking: passes the rate to the kernel, which puts the old rate back by itself.
include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-hz-preview
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-hz-preview.sh
LOCAL_SRC_FILES := helper/andr36oid-hz-preview.sh
LOCAL_INIT_RC := helper/andr36oid-hz-preview.rc
include $(BUILD_PREBUILT)
