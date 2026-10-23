package org.andr36oid.usbmode;

import java.util.Arrays;
import java.util.List;

public final class WizardTest {
    private static int checks;
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Wizard check " + checks);
    }
    public static void main(String[] args) {
        check(UsbWizard.ROLES.length == 9);
        int[] masks = {UsbFunctions.STORAGE | UsbFunctions.KEYBOARD | UsbFunctions.MOUSE, UsbFunctions.XINPUT,
                UsbFunctions.STORAGE, UsbFunctions.XINPUT, UsbFunctions.NETWORK, UsbFunctions.MIDI,
                UsbFunctions.MOUSE, UsbFunctions.KEYBOARD | UsbFunctions.MEDIA, UsbFunctions.NETWORK};
        Integer[][] sequences = {{0,3,4,5,6}, {0,2,6}, {0,3,6}, {0,1,2,6}, {0,6}, {0,6}, {0,5,6}, {0,4,6}, {0,6}};
        for (int i = 0; i < UsbWizard.ROLES.length; i++) {
            String role = UsbWizard.ROLES[i];
            if (!"custom".equals(role)) check(UsbWizard.preset(role, false) == masks[i]);
            check(UsbWizard.steps(role, masks[i]).equals(Arrays.asList(sequences[i])));
        }
        for (String role : UsbWizard.ROLES) for (boolean debugging : new boolean[] {false, true}) {
            int mask = "custom".equals(role) ? UsbFunctions.XINPUT : UsbWizard.preset(role, debugging);
            List<Integer> steps = UsbWizard.steps(role, mask);
            check(steps.get(0) == UsbWizard.ROLE && steps.get(steps.size() - 1) == UsbWizard.REVIEW);
            check(steps.contains(UsbWizard.FUNCTIONS) == "custom".equals(role));
            check(steps.contains(UsbWizard.STORAGE) == ((mask & UsbFunctions.STORAGE) != 0));
            check(steps.contains(UsbWizard.KEYBOARD) == ((mask & UsbFunctions.KEYBOARD) != 0));
            check(steps.contains(UsbWizard.MOUSE) == ((mask & UsbFunctions.MOUSE) != 0));
            check(steps.contains(UsbWizard.CONTROLLER) == ("custom".equals(role) || "game".equals(role)));
            check(((mask & UsbFunctions.ADB) != 0) == (debugging && Arrays.asList("pc", "game", "drive").contains(role)));
        }
        for (int mask = 0; mask < 32; mask++) {
            List<Integer> steps = UsbWizard.steps("custom", mask);
            check(steps.contains(UsbWizard.CONTROLLER));
            check(steps.contains(UsbWizard.STORAGE) == ((mask & UsbFunctions.STORAGE) != 0));
            check(steps.contains(UsbWizard.KEYBOARD) == ((mask & UsbFunctions.KEYBOARD) != 0));
            check(steps.contains(UsbWizard.MOUSE) == ((mask & UsbFunctions.MOUSE) != 0));
        }
        for (int controller : UsbFunctions.CONTROLLER_BITS) if (controller != 0) {
            int[] blockers = UsbWizard.blockers("routing", controller | UsbFunctions.MEDIA);
            check((blockers[0] | blockers[1]) == (controller | UsbFunctions.MEDIA));
            blockers = UsbWizard.blockers("routing", controller | UsbFunctions.MIDI);
            check((blockers[0] | blockers[1]) == (controller | UsbFunctions.MIDI));
        }
        int[] blockers = UsbWizard.blockers("routing", UsbFunctions.SOUND | UsbFunctions.SOUNDBOARD | UsbFunctions.KEYBOARD);
        check((blockers[0] | blockers[1]) == (UsbFunctions.SOUNDBOARD | UsbFunctions.KEYBOARD));
        blockers = UsbWizard.blockers("standalone", UsbFunctions.SWITCH | UsbFunctions.STORAGE | UsbFunctions.ADB);
        check(blockers[0] == UsbFunctions.SWITCH && blockers[1] == UsbFunctions.STORAGE);
        blockers = UsbWizard.blockers("networks", UsbFunctions.NETWORK | UsbFunctions.RNDIS);
        check(blockers[0] == UsbFunctions.NETWORK && blockers[1] == UsbFunctions.RNDIS);
        blockers = UsbWizard.blockers("sound", UsbFunctions.SOUNDBOARD);
        check(blockers[0] == UsbFunctions.SOUNDBOARD && blockers[1] == UsbFunctions.SOUND);
        blockers = UsbWizard.blockers("controllers", UsbFunctions.XINPUT | UsbFunctions.GAMEPAD);
        check(blockers[0] == UsbFunctions.XINPUT && blockers[1] == UsbFunctions.GAMEPAD);
        for (int single : new int[] {UsbFunctions.KEYBOARD, UsbFunctions.XINPUT, UsbFunctions.MEDIA})
            check(UsbWizard.blockers("routing", single)[1] == 0);
        check(!UsbWizard.captured("release_storage", true));
        check(!UsbWizard.captured("gate_android", true));
        check(!UsbWizard.captured("controller_daemon", false));
        check(UsbWizard.captured("controller_daemon", true));
        check(UsbWizard.captured("sharing", true));
        UsbDrive.Offer image = new UsbDrive.Offer();
        image.kind = UsbDrive.Kind.IMAGE; image.node = "Boot.iso"; image.bytes = 1048576;
        check(!UsbWizard.bootMenu(UsbFunctions.STORAGE, image, true));
        image.bootable = true;
        check(UsbWizard.bootMenu(UsbFunctions.STORAGE, image, true));
        check(!UsbWizard.bootMenu(UsbFunctions.STORAGE, image, false));
        check(!UsbWizard.bootMenu(UsbFunctions.XINPUT, image, true));
        image.kind = UsbDrive.Kind.EASYROMS;
        check(!UsbWizard.bootMenu(UsbFunctions.STORAGE, image, true));
        System.out.println(checks + " wizard preset, conditional step and named blocker checks passed");
    }
}
