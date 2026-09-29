package org.andr36oid.controllers;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;

import java.util.Locale;
import java.util.regex.Pattern;

/** Which Bluetooth devices are game controllers. */
final class BtPads {

    // Controllers on Bluetooth LE (Xbox Series, newer Xbox One S firmware) and some cheap
    // pads report no useful device class, so the name counts too
    private static final Pattern NAMES = Pattern.compile(
            "controller|gamepad|game pad|joystick|joy-con|8bitdo|xbox|dualsense|dualshock"
                    + "|gamesir|ipega|flydigi|stadia|nimbus|moga|pad\\b");

    // Class of device, peripheral minor class: bits 6-7 keyboard/pointing, bits 2-5 the kind
    private static final int KEYBOARD_OR_POINTING = 0xC0;
    private static final int KIND_MASK = 0x3C;
    private static final int KIND_JOYSTICK = 0x04;
    private static final int KIND_GAMEPAD = 0x08;

    private BtPads() {
    }

    static boolean isController(BluetoothDevice device) {
        final BluetoothClass cls = device.getBluetoothClass();
        if (cls != null && cls.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL) {
            final int minor = cls.getDeviceClass();
            final int kind = minor & KIND_MASK;
            if ((minor & KEYBOARD_OR_POINTING) == 0
                    && (kind == KIND_JOYSTICK || kind == KIND_GAMEPAD || kind == 0)) {
                return true;
            }
        }
        final String name = device.getName();
        return name != null && NAMES.matcher(name.toLowerCase(Locale.ROOT)).find();
    }

    static String label(BluetoothDevice device) {
        final String name = device.getAlias();
        return name != null && !name.isEmpty() ? name : device.getAddress();
    }
}
