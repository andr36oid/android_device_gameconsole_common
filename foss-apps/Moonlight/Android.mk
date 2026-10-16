LOCAL_PATH := $(call my-dir)

# Moonlight: streams games from a PC (Sunshine or NVIDIA GameStream) to the console.
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Moonlight
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Moonlight.apk
LOCAL_SRC_FILES := Moonlight.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Moonlight-yml foss-Moonlight-firstrun
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Moonlight-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Moonlight.yml
LOCAL_SRC_FILES := Moonlight.yml
include $(BUILD_PREBUILT)

# First-start settings, see ../README.md ("First-start settings")
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Moonlight-firstrun
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk/.andr36oid/firstrun
LOCAL_MODULE_STEM := moonlight.list
LOCAL_SRC_FILES := firstrun/moonlight.list
LOCAL_REQUIRED_MODULES := foss-Moonlight-firstrun-prefs
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Moonlight-firstrun-prefs
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk/.andr36oid/firstrun/moonlight
LOCAL_MODULE_STEM := prefs.xml
LOCAL_SRC_FILES := firstrun/moonlight/prefs.xml
include $(BUILD_PREBUILT)
