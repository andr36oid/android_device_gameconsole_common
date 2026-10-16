LOCAL_PATH := $(call my-dir)

# D-PAD Player: a music player made for the D-pad, for music on the card. Installed on
# first boot as a normal app by the gameconsole-emu service, see ../README.md. It takes
# the place of LineageOS's Eleven, which is made for touch. On the console it is just
# "Music" (rename.py), so it is signed with the build's key instead of its developer's;
# its developer is credited in the Credits app. The Retro accent is preset (firstrun).
include $(CLEAR_VARS)
LOCAL_MODULE := foss-DPadPlayer
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := DPadPlayer.apk
dpadplayer_apk := $(call local-intermediates-dir)/renamed/DPadPlayer.apk
$(dpadplayer_apk): PRIVATE_CERT := $(DEFAULT_SYSTEM_DEV_CERTIFICATE)
$(dpadplayer_apk): $(LOCAL_PATH)/DPadPlayer.apk $(LOCAL_PATH)/rename.py $(SIGNAPK_JAR) \
		$(SIGNAPK_JNI_LIBRARY_PATH)
	@mkdir -p $(dir $@)
	python3 $(word 2,$^) $< $@.unsigned
	$(JAVA) -Djava.library.path=$$(dirname $(SIGNAPK_JNI_LIBRARY_PATH)) -jar $(SIGNAPK_JAR) \
		-a 4 --min-sdk-version 21 $(PRIVATE_CERT).x509.pem $(PRIVATE_CERT).pk8 \
		$@.unsigned $@
	rm -f $@.unsigned
LOCAL_PREBUILT_MODULE_FILE := $(dpadplayer_apk)
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-DPadPlayer-yml foss-DPadPlayer-firstrun
LOCAL_OVERRIDES_MODULES := Eleven
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-DPadPlayer-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := DPadPlayer.yml
LOCAL_SRC_FILES := DPadPlayer.yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-DPadPlayer-firstrun
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk/.andr36oid/firstrun
LOCAL_MODULE_STEM := dpadplayer.list
LOCAL_SRC_FILES := firstrun/dpadplayer.list
LOCAL_REQUIRED_MODULES := foss-DPadPlayer-firstrun-prefs
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-DPadPlayer-firstrun-prefs
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk/.andr36oid/firstrun/dpadplayer
LOCAL_MODULE_STEM := prefs.xml
LOCAL_SRC_FILES := firstrun/dpadplayer/prefs.xml
include $(BUILD_PREBUILT)
