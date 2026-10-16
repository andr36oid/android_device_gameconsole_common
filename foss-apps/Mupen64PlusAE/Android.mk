LOCAL_PATH := $(call my-dir)

# Mupen64Plus AE: Nintendo 64 emulator. Daijishou's N64 platform has a player for it (org.mupen64plusae.v3.alpha).
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Mupen64PlusAE
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Mupen64PlusAE.apk
LOCAL_SRC_FILES := Mupen64PlusAE.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Mupen64PlusAE-yml foss-Mupen64PlusAE-firstrun
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Mupen64PlusAE-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Mupen64PlusAE.yml
LOCAL_SRC_FILES := Mupen64PlusAE.yml
include $(BUILD_PREBUILT)

# First-start controller and emulation profiles, see ../README.md ("First-start settings")
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Mupen64PlusAE-firstrun
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk/.andr36oid/firstrun
LOCAL_MODULE_STEM := mupen64plusae.list
LOCAL_SRC_FILES := firstrun/mupen64plusae.list
LOCAL_REQUIRED_MODULES := foss-Mupen64PlusAE-firstrun-profile foss-Mupen64PlusAE-firstrun-emulation foss-Mupen64PlusAE-firstrun-prefs
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Mupen64PlusAE-firstrun-profile
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk/.andr36oid/firstrun/mupen64plusae
LOCAL_MODULE_STEM := controller.cfg
LOCAL_SRC_FILES := firstrun/mupen64plusae/controller.cfg
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Mupen64PlusAE-firstrun-emulation
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk/.andr36oid/firstrun/mupen64plusae
LOCAL_MODULE_STEM := emulation.cfg
LOCAL_SRC_FILES := firstrun/mupen64plusae/emulation.cfg
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Mupen64PlusAE-firstrun-prefs
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk/.andr36oid/firstrun/mupen64plusae
LOCAL_MODULE_STEM := prefs.xml
LOCAL_SRC_FILES := firstrun/mupen64plusae/prefs.xml
include $(BUILD_PREBUILT)
