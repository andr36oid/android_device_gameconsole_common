#
# Copyright (C) 2026 The andr36oid Project
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

# Boot animation from LineageOS 23.2 (vendor/lineage@10b4c2b) with the
# andr36oid version text at the bottom. Installed by vendor/lineage through
# TARGET_BOOTANIMATION, set in BoardConfig.mk.
#
# desc.txt: the closing part2 is "p", not LineageOS' "c". A "c" part plays to
# the end after boot has finished, and Android holds the home screen and
# sys.boot_completed until the animation exits: 210 frames at 60fps, 3.5s of
# every boot. With "p" the animation stops once part1 finishes its loop.

LOCAL_PATH := $(call my-dir)

# The text at the bottom carries the build's version when ImageMagick is
# installed (called by path, the build's PATH doesn't include it), otherwise
# the committed overlay.png is used.
ANDR36OID_CONVERT := $(firstword $(wildcard /usr/bin/convert /usr/local/bin/convert))
ifneq ($(ANDR36OID_CONVERT),)
ANDR36OID_OVERLAY := $(TARGET_OUT_INTERMEDIATES)/ANDR36OID_BOOTANIMATION_TEXT/overlay.png
$(ANDR36OID_OVERLAY): PRIVATE_PATH := $(LOCAL_PATH)
$(ANDR36OID_OVERLAY): PRIVATE_TEXT := andr36oid - Android 11 - $(ANDR36OID_VERSION)
$(ANDR36OID_OVERLAY): PRIVATE_CONVERT := $(ANDR36OID_CONVERT)
$(ANDR36OID_OVERLAY): $(LOCAL_PATH)/make-overlay.sh
	@mkdir -p $(dir $@)
	$(hide) CONVERT=$(PRIVATE_CONVERT) $(PRIVATE_PATH)/make-overlay.sh "$(PRIVATE_TEXT)" $@
endif
ANDR36OID_OVERLAY ?= $(LOCAL_PATH)/overlay.png

ANDR36OID_BOOTANIMATION := $(TARGET_OUT_INTERMEDIATES)/ANDR36OID_BOOTANIMATION/bootanimation.zip
$(ANDR36OID_BOOTANIMATION): PRIVATE_PATH := $(LOCAL_PATH)
$(ANDR36OID_BOOTANIMATION): PRIVATE_OVERLAY := $(ANDR36OID_OVERLAY)
$(ANDR36OID_BOOTANIMATION): $(LOCAL_PATH)/gen-bootanimation.sh \
		$(LOCAL_PATH)/bootanimation.tar \
		$(LOCAL_PATH)/desc.txt \
		$(ANDR36OID_OVERLAY) \
		$(SOONG_ZIP)
	@echo "Building bootanimation.zip"
	$(hide) $(PRIVATE_PATH)/gen-bootanimation.sh $@ $(dir $@) \
		$(PRIVATE_PATH)/bootanimation.tar \
		$(PRIVATE_PATH)/desc.txt \
		$(PRIVATE_OVERLAY) \
		prebuilts/tools-lineage/$(HOST_OS)-x86/bin/mogrify \
		$(SOONG_ZIP) \
		$(TARGET_SCREEN_HEIGHT) $(TARGET_SCREEN_WIDTH)
