LOCAL_PATH := $(call my-dir)

# Luanti (formerly Minetest): open source voxel game engine and games. Installed on first boot as a normal app by the gameconsole-emu service, see
# ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-Luanti
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := Luanti.apk
LOCAL_SRC_FILES := Luanti.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-Luanti-list foss-Luanti-firstrun
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Luanti-list
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := z-foss-luanti.list
LOCAL_SRC_FILES := z-foss-luanti.list
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-Luanti-firstrun
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun
LOCAL_MODULE_STEM := luanti.list
LOCAL_SRC_FILES := firstrun/luanti.list
LOCAL_REQUIRED_MODULES := foss-Luanti-game-minetest_game foss-Luanti-game-glitch \
	foss-Luanti-game-backroomtest
include $(BUILD_PREBUILT)

# The games themselves, each with its own license notice (fetch-games.sh)
define luanti-game
include $$(CLEAR_VARS)
LOCAL_MODULE := foss-Luanti-game-$(1)
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/firstrun/luanti
LOCAL_MODULE_STEM := $(1).zip
LOCAL_SRC_FILES := firstrun/luanti/$(1).zip
LOCAL_NOTICE_FILE := $$(LOCAL_PATH)/firstrun/luanti/NOTICE-$(1).txt
include $$(BUILD_PREBUILT)
endef
$(foreach game,minetest_game glitch backroomtest,$(eval $(call luanti-game,$(game))))
