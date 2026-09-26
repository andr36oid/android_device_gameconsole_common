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

LOCAL_PATH := $(call my-dir)

ANDR36OID_BOOTANIMATION := $(TARGET_OUT_INTERMEDIATES)/ANDR36OID_BOOTANIMATION/bootanimation.zip
$(ANDR36OID_BOOTANIMATION): PRIVATE_PATH := $(LOCAL_PATH)
$(ANDR36OID_BOOTANIMATION): $(LOCAL_PATH)/gen-bootanimation.sh \
		$(LOCAL_PATH)/bootanimation.tar \
		$(LOCAL_PATH)/desc.txt \
		$(LOCAL_PATH)/overlay.png \
		$(SOONG_ZIP)
	@echo "Building bootanimation.zip"
	$(hide) $(PRIVATE_PATH)/gen-bootanimation.sh $@ $(dir $@) \
		$(PRIVATE_PATH)/bootanimation.tar \
		$(PRIVATE_PATH)/desc.txt \
		$(PRIVATE_PATH)/overlay.png \
		prebuilts/tools-lineage/$(HOST_OS)-x86/bin/mogrify \
		$(SOONG_ZIP) \
		$(TARGET_SCREEN_HEIGHT) $(TARGET_SCREEN_WIDTH)
