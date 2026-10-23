package org.andr36oid.usbmode;

import java.util.ArrayList;
import java.util.List;

/** Only the native policy decides whether a combination can start. */
final class UsbWizard {
    static final int ROLE = 0, FUNCTIONS = 1, CONTROLLER = 2, STORAGE = 3,
            KEYBOARD = 4, MOUSE = 5, REVIEW = 6;
    static final String[] ROLES = {"pc", "game", "drive", "custom", "wired", "music", "awake", "macro", "link"};

    static int preset(String role, boolean debugging) {
        int mask = "pc".equals(role) ? UsbFunctions.STORAGE | UsbFunctions.KEYBOARD | UsbFunctions.MOUSE
                : "game".equals(role) ? UsbFunctions.XINPUT : "wired".equals(role) || "link".equals(role) ? UsbFunctions.NETWORK
                : "music".equals(role) ? UsbFunctions.MIDI : "awake".equals(role) ? UsbFunctions.MOUSE
                : "macro".equals(role) ? UsbFunctions.KEYBOARD | UsbFunctions.MEDIA : UsbFunctions.STORAGE;
        if (debugging && ("pc".equals(role) || "game".equals(role) || "drive".equals(role))) mask |= UsbFunctions.ADB;
        return mask;
    }

    static List<Integer> steps(String role, int mask) {
        final List<Integer> steps = new ArrayList<>();
        steps.add(ROLE);
        if ("custom".equals(role)) steps.add(FUNCTIONS);
        if ("custom".equals(role) || "game".equals(role) || (mask & UsbFunctions.CONTROLLERS) != 0) steps.add(CONTROLLER);
        if ((mask & UsbFunctions.STORAGE) != 0) steps.add(STORAGE);
        if ((mask & UsbFunctions.KEYBOARD) != 0) steps.add(KEYBOARD);
        if ((mask & UsbFunctions.MOUSE) != 0) steps.add(MOUSE);
        steps.add(REVIEW);
        return steps;
    }

    static boolean captured(String stage, boolean ready) {
        return ready && ("controller_daemon".equals(stage) || "device_role".equals(stage)
                || "bind_drive".equals(stage) || "sharing".equals(stage));
    }

    static boolean bootMenu(int mask, UsbDrive.Offer offer, boolean cdrom) {
        return (mask & UsbFunctions.STORAGE) != 0 && cdrom && offer != null
                && offer.kind == UsbDrive.Kind.IMAGE && offer.bootable && offer.cdromPossible();
    }

    static int[] blockers(String reason, int mask) {
        if ("sound".equals(reason)) return new int[] {UsbFunctions.SOUNDBOARD, UsbFunctions.SOUND};
        if ("networks".equals(reason)) return new int[] {UsbFunctions.NETWORK, UsbFunctions.RNDIS};
        if ("standalone".equals(reason)) {
            final int solo = Integer.lowestOneBit(mask & (UsbFunctions.FLIPPER | UsbFunctions.SWITCH
                    | UsbFunctions.PS3 | UsbFunctions.GAMECUBE | UsbFunctions.XBOX | UsbFunctions.PS4));
            return new int[] {solo, Integer.lowestOneBit(mask & ~solo)};
        }
        int group = mask & UsbFunctions.CONTROLLERS;
        if ("routing".equals(reason)) {
            group = mask & (UsbFunctions.CONTROLLERS | UsbFunctions.MEDIA | UsbFunctions.MIDI | UsbFunctions.SOUNDBOARD);
            if ((mask & (UsbFunctions.SOUNDBOARD | UsbFunctions.KEYBOARD)) == (UsbFunctions.SOUNDBOARD | UsbFunctions.KEYBOARD))
                group = UsbFunctions.SOUNDBOARD | UsbFunctions.KEYBOARD;
        }
        final int first = Integer.lowestOneBit(group);
        return new int[] {first, Integer.lowestOneBit(group & ~first)};
    }
}
