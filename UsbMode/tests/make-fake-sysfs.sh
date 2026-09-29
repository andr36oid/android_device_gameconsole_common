#!/bin/sh
# Makes a fake /sys/bus/usb/devices with the devices UsbCheckTest expects.
#   make-fake-sysfs.sh <empty folder>
set -e
R=$1
[ -n "$R" ] || { echo "usage: $0 <folder>" >&2; exit 1; }
mkdir -p "$R/devices" "$R/drivers"
D=$R/devices

# dev <name> <vid> <pid> <class> <config> <manufacturer> <product>
dev() {
	mkdir -p "$D/$1"
	echo "$2" >"$D/$1/idVendor"
	echo "$3" >"$D/$1/idProduct"
	echo "$4" >"$D/$1/bDeviceClass"
	echo "$5" >"$D/$1/bConfigurationValue"
	[ -n "$6" ] && echo "$6" >"$D/$1/manufacturer"
	[ -n "$7" ] && echo "$7" >"$D/$1/product"
	echo 480 >"$D/$1/speed"
	echo " 2.00" >"$D/$1/version"
}

# ifc <device> <number> <class> <subclass> <protocol> <driver or -> [bcdDevice]
ifc() {
	n=$1:1.$2
	mkdir -p "$D/$n"
	echo "0$2" >"$D/$n/bInterfaceNumber"
	echo "$3" >"$D/$n/bInterfaceClass"
	echo "$4" >"$D/$n/bInterfaceSubClass"
	echo "$5" >"$D/$n/bInterfaceProtocol"
	v=$(tr 'a-f' 'A-F' <"$D/$1/idVendor")
	p=$(tr 'a-f' 'A-F' <"$D/$1/idProduct")
	dc=$(cat "$D/$1/bDeviceClass")
	printf 'usb:v%sp%sd%sdc%sdsc00dp00ic%sisc%sip%sin%02X\n' "$v" "$p" "${7:-0200}" "$dc" \
		"$(echo "$3" | tr 'a-f' 'A-F')" "$(echo "$4" | tr 'a-f' 'A-F')" \
		"$(echo "$5" | tr 'a-f' 'A-F')" "$2" >"$D/$n/modalias"
	if [ "$6" != - ]; then
		mkdir -p "$R/drivers/$6"
		ln -s "../../drivers/$6" "$D/$n/driver"
	fi
}

# root hub: skipped
dev usb1 1d6b 0002 09 1 "Linux" "DWC OTG Controller"
ifc usb1 0 09 00 00 hub

dev 1-1 1a40 0101 09 1 "" "USB 2.0 Hub"
ifc 1-1 0 09 00 00 hub

# Wi-Fi, driver exists (8821cu), Wi-Fi off
dev 1-1.1 0bda c811 00 1 "Realtek" "802.11ac NIC"
ifc 1-1.1 0 ff ff ff -

# Wi-Fi in driver-CD mode, known ID
dev 1-1.2 0bda 1a2b 00 1 "Realtek" "DISK"
ifc 1-1.2 0 08 06 50 usb-storage

# Wi-Fi 6 without a driver (RTL8852AU)
dev 1-1.3 0bda 885a 00 1 "Realtek" "802.11ax WLAN Adapter"
ifc 1-1.3 0 ff ff ff -

# Xbox 360 pad: xpad takes interface 0 only
dev 1-1.4 045e 028e ff 1 "Microsoft" "Controller" 
ifc 1-1.4 0 ff 5d 01 xpad 0114
ifc 1-1.4 1 ff 5d 03 - 0114
ifc 1-1.4 2 ff 5d 02 - 0114
ifc 1-1.4 3 ff fd 13 - 0114

# USB Ethernet, working
dev 1-1.5 0bda 8153 00 1 "Realtek" "USB 10/100/1000 LAN"
ifc 1-1.5 0 ff ff 00 r8152 3000

# no name, vendor class, nothing matches
dev 1-1.6 1234 5678 00 1 "" ""
ifc 1-1.6 0 ff 00 00 -

# never configured (not enough power)
dev 1-1.7 0bda 8812 00 "" "Realtek" "802.11n NIC"

# keyboard
dev 2-1 046d c31c 00 1 "Logitech" "USB Keyboard"
ifc 2-1 0 03 01 01 usbhid 6400
ifc 2-1 1 03 00 00 usbhid 6400

# driver-CD from a Wi-Fi maker that the kernel doesn't know
dev 3-1 0bda a192 00 1 "Realtek" "DISK"
ifc 3-1 0 08 06 50 usb-storage

# Realtek card reader: a real drive, works
dev 4-1 0bda 0158 00 1 "Generic" "USB2.0-CRW"
ifc 4-1 0 08 06 50 usb-storage 5877

# USB sound card with volume buttons
dev 5-1 0d8c 0014 00 1 "C-Media Electronics Inc." "USB Audio Device"
ifc 5-1 0 01 01 00 snd-usb-audio 0100
ifc 5-1 1 01 02 00 snd-usb-audio 0100
ifc 5-1 2 01 02 00 snd-usb-audio 0100
ifc 5-1 3 03 00 00 usbhid 0100

# Wi-Fi + Bluetooth combo with a Bluetooth driver but no Wi-Fi driver (RTL8723DU)
dev 6-1 0bda d723 ef 1 "Realtek" "802.11n WLAN Adapter"
ifc 6-1 0 e0 01 01 btusb
ifc 6-1 1 e0 01 01 btusb
ifc 6-1 2 ff ff ff -

# HID pad nobody took (driver exists, did not start)
dev 7-1 0079 0006 00 1 "DragonRise Inc.  " "Generic   USB  Joystick  "
ifc 7-1 0 03 00 00 - 0107
