# USB Bluetooth firmware

Installed to `/vendor/firmware/` (paths below are relative to it), next to
the older Realtek files from `../rtl_bt`. All files come from linux-firmware
commit 9b858e5bb58d7bf1fc4d8818cb9100aed6d46f6a unless noted.

| Files | Driver | Chips | Licence |
|---|---|---|---|
| `rtl_bt/rtl8723a_fw.bin` | btusb + btrtl | RTL8723AU | LICENCE.rtlwifi_firmware.txt |
| `rtl_bt/rtl8723d_*` | btusb + btrtl | RTL8723DU | LICENCE.rtlwifi_firmware.txt |
| `rtl_bt/rtl8761bu_*` | btusb + btrtl | RTL8761BU (TP-Link UB500, ASUS BT500, most BT 5.0 dongles) | LICENCE.rtlwifi_firmware.txt |
| `rtl_bt/rtl8822b_*` | btusb + btrtl | RTL8822BU | LICENCE.rtlwifi_firmware.txt |
| `rtl_bt/rtl8822cu_*` | btusb + btrtl | RTL8822CU | LICENCE.rtlwifi_firmware.txt |
| `rtl_bt/rtl8852au_*` | btusb + btrtl | RTL8852AU / RTL8832AU | LICENCE.rtlwifi_firmware.txt |
| `ath3k-1.fw` | ath3k | Atheros AR3011 | LICENCE.atheros_firmware |
| `ar3k/*.dfu` | ath3k | Atheros AR3012 | LICENCE.atheros_firmware |
| `qca/rampatch_usb_*`, `qca/nvm_usb_*` | btusb | QCA61x4 / QCA9377 (Rome) | LICENSE.QualcommAtheros_ath10k, NOTICE.qca |

`rtl8822cu_fw.bin` is from linux-firmware ddc80c83e2dd and `rtl8852au_fw.bin`
from 3679539d32d1: later versions use the v2 (RTBTCore) patch format that the
4.19 btrtl can't parse. The `*_config.bin` files that linux-firmware installs
as links are stored as copies (8723d -> rtl8821c_config, 8822cu and 8852au ->
rtl8761bu_config).

CSR, Broadcom (without patchram), Intel and Marvell dongles work without
files here. Broadcom `.hcd` patches aren't redistributable.
