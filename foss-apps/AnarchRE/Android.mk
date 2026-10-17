LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
# AnarchRE: from andr36oid's fork (see app.lock), without touch controls when there is
# no touchscreen. Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md. The fork's build is unsigned; it gets the ROM's key here.
LOCAL_MODULE := foss-AnarchRE
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := AnarchRE.apk
anarchre_apk := $(call local-intermediates-dir)/signed/AnarchRE.apk
$(anarchre_apk): PRIVATE_CERT := $(DEFAULT_SYSTEM_DEV_CERTIFICATE)
$(anarchre_apk): $(LOCAL_PATH)/AnarchRE.apk $(SIGNAPK_JAR) $(SIGNAPK_JNI_LIBRARY_PATH)
	@mkdir -p $(dir $@)
	$(JAVA) -Djava.library.path=$$(dirname $(SIGNAPK_JNI_LIBRARY_PATH)) -jar $(SIGNAPK_JAR) \
		-a 4 --min-sdk-version 21 $(PRIVATE_CERT).x509.pem $(PRIVATE_CERT).pk8 $< $@
LOCAL_PREBUILT_MODULE_FILE := $(anarchre_apk)
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-AnarchRE-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-AnarchRE-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := AnarchRE.yml
LOCAL_SRC_FILES := AnarchRE.yml
include $(BUILD_PREBUILT)
