# Copyright (C) 2016 The CyanogenMod Project
# Copyright (C) 2017-2019 The LineageOS Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# This file sets variables that control the way modules are built
# thorughout the system. It should not be used to conditionally
# disable makefiles (the proper mechanism to control what gets
# included in a build is to use PRODUCT_PACKAGES in a product
# definition file).
#
# Default language English (UK). ro.product.locale is the first entry of
# PRODUCT_LOCALES; set before any inherit so it comes ahead of full_base's en_US.
PRODUCT_LOCALES := en_GB

# Inherit from those products. Most specific first.
# Set TARGET_GAMECONSOLE_ZYGOTE64_ONLY := true in lineage_<device>.mk to drop
# the 32-bit zygote. Saves one full zygote (preloaded classes/resources) but
# apps shipping only 32-bit native libs will no longer start.
ifeq ($(TARGET_GAMECONSOLE_ZYGOTE64_ONLY),true)
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
else
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
endif
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)

# Device was launched with M
$(call inherit-product, $(SRC_TARGET_DIR)/product/product_launched_with_m.mk)

# Enable Scoped Storage related
$(call inherit-product, $(SRC_TARGET_DIR)/product/emulated_storage.mk)

# Enable userspace reboot
$(call inherit-product, $(SRC_TARGET_DIR)/product/userspace_reboot.mk)

# Our overlays (overlay-wallpaper: black default wallpaper). Product overlays, as
# those beat device overlays, and set before inheriting Lineage so they come ahead
# of vendor/lineage/overlay/common: as device overlays, every key Lineage also sets
# was silently Lineage's. Check with overlay-tools/overlay-audit.py.
PRODUCT_PACKAGE_OVERLAYS += \
	device/gameconsole/common/overlay-wallpaper \
	device/gameconsole/common/overlay \
	device/gameconsole/common/overlay-lineage

# Inherit some common Lineage stuff.
$(call inherit-product, vendor/lineage/config/common_mini_tablet_wifionly.mk)

# Vendor security patch level
VENDOR_SECURITY_PATCH := $(PLATFORM_SECURITY_PATCH)

PRODUCT_CHARACTERISTICS := tablet

PRODUCT_BUILD_PROP_OVERRIDES += BUILD_UTC_DATE=0

PRODUCT_PROPERTY_OVERRIDES := \
    	ro.carrier=wifi-only


# AAPT
PRODUCT_AAPT_CONFIG := normal
# Every console runs at ro.sf.lcd_density 178 (r36s BoardConfig), where Android picks
# mdpi pictures anyway. Keep only those in platform-built APKs (about 16 MB less).
# Larger "Display size" steps (198+) scale mdpi up instead of using hdpi.
PRODUCT_AAPT_PREF_CONFIG := mdpi
PRODUCT_AAPT_PREBUILT_DPI := normal ldpi mdpi hdpi xhdpi

# Soong namespaces
PRODUCT_SOONG_NAMESPACES := device/gameconsole/common 

# 4 apps in the background
PRODUCT_PROPERTY_OVERRIDES += \
	ro.config.max_starting_bg=4 

PRODUCT_PROPERTY_OVERRIDES += \
    	dalvik.vm.heapstartsize=8m \
    	dalvik.vm.heaptargetutilization=0.75 \
    	dalvik.vm.heapminfree=512k \
    	dalvik.vm.heapmaxfree=2m \
    	dalvik.vm.heapgrowthlimit=128m \
    	dalvik.vm.heapsize=256m

# Reduces GC frequency of foreground apps by 50%
PRODUCT_PROPERTY_OVERRIDES += \
    	dalvik.vm.foreground-heap-growth-multiplier=2.0

# Size images without a fixed partition size (vendor) from their contents
PRODUCT_USE_DYNAMIC_PARTITION_SIZE := true

PRODUCT_COPY_FILES += \
    	$(LOCAL_PATH)/fstab.rk30board:$(TARGET_COPY_OUT_RAMDISK)/fstab.rk30board \
    	$(LOCAL_PATH)/fstab.rk30board:$(TARGET_COPY_OUT_ROOT)/fstab.rk30board \
    	$(LOCAL_PATH)/fstab.rk30board:$(TARGET_COPY_OUT_VENDOR)/fstab.rk30board \
    	$(LOCAL_PATH)/fstab.rk30board:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.rk30board \
    	$(LOCAL_PATH)/fstab.emmc:$(TARGET_COPY_OUT_RAMDISK)/fstab.emmc \
    	$(LOCAL_PATH)/fstab.emmc:$(TARGET_COPY_OUT_ROOT)/fstab.emmc \
    	$(LOCAL_PATH)/fstab.emmc:$(TARGET_COPY_OUT_VENDOR)/fstab.emmc \
    	$(LOCAL_PATH)/fstab.emmc:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.emmc
    	
PRODUCT_COPY_FILES += \
    	$(LOCAL_PATH)/init.rk30board.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.rk30board.rc \
    	$(LOCAL_PATH)/init.rk30board.usb.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/hw/init.rk30board.usb.rc

# RTL BLUETOOTH
PRODUCT_COPY_FILES += \
    	$(LOCAL_PATH)/firmware/rtl_bt/rtl8723b_config:$(TARGET_COPY_OUT_VENDOR)/firmware/rtl_bt/rtl8723b_config.bin \
    	$(LOCAL_PATH)/firmware/rtl_bt/rtl8723b_fw:$(TARGET_COPY_OUT_VENDOR)/firmware/rtl_bt/rtl8723b_fw.bin \
    	$(LOCAL_PATH)/firmware/rtl_bt/rtl8761a_config:$(TARGET_COPY_OUT_VENDOR)/firmware/rtl_bt/rtl8761a_config.bin \
    	$(LOCAL_PATH)/firmware/rtl_bt/rtl8761a_fw:$(TARGET_COPY_OUT_VENDOR)/firmware/rtl_bt/rtl8761a_fw.bin \
	$(LOCAL_PATH)/firmware/rtl_bt/rtl8761b_config:$(TARGET_COPY_OUT_VENDOR)/firmware/rtl_bt/rtl8761b_config.bin \
    	$(LOCAL_PATH)/firmware/rtl_bt/rtl8761b_fw:$(TARGET_COPY_OUT_VENDOR)/firmware/rtl_bt/rtl8761b_fw.bin \
    	$(LOCAL_PATH)/firmware/rtl_bt/rtl8821a_config:$(TARGET_COPY_OUT_VENDOR)/firmware/rtl_bt/rtl8821a_config.bin \
    	$(LOCAL_PATH)/firmware/rtl_bt/rtl8821a_fw:$(TARGET_COPY_OUT_VENDOR)/firmware/rtl_bt/rtl8821a_fw.bin \
    	$(LOCAL_PATH)/firmware/rtl_bt/rtl8821c_config:$(TARGET_COPY_OUT_VENDOR)/firmware/rtl_bt/rtl8821c_config.bin \
     	$(LOCAL_PATH)/firmware/rtl_bt/rtl8821c_fw:$(TARGET_COPY_OUT_VENDOR)/firmware/rtl_bt/rtl8821c_fw.bin \

# Mediatek Wifi
PRODUCT_COPY_FILES += \
    	$(LOCAL_PATH)/firmware/mediatek/mt7601u.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/mt7601u.bin \
    	$(LOCAL_PATH)/firmware/mediatek/mt7610e.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/mediatek/mt7610e.bin \
    	$(LOCAL_PATH)/firmware/mediatek/mt7610u.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/mediatek/mt7610u.bin \
    	$(LOCAL_PATH)/firmware/mediatek/mt7650.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/mt7650.bin \
	$(LOCAL_PATH)/firmware/mediatek/mt7662.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/mt7662.bin \
    	$(LOCAL_PATH)/firmware/mediatek/mt7662_rom_patch.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/mt7662_rom_patch.bin \
    	$(LOCAL_PATH)/firmware/mediatek/mt7662u.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/mediatek/mt7662u.bin \
    	$(LOCAL_PATH)/firmware/mediatek/mt7662u_rom_patch.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/mediatek/mt7662u_rom_patch.bin 

# Ralink Wifi
PRODUCT_COPY_FILES += \
    	$(LOCAL_PATH)/firmware/ralink/rt73.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rt73.bin \
    	$(LOCAL_PATH)/firmware/ralink/rt2561.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rt2561.bin \
    	$(LOCAL_PATH)/firmware/ralink/rt2561s.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rt2561s.bin \
    	$(LOCAL_PATH)/firmware/ralink/rt2661.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rt2661.bin \
	$(LOCAL_PATH)/firmware/ralink/rt2860.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rt2860.bin \
    	$(LOCAL_PATH)/firmware/ralink/rt2870.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rt2870.bin \
    	$(LOCAL_PATH)/firmware/ralink/rt3071.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rt3071.bin \
    	$(LOCAL_PATH)/firmware/ralink/rt3290.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rt3290.bin

	    	    	    	    	    	
# The /vendor copy is the one left when a GSI replaces /system
PRODUCT_COPY_FILES += \
    	$(LOCAL_PATH)/ueventd.rk30board.rc:$(TARGET_COPY_OUT_ROOT)/ueventd.rk30board.rc \
    	$(LOCAL_PATH)/ueventd.rk30board.rc:$(TARGET_COPY_OUT_VENDOR)/ueventd.rc

# Permissions
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/handheld_core_hardware.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/handheld_core_hardware.xml \
    	frameworks/native/data/etc/android.hardware.screen.landscape.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.screen.landscape.xml \
        frameworks/native/data/etc/android.software.cts.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.cts.xml \
	frameworks/native/data/etc/android.hardware.audio.low_latency.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.audio.low_latency.xml \
    	frameworks/native/data/etc/android.hardware.audio.pro.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.audio.pro.xml \
    frameworks/native/data/etc/android.software.autofill.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.autofill.xml \
    frameworks/native/data/etc/android.hardware.opengles.aep.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.opengles.aep.xml \
    frameworks/native/data/etc/android.hardware.location.gps.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.location.gps.xml \
    frameworks/native/data/etc/android.hardware.gamepad.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.gamepad.xml \
    frameworks/native/data/etc/android.software.midi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.midi.xml \
    frameworks/native/data/etc/android.software.sip.voip.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.sip.voip.xml \
        frameworks/native/data/etc/android.hardware.faketouch.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.faketouch.xml \
        frameworks/native/data/etc/android.hardware.usb.host.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.host.xml \
        frameworks/native/data/etc/android.hardware.usb.accessory.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.usb.accessory.xml \
    	frameworks/native/data/etc/android.hardware.ethernet.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.ethernet.xml \
    frameworks/native/data/etc/android.software.freeform_window_management.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.freeform_window_management.xml \

PRODUCT_COPY_FILES += \
    	$(LOCAL_PATH)/bluetooth/bd_addr.txt:$(TARGET_COPY_OUT_VENDOR)/etc/bt/bd_addr.txt \
    	$(LOCAL_PATH)/configs/media/external_camera_config.xml:$(TARGET_COPY_OUT_VENDOR)/etc/external_camera_config.xml \
    	$(LOCAL_PATH)/configs/media/media_profiles.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_profiles_V1_0.xml \
    	$(LOCAL_PATH)/configs/media/media_codecs_performance_rk3326.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_performance.xml \
    	$(LOCAL_PATH)/configs/media/media_codecs_rk3326.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs.xml \

# Copy media codecs config file
PRODUCT_COPY_FILES += \
	$(LOCAL_PATH)/configs/audio/mixer_paths.xml:$(TARGET_COPY_OUT_VENDOR)/etc/mixer_paths.xml \
    	$(LOCAL_PATH)/configs/audio/audio_policy_configuration.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/audio_policy_configuration.xml \
    	$(LOCAL_PATH)/configs/audio/audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_configuration.xml \
    	$(LOCAL_PATH)/configs/audio/audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/audio_policy_configuration.xml \
    	frameworks/av/media/libstagefright/data/media_codecs_google_telephony.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_google_telephony.xml \
	frameworks/av/media/libeffects/data/audio_effects.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_effects.xml \
    	frameworks/av/media/libstagefright/data/media_codecs_google_c2.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/media_codecs_google_c2.xml \
    	frameworks/av/media/libstagefright/data/media_codecs_google_c2_audio.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/media_codecs_google_c2_audio.xml \
    	frameworks/av/media/libstagefright/data/media_codecs_google_c2_video.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/media_codecs_google_c2_video.xml \
	frameworks/av/media/libstagefright/data/media_codecs_google_audio.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_google_audio.xml \
    	frameworks/av/media/libstagefright/data/media_codecs_google_video.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_google_video.xml

# Clock after an empty battery: back to the last good time instead of the build date,
# and the system time written to the RTC at shutdown (clock/andr36oid-clock.sh)
PRODUCT_PACKAGES += \
	andr36oid-clock

# First start on a fresh card: userdata and EASYROMS are added by Android itself
# (firstresize/andr36oid-firstresize.sh), no resizer kernel and no restart
PRODUCT_PACKAGES += \
	andr36oid-firstresize

# Need AppWidget permission to prevent Launcher[2|3] crashing
PRODUCT_COPY_FILES += \
	frameworks/native/data/etc/android.software.app_widgets.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.software.app_widgets.xml

# Public libraries
#PRODUCT_COPY_FILES += \
#    $(LOCAL_PATH)/configs/public.libraries.txt:$(TARGET_COPY_OUT_VENDOR)/etc/public.libraries.txt

# audio policy configuration
USE_XML_AUDIO_POLICY_CONF := 1
PRODUCT_COPY_FILES += \
    	frameworks/av/services/audiopolicy/config/a2dp_in_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/a2dp_in_audio_policy_configuration.xml \
	$(LOCAL_PATH)/configs/audio/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \
	frameworks/av/services/audiopolicy/config/bluetooth_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/bluetooth_audio_policy_configuration.xml \
	frameworks/av/services/audiopolicy/config/r_submix_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/r_submix_audio_policy_configuration.xml \
	frameworks/av/services/audiopolicy/config/usb_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/usb_audio_policy_configuration.xml \
	frameworks/av/services/audiopolicy/config/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml 

# Seccomp
PRODUCT_COPY_FILES += \
	$(LOCAL_PATH)/configs/seccomp/mediaswcodec.policy:$(TARGET_COPY_OUT_VENDOR)/etc/seccomp_policy/mediaswcodec.policy \
    	$(LOCAL_PATH)/configs/seccomp/mediacodec.policy:$(TARGET_COPY_OUT_VENDOR)/etc/seccomp_policy/mediacodec.policy

# Audio HIDL
PRODUCT_PACKAGES += \
    android.hardware.audio.service \
    android.hardware.audio@6.0-impl \
    android.hardware.audio.effect@6.0-impl \
    
# Build default bluetooth a2dp and usb audio HALs
PRODUCT_PACKAGES += \
    	audio.primary.$(TARGET_BOARD_PLATFORM) \
	audio.usb.default \
    	audio.r_submix.default \
    	libaudioroute \
    	libaudio-resampler \
    	tinyplay \
    	tinycap \
    	tinymix \
    	tinypcminfo
    
# Enable AAudio MMAP/NOIRQ data path.
# 1 is AAUDIO_POLICY_NEVER  means only use Legacy path.
# 2 is AAUDIO_POLICY_AUTO   means try MMAP then fallback to Legacy path.
# 3 is AAUDIO_POLICY_ALWAYS means only use MMAP path.
PRODUCT_PROPERTY_OVERRIDES += aaudio.mmap_policy=2
# 1 is AAUDIO_POLICY_NEVER  means only use SHARED mode
# 2 is AAUDIO_POLICY_AUTO   means try EXCLUSIVE then fallback to SHARED mode.
# 3 is AAUDIO_POLICY_ALWAYS means only use EXCLUSIVE mode.
PRODUCT_PROPERTY_OVERRIDES += aaudio.mmap_exclusive_policy=2
    
# Bluetooth HIDL. Bluetooth is a USB dongle: the btusb HAL (bluetooth/btlinux)
# gives up when none is plugged in, and with ro.bluetooth.usb_hotplug the
# framework only turns Bluetooth on while a controller is present and turns
# it back on when one is plugged in.
PRODUCT_PACKAGES += \
    	audio.bluetooth.default \
    	android.hardware.bluetooth.audio@2.0-impl \
    	android.hardware.bluetooth@1.1-service.btusb

PRODUCT_PRODUCT_PROPERTIES += \
	ro.bluetooth.usb_hotplug=true \
	bluetooth.enable_timeout_ms=8000

PRODUCT_COPY_FILES += \
        frameworks/native/data/etc/android.hardware.bluetooth.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth.xml \
        frameworks/native/data/etc/android.hardware.bluetooth_le.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.bluetooth_le.xml 

ifeq ($(TARGET_GAMECONSOLE_VULKAN),true)
# Experimental Vulkan from the g2p0 blob (mali/Android.mk). Mali-G31 on this
# driver is not validated for anything higher, so advertise the baseline:
# Vulkan 1.1, hardware level 0, compute level 0.
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.vulkan.level-0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.level.xml \
    frameworks/native/data/etc/android.hardware.vulkan.version-1_1.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.version.xml \
    frameworks/native/data/etc/android.hardware.vulkan.compute-0.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.vulkan.compute.xml

# The loader tries vulkan.<ro.hardware.vulkan>.so, then vulkan.<ro.board.platform>.so
PRODUCT_PROPERTY_OVERRIDES += \
    ro.hardware.vulkan=$(TARGET_BOARD_PLATFORM)

PRODUCT_PACKAGES += \
    vulkan.$(TARGET_BOARD_PLATFORM)
endif

# Specify OpenGLES version 
PRODUCT_PROPERTY_OVERRIDES += \
    	ro.opengles.version=196610

PRODUCT_PACKAGES += libGLES_mali.so 
PRODUCT_PACKAGES += libGLES_android

PRODUCT_PACKAGES += libwifi-hal-rtl

# Graphics HAL
# define MPP_BUF_TYPE_DRM 1
# define MPP_BUF_TYPE_ION_LEGACY 2
# define MPP_BUF_TYPE_ION_404 3
# define MPP_BUF_TYPE_ION_419 4
# define MPP_BUF_TYPE_DMA_BUF 5
ifeq ($(TARGET_RK_GRALLOC_VERSION),4)
PRODUCT_PROPERTY_OVERRIDES += \
    ro.vendor.mpp_buf_type=1
# Gralloc HAL
PRODUCT_PACKAGES += \
    arm.graphics-ndk_platform \
    android.hardware.graphics.allocator@4.0-impl-$(BOARD_VENDOR_GPU_PLATFORM) \
    android.hardware.graphics.mapper@4.0-impl-$(BOARD_VENDOR_GPU_PLATFORM) \
    android.hardware.graphics.allocator@4.0-service

DEVICE_MANIFEST_FILE += \
    device/gameconsole/common/manifests/android.hardware.graphics.mapper@4.0.xml \
    device/gameconsole/common/manifests/android.hardware.graphics.allocator@4.0.xml
else
PRODUCT_PROPERTY_OVERRIDES += \
    ro.vendor.mpp_buf_type=1
PRODUCT_PACKAGES += \
    gralloc.$(TARGET_BOARD_HARDWARE) \
    android.hardware.graphics.mapper@2.0-impl-2.1 \
    android.hardware.graphics.allocator@2.0-impl \
    android.hardware.graphics.allocator@2.0-service

DEVICE_MANIFEST_FILE += \
    device/gameconsole/common/manifests/android.hardware.graphics.mapper@2.1.xml \
    device/gameconsole/common/manifests/android.hardware.graphics.allocator@2.0.xml
endif

PRODUCT_PACKAGES += \
    rkhelper

# HW Composer
PRODUCT_PACKAGES += \
    hwcomposer.$(TARGET_BOARD_HARDWARE) \
    android.hardware.graphics.composer@2.1-service

# Software Gatekeeper HAL
PRODUCT_PACKAGES += \
    	android.hardware.gatekeeper@1.0-service.software

# Memtrack HAL
PRODUCT_PACKAGES += \
	memtrack.default \
	android.hardware.memtrack@1.0-impl \
	android.hardware.memtrack@1.0-service

# ThermalHAL
PRODUCT_PACKAGES += \
     	android.hardware.thermal@2.0-service.mock \

# DRM
PRODUCT_PACKAGES += \
    	android.hardware.drm@1.0-impl \
    	android.hardware.drm@1.0-service-lazy \
    	android.hardware.drm@1.3-service-lazy.clearkey \
        android.hardware.drm@1.3-service-lazy.widevine

PRODUCT_PACKAGES += \
    	android.hardware.keymaster@3.0-impl \
    	android.hardware.keymaster@3.0-service

# Light HIDL
PRODUCT_PACKAGES += \
    	lights.$(TARGET_BOARD_PLATFORM) \
    	android.hardware.light@2.0-service \
    	android.hardware.light@2.0-impl

# Media HIDL
PRODUCT_PACKAGES += \
    	android.hardware.media.omx@1.0-service \
	android.hardware.media.c2@1.0-service
	
# PowerHAL. The AIDL one is for newer GSIs, which only talk to Power AIDL.
PRODUCT_PACKAGES += \
    	power.default \
    	android.hardware.power@1.0-impl \
    	android.hardware.power@1.0-service \
    	android.hardware.power-service.example

# RenderScript HIDL
PRODUCT_PACKAGES += \
    	android.hardware.renderscript@1.0-impl

# Health
PRODUCT_PACKAGES += \
	android.hardware.health@2.1-impl \
	android.hardware.health@2.1-service

# USB HIDL
PRODUCT_PACKAGES += \
        android.hardware.usb@1.1-service

#PRODUCT_COPY_FILES += \
#    hardware/rockchip/libgraphicpolicy/graphic_profiles.conf:$(TARGET_COPY_OUT_VENDOR)/etc/graphic/graphic_profiles.conf

# Wi-Fi   
PRODUCT_PACKAGES += \
    android.hardware.wifi.hostapd@1.0-service \
    android.hardware.wifi@1.0-service \

# Add wifi-related packages
PRODUCT_PACKAGES += libwpa_client wpa_supplicant hostapd wificond libwifi-hal
PRODUCT_PROPERTY_OVERRIDES += wifi.interface=wlan0 \
                              wifi.supplicant_scan_interval=180

# Wi-Fi is a USB adapter. libwifi_hal loads its driver when Wi-Fi starts (or
# uses one that is built in / already loaded, renaming its interface to
# wlan0), and system_server starts Wi-Fi when an adapter is plugged in while
# Wi-Fi is switched on (UsbWifiHotplugService).
PRODUCT_PRODUCT_PROPERTIES += \
	ro.wifi.usb_hotplug=true
                              
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.wifi.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.xml \
    frameworks/native/data/etc/android.hardware.wifi.direct.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.wifi.direct.xml \
    $(LOCAL_PATH)/configs/wifi/wpa_supplicant.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/wpa_supplicant.conf \
    $(LOCAL_PATH)/configs/wifi/wpa_supplicant.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/wpa_supplicant_overlay.conf \
    $(LOCAL_PATH)/configs/wifi/p2p_supplicant.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/p2p_supplicant_overlay.conf \
    $(LOCAL_PATH)/configs/wifi/p2p_supplicant.conf:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/p2p_supplicant.conf

# USB WiFi: VID:PID whitelist and per-driver module chains, generated from
# the kernel build by scripts/android/gen-usb-wifi-list.sh, plus firmware
USB_WIFI_PATH := device/gameconsole/common
PRODUCT_COPY_FILES += \
    $(USB_WIFI_PATH)/configs/wifi/wifi_id_list.txt:$(TARGET_COPY_OUT_VENDOR)/etc/wifi_id_list.txt \
    $(foreach f,$(notdir $(wildcard $(USB_WIFI_PATH)/configs/wifi/modprobe.d/*)),\
        $(USB_WIFI_PATH)/configs/wifi/modprobe.d/$(f):$(TARGET_COPY_OUT_VENDOR)/etc/modprobe.d/$(f)) \
    $(foreach f,$(shell cd $(USB_WIFI_PATH)/firmware/usbwifi && find . -type f ! -path './LICENSES/*' ! -name README.md | sed 's|^\./||'),\
        $(USB_WIFI_PATH)/firmware/usbwifi/$(f):$(TARGET_COPY_OUT_VENDOR)/firmware/$(f)) \
    $(LOCAL_PATH)/configs/wifi/RT2870STA.dat:$(TARGET_COPY_OUT_SYSTEM)/etc/Wireless/RT2870STA/RT2870STA.dat

# Wi-Fi chip on the SDIO bus (RK915 clones): SDIO id list for libwifi_hal,
# module chain in configs/wifi/modprobe.d/rk915, firmware from Rockchip
PRODUCT_COPY_FILES += \
    $(USB_WIFI_PATH)/configs/wifi/wifi_sdio_id_list.txt:$(TARGET_COPY_OUT_VENDOR)/etc/wifi_sdio_id_list.txt \
    $(USB_WIFI_PATH)/firmware/rk915/rk915_fw.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rk915_fw.bin \
    $(USB_WIFI_PATH)/firmware/rk915/rk915_patch.bin:$(TARGET_COPY_OUT_VENDOR)/firmware/rk915_patch.bin

# USB Bluetooth firmware (btrtl, ath3k, QCA), see firmware/usbbt/README.md
PRODUCT_COPY_FILES += \
    $(foreach f,$(shell cd $(USB_WIFI_PATH)/firmware/usbbt && find . -type f ! -path './LICENSES/*' ! -name README.md | sed 's|^\./||'),\
        $(USB_WIFI_PATH)/firmware/usbbt/$(f):$(TARGET_COPY_OUT_VENDOR)/firmware/$(f))
	    
# Copy device kcm
PRODUCT_COPY_FILES += \
        $(LOCAL_PATH)/Vendor_484b_Product_1100.kl:$(TARGET_COPY_OUT_SYSTEM)/usr/keylayout/Vendor_484b_Product_1100.kl \
        $(LOCAL_PATH)/Vendor_484b_Product_1100.kcm:$(TARGET_COPY_OUT_SYSTEM)/usr/keychars/Vendor_484b_Product_1100.kcm \
        $(LOCAL_PATH)/Vendor_484b_Product_1100.idc:$(TARGET_COPY_OUT_SYSTEM)/usr/idc/Vendor_484b_Product_1100.idc

# Key layouts for external controllers that Android 11 gets wrong or lacks (DualSense,
# Xbox pads xpad only knows by maker, newer Xbox Bluetooth IDs, Nintendo pads), see the
# comment at the top of each file
GAMEPADS_PATH := device/gameconsole/common/gamepads
PRODUCT_COPY_FILES += \
        $(foreach f,$(notdir $(wildcard $(GAMEPADS_PATH)/keylayout/*.kl)),\
            $(GAMEPADS_PATH)/keylayout/$(f):$(TARGET_COPY_OUT_SYSTEM)/usr/keylayout/$(f))

# Build and run only ART
PRODUCT_RUNTIMES := runtime_libart_default

# Power menu stats panel: CPU, temperature, battery and RAM with small graphs of the last
# 10 minutes above the power menu buttons (frameworks/base night/ux7-power-menu-stats).
# persist.sys.power_menu_stats=0 hides it and stops its sampling, =1 brings it back.
PRODUCT_PRODUCT_PROPERTIES += \
	ro.andr36oid.power_menu_stats=true

PRODUCT_PRODUCT_PROPERTIES += \
	ro.statsd.enable=false

# Shown in Settings > About console and in the boot animation
ANDR36OID_VERSION := $(shell device/gameconsole/common/version.sh $(TARGET_BUILD_VARIANT))
PRODUCT_PRODUCT_PROPERTIES += \
	ro.andr36oid.version=$(ANDR36OID_VERSION)

# Apps that ask for portrait stay upright in the middle of the landscape screen with black
# bars, instead of turning the picture sideways (frameworks/base night/o2-portrait).
# adb shell wm fixed-to-user-rotation disabled brings back the old behaviour.
PRODUCT_PRODUCT_PROPERTIES += \
	ro.andr36oid.fixed_rotation=true

# Holding FN shows the list of FN shortcuts (frameworks/base night/ux3-fn-help)
PRODUCT_PRODUCT_PROPERTIES += \
	ro.andr36oid.fn_help=true

# FN + button shortcuts (frameworks/base PhoneWindowManager). Tells the quick start guide
# to list them. persist.sys.fn_hotkeys=0 turns them off.
PRODUCT_PRODUCT_PROPERTIES += \
	ro.andr36oid.fn_hotkeys=true

# FN + B opens the power menu's App switcher page (frameworks/base night/o2-fn-switcher).
# Tells the quick start guide and Buttons and shortcuts to list it.
PRODUCT_PRODUCT_PROPERTIES += \
	ro.andr36oid.fn_switcher=true

# FN + L2 shows or hides the performance overlay (frameworks/base night/ux3-fn-overlay)
PRODUCT_PRODUCT_PROPERTIES += \
	ro.andr36oid.fn_overlay=true

# Apps for beta testers (beta-apps/README.md), on AUTOAPK, offered unticked on the
# setup's app page, in every build but release builds
ifeq ($(filter %-release,$(ANDR36OID_VERSION)),)
PRODUCT_PACKAGES += \
	andr36oid-beta-apps
endif

# The system WebView: Thorium's, instead of LineageOS's (webview/)
PRODUCT_PACKAGES += \
	ThoriumWebView

# Joystick as mouse
PRODUCT_PACKAGES += \
	joyMouse \
	JoyMouseSettings

# Touch controls: per-app layouts that turn buttons and sticks into touches for
# touchscreen-only games (Settings > Touch controls, FN + L1 in a game). The joyMouse daemon
# does the touching through a virtual touchscreen, described by the .idc
PRODUCT_PACKAGES += \
	TouchMapper \
	andr36oid-touchmap-share
PRODUCT_COPY_FILES += \
	$(LOCAL_PATH)/joyMouse/andr36oid_Touch_Controls.idc:$(TARGET_COPY_OUT_SYSTEM)/usr/idc/andr36oid_Touch_Controls.idc

# Performance overlay: FPS, CPU, temperature and battery on top of games
# (Settings > Performance overlay, Quick Settings tile once used)
PRODUCT_PACKAGES += \
	PerfOverlay

# Optional looks: R36S accent colours, wallpapers, retro UI sounds, boot animation
$(call inherit-product, device/gameconsole/common/themes/themes.mk)

# Save backup: daily and after-game backups of the save games to the internal storage,
# EASYROMS and USB drives, and restore (Settings top level, and in the app list)
PRODUCT_PACKAGES += \
	SaveBackup \
	andr36oid-savebackup

# Button mapping and profiles (Settings > Button mapping, Quick Settings tile)
PRODUCT_PACKAGES += \
	ButtonMapper

# Controllers: connected pads with battery and RetroArch player, who is player 1 in
# RetroArch, Bluetooth pairing with the buttons only (Settings > Controllers)
PRODUCT_PACKAGES += \
	Andr36oidControllers \
	andr36oid-gpio-scan

# CPU overclock (Settings > CPU overclock, Quick Settings tile once used)
PRODUCT_PACKAGES += \
	CpuOverclock

# Settings > Battery > Deep sleep: the firmware's sleep steps, for finding sleep freezes
PRODUCT_PACKAGES += \
	DeepSleep

# Settings > System > Kernel Tweaks: low-level kernel switches for hardware trouble
PRODUCT_PACKAGES += \
	KernelTweaks \
	andr36oid-ktweaks \
	andr36oid-hz-preview

# RGB lights (Settings > Display > RGB lights), shown only on consoles with an
# RGB LED (R36 Ultra); off unless chosen
PRODUCT_PACKAGES += \
	RgbLights

# Status light (Settings > Display > Status light): battery, charging and notification
# colours of the LED by the power button, on consoles whose DTB has the status LED
PRODUCT_PACKAGES += \
	StatusLight

# Black frame insertion (Settings > Display, only at 100 Hz or more): after PakoVM's BFI
PRODUCT_PACKAGES += \
	BlackFrameInsertion

# USB mode: switches the OTG port between host and device (adb, MTP)
# (Settings > USB mode, Quick Settings tile once used)
PRODUCT_PACKAGES += \
	UsbMode

# Credits, an easter egg behind the andr36oid version in Settings > About console
PRODUCT_PACKAGES += \
	Andr36oidCredits

# Quick start guide (Settings > Quick start guide), and its tutorial once after the first
# setup. The first-start launcher is only shipped as an APK: gameconsole-emu installs it as a
# user app while the setup wizard runs, and the guide uninstalls it after the tutorial.
PRODUCT_PACKAGES += \
	Andr36oidGuide \
	Andr36oidFirstStart-preinstall

# Controller test (Settings > Controller test)
PRODUCT_PACKAGES += \
	ControllerTest

# Smoothness test (Settings > Display > Panel overclocking)
PRODUCT_PACKAGES += \
	SmoothnessTest

# Low battery warning on top of games (Settings > Battery > Low battery warning),
# game mode: Do Not Disturb while playing (Settings > Game mode, Quick Settings tile),
# and Continue playing: back into the last game (tile, FN + A, power menu); its root
# helper reads RetroArch's and PPSSPP's last game
PRODUCT_PACKAGES += \
	GameComfort \
	andr36oid-gamecomfort

# BIOS check: which BIOS files are missing or wrong, copies the good ones to RetroArch
# (Settings top level, and in the app list)
PRODUCT_PACKAGES += \
	BiosCheck \
	andr36oid-bioscheck

# Remove phone packages that added by default product configuration
PRODUCT_PACKAGES += \
    	remove-BlockedNumberProvider \
    	remove-Telecom \
    	remove-TeleService \
    	remove-MmsService \
	remove-Traceur \
	remove-HTMLViewer \
	remove-UserDictionaryProvider \
	remove-SimAppDialog \
	remove-SoundPicker \
	remove-SecureElement \
	remove-EasterEgg \
	remove-CalendarProvider \
	remove-BookmarkProvider \
	remove-BasicDreams \
	remove-LatinIME \
	remove-DeskClock \
	remove-Contacts \
	remove-Calendar \
    	remove-ExactCalculator \
    	remove-Etar \
    	remove-Email \
		remove-TrebuchetQuickStepGo \
		remove-TrebuchetQuickStep \
	remove-Camera2 \
	remove-Exchange2 \
	remove-LiveWallpapersPicker \
	remove-PhotoTable \
	remove-QuickAccessWallet \
	remove-QuickSearchBox \
	remove-Seedvault \
	remove-Updater \
	remove-WAPPushManager \
	remove-cameraserver \
	remove-android.hardware.cas@1.2-service

# Play time: time played per day, most played apps, break reminders (Settings > Play time)
PRODUCT_PACKAGES += \
	Andr36oidPlayTime

# Second home screen next to Daijishou: the andr36oid launcher (our SproutLauncher fork,
# apps/Andr36oidLauncher). The setup wizard asks which one is home. Only when its APK is in
# the tree; without it the build and the wizard stay Daijishou-only.
# ro.andr36oid.launcher_home is the launcher's home activity (package/class): the one place
# the setup wizard, the quick start guide and gameconsole-emu learn its id from.
ifneq ($(wildcard device/gameconsole/common/apps/Andr36oidLauncher/Andr36oidLauncher.apk),)
PRODUCT_PACKAGES += \
	Andr36oidLauncher
PRODUCT_PRODUCT_PROPERTIES += \
	ro.andr36oid.launcher_home=org.andr36oid.launcher/games.indiegesindel.sproutlauncher.MainActivity
endif

# Run the network stack and tethering inside system_server instead of
# two separate persistent processes (same as Android Go).
PRODUCT_PACKAGES += \
	InProcessNetworkStack \
	com.android.tethering.inprocess

# App prune 1: apps that start at every boot but can't do anything on this console (work profile setup, DSU loader, old launcher fixer)
PRODUCT_PACKAGES += \
	remove-ManagedProvisioning \
	remove-DynamicSystemInstallationService \
	remove-OneTimeInitializer

# App prune 2: print apps (printing is already turned off)
PRODUCT_PACKAGES += \
	remove-PrintSpooler \
	remove-BuiltInPrintService \
	remove-PrintRecommendationService

# App prune 3: backup helpers (backup is already turned off)
PRODUCT_PACKAGES += \
	remove-LocalTransport \
	remove-SharedStorageBackup \
	remove-BackupRestoreConfirmation

# Hide features we do not want services started for (backup).
PRODUCT_COPY_FILES += \
	$(LOCAL_PATH)/configs/permissions/gameconsole_unavailable_features.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/gameconsole_unavailable_features.xml
 	
PRODUCT_PACKAGES += \
	usb_modeswitch \
	Daijishou \
	LeanbackIME \

# On-screen keyboard made for the controller (key grid or stick typing), a second
# keyboard to choose in Settings. The Android TV keyboard above stays the default.
PRODUCT_PACKAGES += \
	GamepadKeyboard

# Preinstalled emulators (RetroArch + libretro cores, PPSSPP), see emulators/README.md.
# Our free images include the SNES and Genesis cores that forbid commercial use.
GAMECONSOLE_EMU_NONCOMMERCIAL_CORES := true
$(call inherit-product, device/gameconsole/common/emulators/emulators.mk)

# Extra FOSS apps (game streaming, video player, stand-alone emulators),
# installed on first boot like the emulators, see foss-apps/README.md.
$(call inherit-product, device/gameconsole/common/foss-apps/foss-apps.mk)

# A few free lo-fi tracks for the music player, on AUTOAPK until the first start
# copies them into Music, see examplemusic/Android.mk.
PRODUCT_PACKAGES += \
	example-music

# Root is Magisk, baked into the ramdisk by the device mkimg.sh (see magisk/README.md).
# The app is preinstalled so it works offline from the first boot.
PRODUCT_PACKAGES += \
    MagiskApp

# Exclude AudioFX
TARGET_EXCLUDES_AUDIOFX := true

# Netutils
PRODUCT_PACKAGES += \
	android.system.net.netd@1.0 \
	libandroid_net \
	netutils-wrapper-1.0

# Settings > Network & internet > Ethernet (USB adapters, USB tethering from a phone)
PRODUCT_PACKAGES += \
	EthernetSettings

# WiFi Display
PRODUCT_PACKAGES += \
	libaacwrapper \
	libnl \

PRODUCT_DEFAULT_PROPERTY_OVERRIDES += \
	ro.surface_flinger.max_frame_buffer_acquired_buffers=3 \
	ro.surface_flinger.start_graphics_allocator_service=1 \
	ro.surface_flinger.supports_background_blur=1 \

PRODUCT_PROPERTY_OVERRIDES += \
	ro.logd.auditd=false \

#PRODUCT_PROPERTY_OVERRIDES += \
#	ro.logd.size=1M \
#	logd.logpersistd=logcatd \
#	logd.logpersistd.size=30 \

#    
#      debug.stagefright.ccodec=0

#PRODUCT_PROPERTY_OVERRIDES += \
#	ro.lmk.use_psi=true	\

# lmkd (PSI mode) with the values it picks for ro.config.low_ram devices:
# kill a cached app once 30% of the page cache is refaulting (default 100%),
# since page cache reloads from the SD card stall the game in front, and
# react to 200ms partial stalls instead of 70ms so short hiccups don't kill.
PRODUCT_PROPERTY_OVERRIDES += \
	ro.lmk.thrashing_limit=30 \
	ro.lmk.thrashing_limit_decay=50 \
	ro.lmk.psi_partial_stall_ms=200


PRODUCT_PROPERTY_OVERRIDES += \
	debug.hwui.renderer=skiagl \
	debug.hwui.use_buffer_age=false \
	debug.hwui.use_partial_updates=false \
	debug.sf.disable_backpressure=1 \
	debug.sf.enable_gl_backpressure=1 \
	vendor.hwc.compose_policy=6 \
	vendor.hwc.device.primary=DSI \
	ro.config.media_vol_steps=30 \
	vendor.gralloc.no_afbc_for_fb_target_layer = 1 \

# Stick calibration (Settings > Calibrate sticks); the joypad driver applies it, init hands
# it over at boot (init.rk30board.rc)
PRODUCT_PACKAGES += \
	StickCalibration

# librkskia
PRODUCT_PACKAGES += \
	librkskia \

	
# Problem report: saves logs to EASYROMS for bug reports (Settings > System)
PRODUCT_PACKAGES += \
	ProblemReport \
	andr36oid-report

# omx
PRODUCT_PACKAGES += \
    	libomxvpu_enc \
    	libomxvpu_dec \
    	libRkOMX_Resourcemanager \
    	libRkOMX_Basecomponent \
    	libRkOMX_OSAL \
    	libOMX_Core \

# SD card check: speed test and fake card check (Settings > Storage)
PRODUCT_PACKAGES += \
	CardCheck \
	andr36oid-cardcheck

# Built-in eMMC (Settings > System): the chip's details and backups of it, on consoles whose
# eMMC Kernel Tweaks can switch on
PRODUCT_PACKAGES += \
	EmmcTool \
	andr36oid-emmc

# eMMC Installation (Settings > System): andr36oid from the SD card onto the built-in eMMC,
# with a backup of the stock one, each step proven before the next (emmcinstall/)
PRODUCT_PACKAGES += \
	andr36oid-emmcinstall

# Free up space (Settings > Storage): old save states, backups, caches, screenshots,
# APKs and duplicate ROMs; low space and EASYROMS error notifications
PRODUCT_PACKAGES += \
	StorageCare \
	andr36oid-storagecare

# APK installer: at start, offers to install the APKs in EASYROMS/apk
PRODUCT_PACKAGES += \
	ApkInstall \
	andr36oid-apkinstall

# Camera omx-plugin vpu akmd libion_rockchip_ext
#    	
PRODUCT_PACKAGES += \
    	libvpu \
    	libstagefrighthw \
    	libstagefright_wfd \
    	libgralloc_priv_omx \
    	libion \
    	akmd \
    	libion_ext \

# libvpu
PRODUCT_PACKAGES += \
    libffmpeg_58 \
    libiconv \
    libjpeghwenc \
    libmpp \
    librk_vpuapi \
    librtopus \

PRODUCT_PROPERTY_OVERRIDES += \
	config.disable_rtt=true \
	config.disable_bluetooth=false \
	config.disable_vrmanager=true \
	config.disable_consumerir=true \
	config.disable_cameraservice=true \

# adb over the network listens on 5555, the usual adb port. Not 5037: adbd running as root
# ("adb root") keeps 5037 for its own server socket, so it could no longer listen for adb there.
PRODUCT_PROPERTY_OVERRIDES += \
	ro.radio.noril=yes \
	ro.lockscreen.disable.default=true \
	persist.adb.tcp.port=5555 \
	persist.sys.zram_enabled=1 \
	persist.sys.disable_rescue=true \
	persist.sys.purgeable_assets=1 \
	ro.kernel.android.checkjni=0 \
	debug.sf.nobootanimation=0
	
# Disable stats logging & monitoring
PRODUCT_PROPERTY_OVERRIDES += \
	debug.atrace.tags.enableflags=0 \
	debugtool.anrhistory=0 \
	ro.com.google.locationfeatures=0 \
	ro.com.google.networklocation=0 \
	profiler.debugmonitor=false \
	profiler.launch=false \
	profiler.hung.dumpdobugreport=false \
	persist.service.pcsync.enable=0 \
	persist.service.lgospd.enable=0 \

# Disable Scudo outside of eng builds to save RAM.
PRODUCT_DISABLE_SCUDO := true
PRODUCT_MINIMIZE_JAVA_DEBUG_INFO := true

# Android Go low-RAM mode. Android 11 has no PRODUCT_VENDOR_PROPERTIES, so
# this used to be dropped silently; it must go in PRODUCT_PROPERTY_OVERRIDES.
# Trade-offs: no multi-window/PiP or bubbles, system_server windows
# draw in software, apps that check isLowRamDevice() use less memory.
PRODUCT_PROPERTY_OVERRIDES += \
     ro.config.low_ram=true

# Speed profile services and wifi-service to reduce RAM and storage.
PRODUCT_SYSTEM_SERVER_COMPILER_FILTER := speed-profile

# Profiles for compiling apps ahead of time: a <module>.prof here makes the build
# compile that app with speed-profile (only the methods in the profile, plus an app
# image) instead of quicken. Daijishou.prof is the baseline profile Daijishou ships
# inside its APK (assets/dexopt/baseline.prof), so its start-up code is compiled
# before the first start instead of being interpreted and JIT-compiled on every boot
# until the idle compile runs. +6.5 MB on /system. After updating Daijishou.apk,
# refresh it with: unzip -p apps/Daijishou/Daijishou.apk assets/dexopt/baseline.prof
# > dexpreopt-profiles/Daijishou.prof (an old one only prints "Profile out of date"
# and the app is compiled as before), then run apps/Daijishou/unbind-apps-hotkeys.py
# dexpreopt-profiles/Daijishou.prof apps/Daijishou/Daijishou.apk (R2 and Y off on the
# Apps page; it updates the profile to match).
PRODUCT_DEX_PREOPT_PROFILE_DIR := device/gameconsole/common/dexpreopt-profiles

# Always preopt extracted APKs to prevent extracting out of the APK for gms
# modules.
PRODUCT_ALWAYS_PREOPT_EXTRACTED_APK := true

# Do not generate libartd.
PRODUCT_ART_TARGET_INCLUDE_DEBUG_BUILD := false

# Enable updating of APEXes
$(call inherit-product, $(SRC_TARGET_DIR)/product/updatable_apex.mk)

# Flatten APEXs for performance
OVERRIDE_TARGET_FLATTEN_APEX := true

# No IORap prefetching and no perfetto daemons. iorapd is meant for large
# memory devices and keeps itself plus traced/traced_probes resident.
PRODUCT_PRODUCT_PROPERTIES += \
    ro.iorapd.enable=false \
    persist.traced.enable=0

# set threshold to filter unused apps
PRODUCT_PROPERTY_OVERRIDES += \
pm.dexopt.downgrade_after_inactive_days=10

# First boot: the apps that can't be compiled at build time (PermissionController,
# MediaProvider, ExtServices, Tethering, OsuLogin in the APEXes, ~6 MB of code) are
# compiled while system_server starts, which holds up the first boot. verify skips
# the quicken pass (Android 12 does the same); the idle compile later turns them
# into speed-profile anyway.
PRODUCT_PROPERTY_OVERRIDES += \
	pm.dexopt.first-boot=verify

# Dedupe VNDK libraries with identical core variants.
TARGET_VNDK_USE_CORE_VARIANT := true

# DTB hint: at every start, BOOT/dtb-hint.txt says whether the DTB fits the console
# and which folder to try if not (DtbHint/)
PRODUCT_PACKAGES += \
	andr36oid-dtbhint

# madvise random in ART to reduce page cache thrashing.
PRODUCT_PROPERTY_OVERRIDES += \
	dalvik.vm.madvise-random=true

# Give slow-starting apps more time before the ANR dialog pops up
# (needs patches/frameworks/base/0001-am-wm-Make-ANR-timeouts-scalable...).
# 3 = 15s input, 30s foreground broadcast, 60s service timeouts.
PRODUCT_PROPERTY_OVERRIDES += \
	ro.config.anr_timeout_multiplier=3

PRODUCT_TYPE := go
$(call inherit-product-if-exists, vendor/gapps/gapps.mk)

# Battery details: voltage, current, charge times and a 24 hour graph (Settings > Battery)
PRODUCT_PACKAGES += \
	BatteryDetails

# Display comfort: Extra dim and Screen colors on Settings > Display (Settings and
# frameworks/base night/o2-display). Extra dim and Night Light use the display color
# table (kernel color_lut), Screen colors the display saturation stage (Rockchip composer
# persist.vendor.saturation.main)
PRODUCT_PRODUCT_PROPERTIES += \
	ro.andr36oid.extra_dim=true \
	ro.andr36oid.screen_colors=true

# Every build but a release keeps logcat on the card in /data/misc/logd (16 files of
# 2 MB, the oldest dropped), so a problem can be read from the card afterwards; adb is
# often not available on a test console. ANDR36OID_BUILD_KIND comes from build-image.sh
# (night, beta, release); a build without it counts as a release, so logcat stays off.
ifneq ($(filter-out release,$(ANDR36OID_BUILD_KIND)),)
PRODUCT_PRODUCT_PROPERTIES += \
    logd.logpersistd=logcatd \
    logd.logpersistd.size=16
# logcatd and its init script: Android only adds them through the debug package list,
# which this build doesn't use, so without this the setting above starts nothing
PRODUCT_PACKAGES += \
    logpersist.start
# The kernel log goes into logcat's kernel buffer too, so it is saved with it: a hang,
# a watchdog restart or a power-off keeps the kernel side on the card as well, not
# only in RAM (pstore keeps it only through a reset)
PRODUCT_PROPERTY_OVERRIDES += \
    ro.logd.kernel=true
else
PRODUCT_PROPERTY_OVERRIDES += \
    ro.logd.kernel=false
endif
