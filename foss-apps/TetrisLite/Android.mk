LOCAL_PATH := $(call my-dir)

# TetrisLite: the falling blocks game, from andr36oid's fork (see app.lock). Installed on
# first boot as a normal app by the gameconsole-emu service, see ../README.md. The fork's
# build is unsigned; it gets the ROM's key here, like Music.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-TetrisLite
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := TetrisLite.apk
tetrislite_apk := $(call local-intermediates-dir)/signed/TetrisLite.apk
$(tetrislite_apk): PRIVATE_CERT := $(DEFAULT_SYSTEM_DEV_CERTIFICATE)
$(tetrislite_apk): $(LOCAL_PATH)/TetrisLite.apk $(SIGNAPK_JAR) $(SIGNAPK_JNI_LIBRARY_PATH)
	@mkdir -p $(dir $@)
	$(JAVA) -Djava.library.path=$$(dirname $(SIGNAPK_JNI_LIBRARY_PATH)) -jar $(SIGNAPK_JAR) \
		-a 4 --min-sdk-version 24 $(PRIVATE_CERT).x509.pem $(PRIVATE_CERT).pk8 $< $@
LOCAL_PREBUILT_MODULE_FILE := $(tetrislite_apk)
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-TetrisLite-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-TetrisLite-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := TetrisLite.yml
LOCAL_SRC_FILES := TetrisLite.yml
include $(BUILD_PREBUILT)
