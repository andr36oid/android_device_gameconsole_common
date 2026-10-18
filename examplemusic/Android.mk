# Example music: a few free lo-fi tracks (CC0 and CC BY 4.0, see CREDITS.txt) in
# examplemusic/ on AUTOAPK. The first start copies them into the Music folder on
# the internal storage (gameconsole-emu.sh, seed_example_music) before AUTOAPK
# goes to userdata, so they don't take space twice.
LOCAL_PATH := $(call my-dir)

EXAMPLE_MUSIC_FILES := \
    holiznacc0-bubbles.mp3 \
    holiznacc0-lucid.mp3 \
    holiznacc0-one-night-in-france.mp3 \
    holiznacc0-still-life.mp3 \
    holiznacc0-the-last-long-day.mp3 \
    holiznacc0-tokyo-sunset.mp3 \
    ketsa-glorious-winter.mp3 \
    ketsa-night-soul.mp3 \
    ketsa-sunday.mp3 \
    loyalty-freak-music-coexistenz.mp3 \
    loyalty-freak-music-traveling-in-your-mind.mp3 \
    CREDITS.txt

define example-music-file
include $$(CLEAR_VARS)
LOCAL_MODULE := example-music-$(basename $(1))
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $$(PRODUCT_OUT)/autoapk/examplemusic
LOCAL_MODULE_STEM := $(1)
LOCAL_SRC_FILES := $(1)
include $$(BUILD_PREBUILT)
endef
$(foreach f,$(EXAMPLE_MUSIC_FILES),$(eval $(call example-music-file,$(f))))

include $(CLEAR_VARS)
LOCAL_MODULE := example-music
LOCAL_MODULE_TAGS := optional
LOCAL_REQUIRED_MODULES := $(foreach f,$(EXAMPLE_MUSIC_FILES),example-music-$(basename $(f)))
include $(BUILD_PHONY_PACKAGE)
