package org.andr36oid.usbmode;

import java.util.Arrays;

public class PadModelsTest {
    private static int checks;
    private static void check(boolean value) { if (!value) throw new AssertionError("Check " + checks); checks++; }
    private static byte[] frame(int buttons) {
        byte[] frame = new byte[16]; frame[0] = frame[13] = 1;
        for (int i = 0; i < 3; i++) frame[1 + i] = (byte) (buttons >> (8 * i)); return frame;
    }
    private static short sample(byte[] pcm, int n) { return (short) ((pcm[n * 2] & 255) | ((pcm[n * 2 + 1] & 255) << 8)); }
    public static void main(String[] args) {
        MacroProfiles profiles = MacroProfiles.examples("Streaming","Editing");
        check(profiles.profiles.size() == 2);
        check(profiles.profiles.get(0).buttons[11].keys[0] == 0x73);
        check(profiles.profiles.get(1).buttons[0].modifiers == 1 && profiles.profiles.get(1).buttons[0].keys[0] == 6);
        String original = profiles.write();
        check(MacroProfiles.read(original).write().equals(original));
        profiles.duplicate(0,"Streams ; 日本語");
        profiles.profiles.get(2).buttons[0].keys[0] = 4;
        check(profiles.profiles.get(0).buttons[0].keys[0] == 0x68);
        check(MacroProfiles.read(profiles.write()).profiles.get(2).name.equals("Streams ; 日本語"));
        for (String invalid : new String[] {"", "2\n", "1\n?",original.replace("0,0,104","256,0,104"),original.replace("0,0,104","0,64,104"),original.replace("0,0,104","0,0,116")}) {
            try { MacroProfiles.read(invalid); throw new AssertionError(invalid); } catch (IllegalArgumentException expected) { checks++; }
        }
        for (String invalid : new String[] {"", " ", "x\ny", "x".repeat(61)}) {
            try { MacroProfiles.validName(invalid); throw new AssertionError(); } catch (IllegalArgumentException expected) { checks++; }
        }
        MacroProfiles.Player player = new MacroProfiles.Player(profiles.profiles.get(0));
        check(player.update(frame(1))[11] == 0); // Must release before arming.
        player.update(frame(0));
        byte[] packet = player.update(frame(1)); check((packet[2] & 255) == 0x68 && packet[11] == 1);
        check(player.update(frame(0))[2] == 0);
        player.profile.buttons[4] = new MacroProfiles.Combo(10,0x16); // Win+Shift+S
        packet = player.update(frame(1 << 12)); check(packet[1] == 10 && packet[2] == 0x16);
        player.profile.buttons[5].media = 33;
        packet = player.update(frame((1 << 12) | (1 << 11))); check(packet[13] == 33 && packet[1] == 10);
        packet = player.update(frame((1 << 5) | (1 << 12))); check(packet[11] == 0 && packet[2] == 0 && packet[13] == 0);
        player.update(frame(0)); player.profile.buttons[0].keys = new int[] {4,5,6,7,8,9}; player.profile.buttons[1].keys = new int[] {10};
        packet = player.update(frame(1 | (1 << 3))); for (int i = 2; i < 8; i++) check(packet[i] == 1);
        player.reset(); check(player.update(frame(1))[11] == 0);
        SoundMixer mixer = new SoundMixer(); mixer.volume(100);
        mixer.play(new short[] {10000,-10000,20000,-20000});
        mixer.play(new short[] {30000,-30000});
        byte[] pcm = mixer.render(2,null); check(sample(pcm,0) == 32767 && sample(pcm,1) == -32768);
        check(sample(pcm,2) == 20000 && sample(pcm,3) == -20000);
        check(Arrays.equals(mixer.render(2,null),new byte[8]));
        mixer.play(new short[] {10000,10000}); mixer.stop(); check(Arrays.equals(mixer.render(1,null),new byte[4]));
        mixer.volume(25); pcm = mixer.render(1,new short[] {20000,-20000}); check(sample(pcm,0) == 5000 && sample(pcm,1) == -5000);
        mixer.volume(0); mixer.play(new short[] {30000,-30000}); check(Arrays.equals(mixer.render(1,null),new byte[4]));
        mixer.volume(100); mixer.play(new short[] {1}); check(Arrays.equals(mixer.render(1,null),new byte[4]));
        System.out.println(checks + " macro serialization, held combinations, F13-F24 and sound mixer checks passed");
    }
}
