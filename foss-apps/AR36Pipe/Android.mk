LOCAL_PATH := $(call my-dir)

# AR36Pipe: NewPipe by Team NewPipe made for the handheld, from andr36oid's fork (see
# app.lock), unticked on the setup's app page. Installed on first boot as a normal app by the
# gameconsole-emu service, see ../README.md. The fork's build is unsigned; it gets the ROM's
# key here, like TetrisLite.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-AR36Pipe
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := AR36Pipe.apk
ar36pipe_apk := $(call local-intermediates-dir)/signed/AR36Pipe.apk
$(ar36pipe_apk): PRIVATE_CERT := $(DEFAULT_SYSTEM_DEV_CERTIFICATE)
$(ar36pipe_apk): $(LOCAL_PATH)/AR36Pipe.apk $(SIGNAPK_JAR) $(SIGNAPK_JNI_LIBRARY_PATH)
	@mkdir -p $(dir $@)
	$(JAVA) -Djava.library.path=$$(dirname $(SIGNAPK_JNI_LIBRARY_PATH)) -jar $(SIGNAPK_JAR) \
		-a 4 --min-sdk-version 23 $(PRIVATE_CERT).x509.pem $(PRIVATE_CERT).pk8 $< $@
LOCAL_PREBUILT_MODULE_FILE := $(ar36pipe_apk)
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-AR36Pipe-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-AR36Pipe-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := AR36Pipe.yml
LOCAL_SRC_FILES := AR36Pipe.yml
include $(BUILD_PREBUILT)
