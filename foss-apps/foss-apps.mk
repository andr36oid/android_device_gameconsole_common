#
# Extra FOSS apps (Moonlight, VLC, ...), see foss-apps/README.md.
#
# Every app folder brings a product.mk that adds its modules. Set
#   GAMECONSOLE_FOSS_APPS := false
# before this file gets inherited to leave all of them out.
#

GAMECONSOLE_FOSS_APPS ?= true

ifeq ($(GAMECONSOLE_FOSS_APPS),true)
-include $(sort $(wildcard device/gameconsole/common/foss-apps/*/product.mk))
endif
