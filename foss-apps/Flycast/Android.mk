LOCAL_PATH := $(call my-dir)

# Flycast: Dreamcast, NAOMI and Atomiswave emulator.
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Flycast
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := Flycast.apk
LOCAL_SRC_FILES := Flycast.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Flycast-list foss-Flycast-firstrun
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Flycast-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-flycast.list
LOCAL_SRC_FILES := z-foss-flycast.list
include $(BUILD_PREBUILT)

# First-start settings, button maps and hotkeys, see ../README.md ("First-start settings")
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Flycast-firstrun
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun
LOCAL_MODULE_STEM := flycast.list
LOCAL_SRC_FILES := firstrun/flycast.list
LOCAL_REQUIRED_MODULES := foss-Flycast-firstrun-emucfg foss-Flycast-firstrun-dreamcast foss-Flycast-firstrun-arcade
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Flycast-firstrun-dreamcast
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun/flycast
LOCAL_MODULE_STEM := dreamcast.cfg
LOCAL_SRC_FILES := firstrun/flycast/dreamcast.cfg
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Flycast-firstrun-arcade
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun/flycast
LOCAL_MODULE_STEM := arcade.cfg
LOCAL_SRC_FILES := firstrun/flycast/arcade.cfg
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Flycast-firstrun-emucfg
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun/flycast
LOCAL_MODULE_STEM := emu.cfg
LOCAL_SRC_FILES := firstrun/flycast/emu.cfg
include $(BUILD_PREBUILT)
