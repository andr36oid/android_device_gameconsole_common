LOCAL_PATH := $(call my-dir)

# MuPDF mini: reader for PDF, EPUB, XPS, FB2 and CBZ comics.
# Installed on first boot as a normal app by the gameconsole-emu service,
# see ../README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := foss-MuPDFmini
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := MuPDFmini.apk
LOCAL_SRC_FILES := MuPDFmini.apk
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/NOTICE.txt
LOCAL_REQUIRED_MODULES := gameconsole-emu foss-MuPDFmini-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := foss-MuPDFmini-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := MuPDFmini.yml
LOCAL_SRC_FILES := MuPDFmini.yml
include $(BUILD_PREBUILT)
