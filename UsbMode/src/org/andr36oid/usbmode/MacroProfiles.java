package org.andr36oid.usbmode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

final class MacroProfiles {
    static final int[] BUTTONS = {0,3,1,2,12,11,14,13,8,9,15,16};
    static final String[] CAPS = {"↑","→","↓","←","A","B","X","Y","L1","R1","L2","R2"};
    static final class Combo {
        int modifiers, media;
        int[] keys = new int[0];
        Combo() { }
        Combo(int modifiers, int... keys) { this.modifiers = modifiers; this.keys = keys.clone(); }
        String label() {
            StringBuilder text = new StringBuilder();
            final String[] mods = {"Ctrl","Shift","Alt","Win","RCtrl","RShift","AltGr","RWin"};
            for (int i = 0; i < 8; i++) if ((modifiers & (1 << i)) != 0) text.append(mods[i]).append('+');
            for (int key : keys) text.append(keyName(key)).append('+');
            if (media != 0) text.append("♪").append(Integer.toHexString(media)).append('+');
            return text.length() == 0 ? "—" : text.substring(0,text.length() - 1);
        }
    }
    static final class Profile {
        String name;
        final Combo[] buttons = new Combo[12];
        Profile(String name) { this.name = name; for (int i = 0; i < buttons.length; i++) buttons[i] = new Combo(); }
    }
    final List<Profile> profiles = new ArrayList<>();
    static MacroProfiles examples(String streaming, String editing) {
        MacroProfiles result = new MacroProfiles(); Profile stream = new Profile(streaming), edit = new Profile(editing);
        for (int i = 0; i < 12; i++) stream.buttons[i] = new Combo(0,0x68 + i);
        int[] keys = {6,25,29,28,22,4,27,9,0x2b,0x16,0x28,0x29};
        for (int i = 0; i < 12; i++) edit.buttons[i] = new Combo(i < 8 ? 1 : i == 8 ? 4 : i == 9 ? 10 : 0,keys[i]);
        result.profiles.add(stream); result.profiles.add(edit); return result;
    }
    Profile duplicate(int index, String name) {
        MacroProfiles copy = read(write()); Profile profile = copy.profiles.get(index); profile.name = validName(name);
        if (profiles.size() >= 32) throw new IllegalArgumentException(); profiles.add(profile); return profile;
    }
    static String validName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 60) throw new IllegalArgumentException();
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) throw new IllegalArgumentException();
        return name.trim();
    }
    String write() {
        StringBuilder text = new StringBuilder("1\n");
        for (Profile profile : profiles) {
            text.append(Base64.getEncoder().encodeToString(validName(profile.name).getBytes(StandardCharsets.UTF_8)));
            for (Combo combo : profile.buttons) {
                text.append(';').append(combo.modifiers).append(',').append(combo.media);
                for (int key : combo.keys) text.append(',').append(key);
            }
            text.append('\n');
        }
        return text.toString();
    }
    static MacroProfiles read(String text) {
        if (text == null || text.length() > 65536) throw new IllegalArgumentException();
        String[] lines = text.split("\n");
        if (lines.length < 2 || lines.length > 33 || !lines[0].equals("1")) throw new IllegalArgumentException();
        MacroProfiles result = new MacroProfiles();
        for (int row = 1; row < lines.length; row++) {
            String[] fields = lines[row].split(";",-1);
            if (fields.length != 13) throw new IllegalArgumentException();
            Profile profile = new Profile(validName(new String(Base64.getDecoder().decode(fields[0]),StandardCharsets.UTF_8)));
            for (int i = 0; i < 12; i++) {
                String[] values = fields[i + 1].split(",",-1);
                if (values.length < 2 || values.length > 8) throw new IllegalArgumentException();
                Combo combo = profile.buttons[i]; combo.modifiers = number(values[0],255); combo.media = number(values[1],63);
                combo.keys = new int[values.length - 2];
                for (int j = 2; j < values.length; j++) combo.keys[j - 2] = number(values[j],0x73);
            }
            result.profiles.add(profile);
        }
        return result;
    }
    private static int number(String text, int maximum) {
        int n = Integer.parseInt(text); if (n < 0 || n > maximum) throw new IllegalArgumentException(); return n;
    }
    static String keyName(int key) {
        if (key >= 4 && key <= 29) return String.valueOf((char) ('A' + key - 4));
        if (key >= 0x1e && key <= 0x27) return String.valueOf((key - 0x1d) % 10);
        if (key >= 0x3a && key <= 0x45) return "F" + (key - 0x39);
        if (key >= 0x68 && key <= 0x73) return "F" + (key - 0x68 + 13);
        for (KeyboardModel.Key[] row : KeyboardModel.ROWS) for (KeyboardModel.Key item : row) if (item.usage == key) return item.cap;
        return String.format("%02X",key);
    }
    static final class Player {
        Profile profile;
        int pressed;
        private boolean armed;
        Player(Profile profile) { this.profile = profile; }
        void reset() { armed = false; pressed = 0; }
        byte[] update(byte[] frame) {
            byte[] packet = new byte[16]; packet[0] = 1;
            pressed = (frame[1] & 255) | ((frame[2] & 255) << 8) | ((frame[3] & 255) << 16);
            if (frame[13] == 0 || frame[14] != 0 || (pressed & (1 << 5)) != 0) { reset(); return packet; }
            if (!armed) { armed = pressed == 0; return packet; }
            List<Integer> usages = new ArrayList<>();
            for (int i = 0; i < BUTTONS.length; i++) if ((pressed & (1 << BUTTONS[i])) != 0) {
                Combo combo = profile.buttons[i]; packet[1] |= combo.modifiers; packet[13] |= combo.media;
                for (int key : combo.keys) if (key != 0 && !usages.contains(key)) usages.add(key);
            }
            if (usages.size() > 6) Arrays.fill(packet,2,8,(byte) 1);
            else for (int i = 0; i < usages.size(); i++) packet[2 + i] = (byte) (int) usages.get(i);
            packet[11] = 1; return packet;
        }
    }
}
