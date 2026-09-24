# USB WiFi firmware

Installed to `/vendor/firmware/` (paths below are relative to it). The
kernel searches `/vendor/firmware` directly (firmware_class.path and the
built-in Android search path), aic8800 reads `/vendor/firmware/<chip>/`.

| Files | Driver | Source | Licence |
|---|---|---|---|
| `rtlwifi/*` | rtl8192cu, rtl8xxxu | linux-firmware | LICENCE.rtlwifi_firmware.txt |
| `ath9k_htc/*`, `htc_*.fw` | ath9k_htc | linux-firmware | LICENCE.open-ath9k-htc-firmware |
| `carl9170-1.fw` | carl9170 | linux-firmware | GPL-2.0 |
| `ar5523.bin` | ar5523 | linux-firmware | LICENCE.atheros_firmware |
| `brcm/brcmfmac*.bin` | brcmfmac (USB) | linux-firmware | LICENCE.broadcom_bcm43xx, LICENCE.cypress |
| `mrvl/usb*.bin` | mwifiex_usb | linux-firmware | LICENCE.NXP |
| `rsi/rs9113_wlan_qspi.rps` | rsi_usb | linux-firmware | LICENSE.rsi |
| `zd1211/*` | zd1211rw | Debian firmware-zd1211 1.5-13 | zd1211-firmware.copyright |
| `atmel_at76c50*.bin` | at76c50x-usb | Debian atmel-firmware 1.3-7 | atmel-firmware.copyright |
| `isl3886usb`, `isl3887usb` | p54usb | daemonizer.de prism54 fw-usb 2.5.8.0 / 2.13.25.0.lm87 | redistributable per upstream |
| `aic8800*/*` | aic_load_fw, aic8800_fdrv | radxa-pkg/aic8800 (src/USB/driver_fw/fw) | vendor firmware, see that repo |

MediaTek (mt7601u/mt76x0/mt76x2u) and Ralink (rt2x00) firmware lives in
`../mediatek` and `../ralink`, Realtek vendor drivers carry theirs inside
the module.
