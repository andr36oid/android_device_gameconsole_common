# RK915 Wi-Fi firmware

`rk915_fw.bin` and `rk915_patch.bin` come from Rockchip's rkwifibt package,
commit 3d0ed39cfdd24343715057e93134cd63b7321827
(https://github.com/Caesar-github/rkwifibt, `firmware/rockchip/WIFI_FIRMWARE/`).
Licence: `LICENSE` (Rockchip, BSD 3-clause style).

| File | md5 |
|---|---|
| rk915_fw.bin | a0e5ed758324e15bc2ed62d0e29f12d8 |
| rk915_patch.bin | 6c1f8ec692e153ca61617d0d2dd5cd4d |

They are copied to /vendor/firmware. The kernel driver
(drivers/net/wireless/rockchip_wlan/rk915) opens them by path there. The
optional `rk915_cal.bin` and `rk915_rf_para.txt` are not shipped.
