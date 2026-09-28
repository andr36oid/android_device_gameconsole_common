LOCAL_PATH := $(call my-dir)

ifneq ($(TARGET_GAMECONSOLE_VULKAN),true)

# Default: Rockchip's GLES-only r21p0 blob for gralloc 0.
include $(CLEAR_VARS)
LOCAL_MODULE := libGLES_mali.so
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_STRIP_MODULE := false
LOCAL_SRC_FILES_arm := lib/egl/$(LOCAL_MODULE)
LOCAL_SRC_FILES_arm64 := lib64/egl/$(LOCAL_MODULE)
LOCAL_MODULE_PATH_32 := $(TARGET_OUT_VENDOR)/lib/egl/
LOCAL_MODULE_PATH_64 := $(TARGET_OUT_VENDOR)/lib64/egl/
LOCAL_MULTILIB := both
LOCAL_SHARED_LIBRARIES := android.hardware.graphics.common@1.0 libz libnativewindow libc++ liblog libm libc libdl libutilscallstack libcutils android.hardware.graphics.mapper@4.0 libgralloctypes libhidlbase libutils libz


# Create symlinks.
LOCAL_POST_INSTALL_CMD := \
	if [ -f $(LOCAL_MODULE_PATH_64)/libGLES_mali.so ];then cd $(TARGET_OUT_VENDOR)/lib64; ln -sf egl/libGLES_mali.so libGLES_mali.so; cd -; fi; \
	if [ $(BUILD_WITH_GOOGLE_MARKET) != true ];then mkdir -p $(TARGET_OUT_VENDOR)/lib64/hw; cd $(TARGET_OUT_VENDOR)/lib64/hw; ln -sf ../libGLES_mali.so vulkan.$(TARGET_BOARD_PLATFORM).so; cd -; fi; \
	cd $(TARGET_OUT_VENDOR)/lib64; \
	ln -sf egl/libGLES_mali.so libOpenCL.so.1.1; \
	ln -sf libOpenCL.so.1.1 libOpenCL.so.1; \
	ln -sf libOpenCL.so.1 libOpenCL.so; \
	cd -;

include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := vulkan.rk3326.so
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_STRIP_MODULE := false
LOCAL_SRC_FILES_arm := lib/hw/$(LOCAL_MODULE)
LOCAL_SRC_FILES_arm64 := lib64/hw/$(LOCAL_MODULE)
LOCAL_MODULE_PATH_32 := $(TARGET_OUT_VENDOR)/lib/hw
LOCAL_MODULE_PATH_64 := $(TARGET_OUT_VENDOR)/lib64/hw
LOCAL_MULTILIB := both
LOCAL_SHARED_LIBRARIES := android.hardware.graphics.common@1.0 libz libnativewindow libc++ liblog libm libc libdl
# Bypass prebuilt ELF check because the DT_SONAME "libGLES_mali.so" is
# different from LOCAL_MODULE "vulkan.hikey960.so".
LOCAL_CHECK_ELF_FILES := false
include $(BUILD_PREBUILT)

else # TARGET_GAMECONSOLE_VULKAN

# Experimental: Rockchip's Android 12 Mali-G31 (MaliTDVx) g7p1 blobs with
# Vulkan. They need gralloc 4, the g7p1 kbase in the kernel
# (gameconsole_vulkan.config) and libdmabufheap from g7p1/shim.
# Source and checksums in g7p1/README.md.
include $(CLEAR_VARS)
LOCAL_MODULE := libGLES_mali.so
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
LOCAL_SRC_FILES_arm := g7p1/lib/egl/libGLES_mali.so
LOCAL_SRC_FILES_arm64 := g7p1/lib64/egl/libGLES_mali.so
LOCAL_MODULE_PATH_32 := $(TARGET_OUT_VENDOR)/lib/egl/
LOCAL_MODULE_PATH_64 := $(TARGET_OUT_VENDOR)/lib64/egl/
LOCAL_MULTILIB := both
LOCAL_SHARED_LIBRARIES := libutilscallstack libnativewindow libutils liblog libdmabufheap libz libcutils android.hardware.graphics.mapper@4.0 libgralloctypes libhidlbase libhardware libc++ libm libc libdl

# Same symlinks as the default blob, minus the broken vulkan one: the loader
# gets vulkan.$(TARGET_BOARD_PLATFORM).so from the module below.
LOCAL_POST_INSTALL_CMD := \
	if [ -f $(LOCAL_MODULE_PATH_64)/libGLES_mali.so ];then cd $(TARGET_OUT_VENDOR)/lib64; ln -sf egl/libGLES_mali.so libGLES_mali.so; cd -; fi; \
	cd $(TARGET_OUT_VENDOR)/lib64; \
	ln -sf egl/libGLES_mali.so libOpenCL.so.1.1; \
	ln -sf libOpenCL.so.1.1 libOpenCL.so.1; \
	ln -sf libOpenCL.so.1 libOpenCL.so; \
	cd -;

include $(BUILD_PREBUILT)

# Vulkan HAL stub. It links libGLES_mali.so, which exports HMI.
include $(CLEAR_VARS)
LOCAL_MODULE := vulkan.$(TARGET_BOARD_PLATFORM)
LOCAL_MODULE_SUFFIX := .so
LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
LOCAL_SRC_FILES_arm := g7p1/lib/hw/vulkan.mali.so
LOCAL_SRC_FILES_arm64 := g7p1/lib64/hw/vulkan.mali.so
LOCAL_MODULE_PATH_32 := $(TARGET_OUT_VENDOR)/lib/hw
LOCAL_MODULE_PATH_64 := $(TARGET_OUT_VENDOR)/lib64/hw
LOCAL_MULTILIB := both
LOCAL_REQUIRED_MODULES := libGLES_mali.so
include $(BUILD_PREBUILT)

endif # TARGET_GAMECONSOLE_VULKAN
