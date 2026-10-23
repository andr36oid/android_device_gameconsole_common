package org.andr36oid.usbmode;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class KeyboardTest {
    private static int checks;
    private static long time;
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Keyboard check " + checks);
    }
    private static byte[] frame(int... buttons) {
        final byte[] frame = new byte[16]; frame[0] = frame[13] = 1;
        for (int button : buttons) frame[1 + button / 8] |= 1 << (button % 8);
        return frame;
    }
    private static byte[] update(KeyboardModel model, int... buttons) {
        time += 10;
        return model.update(frame(buttons), time);
    }
    private static void select(KeyboardModel model, int usage, int action) {
        for (int row = 0; row < KeyboardModel.ROWS.length; row++) {
            for (int column = 0; column < KeyboardModel.ROWS[row].length; column++) {
                final KeyboardModel.Key key = KeyboardModel.ROWS[row][column];
                if (key.usage == usage && key.action == action) {
                    while (model.row() != row) model.move(0, 1);
                    while (model.column() != column) model.move(1, 0);
                    return;
                }
            }
        }
        throw new AssertionError("Missing usage " + usage);
    }
    private static boolean contains(byte[] packet, int key) {
        for (int i = 2; i < 8; i++) if ((packet[i] & 255) == key) return true;
        return false;
    }
    public static void main(String[] args) {
        final KeyboardModel model = new KeyboardModel("us");
        final Set<Integer> usages = new HashSet<>();
        for (int row = 0; row < KeyboardModel.ROWS.length; row++) {
            check(KeyboardModel.rowWidth(row) > 0);
            for (KeyboardModel.Key key : KeyboardModel.ROWS[row]) {
                check(key.action != 0 || (key.usage >= 4 && key.usage <= 0x65)
                        || (key.usage >= 0xe0 && key.usage <= 0xe7));
                if (key.action == 0) check(usages.add(key.usage));
                check(key.width > 0);
            }
        }
        // Every letter, number, function key and the entire requested editing cluster.
        for (int u = 4; u <= 0x38; u++) check(usages.contains(u));
        for (int u = 0x3a; u <= 0x53; u++) check(usages.contains(u));
        for (int u : new int[] {0x39,0x64,0x65,0xe0,0xe1,0xe2,0xe3,0xe4,0xe5,0xe6}) check(usages.contains(u));
        select(model, 0x2c, 0);
        int row = model.row(), column = model.column();
        model.move(0, -1); model.move(0, 1);
        check(model.row() == row && model.column() == column);
        model.move(0, 1); model.move(0, 1); model.move(0, -1); model.move(0, -1);
        check(model.row() == row && model.column() == column);
        for (int i = 0; i < KeyboardModel.ROWS[row].length; i++) model.move(1, 0);
        check(model.column() == column);
        for (int i = 0; i < KeyboardModel.ROWS.length; i++) model.move(0, 1);
        check(model.row() == row && model.column() == column);
        check(!contains(update(model, 12), 0x2c)); // starting with A down must not type
        update(model);
        check(contains(update(model, 12), 0x2c));
        model.move(1, 0);
        check(contains(update(model, 12), 0x2c)); // hold original key across navigation
        check(!contains(update(model), 0x2c));
        check(contains(update(model, 11), 0x2a)); update(model);
        check(contains(update(model, 14), 0x2c)); update(model);
        check(contains(update(model, 13), 0x28)); update(model);
        check(contains(update(model, 4), 0x28)); update(model);
        select(model, 4, 0);
        check(update(model, 8)[1] == 2); update(model); // Shift next
        check(update(model, 12)[1] == 2);
        check(update(model, 12)[1] == 2);
        check(update(model)[1] == 0); // consumed on release, so PC repeats while held
        update(model, 8); update(model); update(model, 8); update(model);
        check(model.modifier(1) == 2);
        update(model, 12); check(update(model)[1] == 2);
        update(model, 8); update(model); check(model.modifier(1) == 0);
        update(model, 9); update(model);
        select(model, 0xe2, 0); update(model, 12); update(model);
        select(model, 0x4c, 0);
        byte[] packet = update(model, 12);
        check(packet[1] == 5 && contains(packet, 0x4c)); // Ctrl+Alt+Del
        check(update(model)[1] == 0);
        select(model, 0xe3, 0); check(update(model, 12)[1] == 8); update(model);
        select(model, 4, 0); check(update(model, 12)[1] == 8); update(model);
        check(update(model, 15)[8] == 1); update(model);
        check(update(model, 16)[8] == 2); update(model);
        check(update(model, 6)[8] == 4); update(model);
        check(update(model, 5, 12)[8] == 1); update(model);
        check(update(model, 5, 11)[8] == 2); update(model);
        check(update(model, 5, 14)[8] == 4); update(model);
        packet = update(model, 5, 4);
        check(packet[9] == 1 && !contains(packet, 0x28)); update(model);
        select(model, 0, KeyboardModel.MOUSE_LEFT);
        check(update(model, 12)[8] == 1); check(update(model)[8] == 1);
        check(update(model, 12)[8] == 0); update(model);
        select(model, 0, KeyboardModel.WHEEL_UP);
        check(update(model, 12)[10] == 1); check(update(model)[10] == 0);
        update(model, 9); update(model); update(model, 9); update(model);
        update(model, 15, 12);
        byte[] disconnected = frame(15, 12); disconnected[13] = 0;
        packet = model.update(disconnected, ++time);
        for (int i = 1; i < 12; i++) check(packet[i] == 0);
        check(update(model, 12)[11] == 0); // reconnect still held: wait for neutral
        check(update(model)[11] == 1);
        select(model, 4, 0); check(contains(update(model, 12), 4));
        model.reset();
        packet = update(model, 12);
        check(!contains(packet, 4) && packet[8] == 0 && packet[1] == 0);
        update(model);
        int oldRow = model.row(); update(model, 1); check(model.row() != oldRow);
        oldRow = model.row(); update(model, 1); check(model.row() == oldRow);
        time += 350; update(model, 1); check(model.row() != oldRow); update(model);
        check(Arrays.equals(KeyboardModel.pack(4,4,5,6,7,8,9), new byte[] {4,5,6,7,8,9}));
        check(Arrays.equals(KeyboardModel.pack(4,5,6,7,8,9,10), new byte[] {1,1,1,1,1,1}));
        check(Arrays.equals(KeyboardModel.pack(), new byte[6]));
        for (String layout : KeyboardModel.LAYOUTS) {
            final KeyboardModel labels = new KeyboardModel(layout);
            for (KeyboardModel.Key[] keys : KeyboardModel.ROWS) for (KeyboardModel.Key key : keys)
                if (key.action == 0) check(!labels.label(key).isEmpty());
        }
        check(new KeyboardModel("de").label(new KeyboardModel.Key(0x1c,"Y",1)).equals("Z"));
        check(new KeyboardModel("fr").label(new KeyboardModel.Key(0x14,"Q",1)).equals("A"));
        check(KeyboardModel.defaultLayout(Locale.GERMAN).equals("de"));
        check(KeyboardModel.defaultLayout(Locale.FRENCH).equals("fr"));
        check(KeyboardModel.defaultLayout(new Locale("ja")).equals("us"));
        check(KeyboardModel.validLayout("invalid").equals("us"));
        model.reset();
        byte[] stickHeld = frame(); stickHeld[5] = 0x70;
        check(model.update(stickHeld, ++time)[11] == 0);
        check(update(model)[11] == 1);
        check(model.update(new byte[3], ++time)[11] == 0);
        for (String layout : KeyboardModel.LAYOUTS) {
            final KeyboardModel positions = new KeyboardModel(layout);
            update(positions); select(positions, 0x14, 0);
            check(contains(update(positions, 12), 0x14));
        }
        update(model);
        select(model, 0, KeyboardModel.STOP); update(model, 12); check(model.stopRequested());
        System.out.println("Keyboard navigation, usages, labels, 6KRO, holds, modifiers and release guards: " + checks + " checks passed");
    }
}
