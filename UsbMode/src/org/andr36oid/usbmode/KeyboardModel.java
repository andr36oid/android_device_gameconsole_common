package org.andr36oid.usbmode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// HID positions never change with the label layout. The computer chooses the characters.
final class KeyboardModel {
    static final String[] LAYOUTS = {"us", "de", "fr"};
    static final int MOUSE_LEFT = 1, MOUSE_RIGHT = 2, MOUSE_MIDDLE = 3,
            WHEEL_UP = 4, WHEEL_DOWN = 5, STOP = 6, TYPE_FILE = 7, CHANGE_DISC = 8;
    static final class Key {
        final int usage;
        final String cap;
        final float width;
        final int action;
        Key(int usage, String cap, float width) { this(usage, cap, width, 0); }
        Key(int usage, String cap, float width, int action) {
            this.usage = usage; this.cap = cap; this.width = width; this.action = action;
        }
    }
    static final Key[][] ROWS = build();
    private static Key k(int usage, String cap) { return new Key(usage, cap, 1); }
    private static Key wide(int usage, String cap, float width) { return new Key(usage, cap, width); }
    private static Key[][] build() {
        final List<Key> functions = new ArrayList<>();
        functions.add(k(0x29, "Esc"));
        for (int i = 0; i < 12; i++) functions.add(k(0x3a + i, "F" + (i + 1)));
        functions.add(k(0x46, "PrtSc")); functions.add(k(0x47, "ScrLk")); functions.add(k(0x48, "Pause"));
        return new Key[][] {
            functions.toArray(new Key[0]),
            {k(0x35,"`"),k(0x1e,"1"),k(0x1f,"2"),k(0x20,"3"),k(0x21,"4"),k(0x22,"5"),
             k(0x23,"6"),k(0x24,"7"),k(0x25,"8"),k(0x26,"9"),k(0x27,"0"),k(0x2d,"-"),
             k(0x2e,"="),wide(0x2a,"Bksp",1.8f),k(0x49,"Ins"),k(0x4a,"Home"),k(0x4b,"PgUp")},
            {wide(0x2b,"Tab",1.5f),k(0x14,"Q"),k(0x1a,"W"),k(0x08,"E"),k(0x15,"R"),
             k(0x17,"T"),k(0x1c,"Y"),k(0x18,"U"),k(0x0c,"I"),k(0x12,"O"),k(0x13,"P"),
             k(0x2f,"["),k(0x30,"]"),k(0x31,"\\"),k(0x4c,"Del"),k(0x4d,"End"),k(0x4e,"PgDn")},
            {wide(0x39,"Caps",1.8f),k(0x04,"A"),k(0x16,"S"),k(0x07,"D"),k(0x09,"F"),
             k(0x0a,"G"),k(0x0b,"H"),k(0x0d,"J"),k(0x0e,"K"),k(0x0f,"L"),k(0x33,";"),
             k(0x34,"'"),k(0x32,"ISO #"),wide(0x28,"Enter",2),k(0x53,"Num")},
            {wide(0xe1,"Shift",1.8f),k(0x64,"ISO"),k(0x1d,"Z"),k(0x1b,"X"),k(0x06,"C"),
             k(0x19,"V"),k(0x05,"B"),k(0x11,"N"),k(0x10,"M"),k(0x36,","),k(0x37,"."),
             k(0x38,"/"),wide(0xe5,"Shift",1.8f),k(0x52,"↑")},
            {k(0xe0,"Ctrl"),k(0xe3,"Win"),k(0xe2,"Alt"),wide(0x2c,"Space",5),k(0xe6,"AltGr"),
             k(0x65,"Menu"),k(0xe4,"Ctrl"),k(0x50,"←"),k(0x51,"↓"),k(0x4f,"→")},
            {new Key(0,"",2,MOUSE_LEFT),new Key(0,"",2,MOUSE_RIGHT),new Key(0,"",2,MOUSE_MIDDLE),
             new Key(0,"",2,WHEEL_UP),new Key(0,"",2,WHEEL_DOWN),new Key(0,"",2,TYPE_FILE),new Key(0,"",2,CHANGE_DISC),new Key(0,"",2,STOP)}
        };
    }

    private final int[] mModifiers = new int[8]; // off, next key, locked
    private final int[] mHeld = new int[4];
    private final String mLayout;
    private int mRow = 2, mColumn = 1, mPrevious, mMouseLocked, mWheel;
    private float mDesiredColumn = center(2, 1);
    private int mDirection = -1, mActionHeld;
    private long mRepeatAt;
    private boolean mArmed, mStop, mFileRequested, mDiscRequested;
    MacroProfiles.Player macro;
    final TextTyper typer = new TextTyper();
    boolean discRequested() { boolean value = mDiscRequested; mDiscRequested = false; return value; }
    boolean fileRequested() { boolean requested = mFileRequested; mFileRequested = false; return requested; }

    KeyboardModel(String layout) { mLayout = validLayout(layout); }
    static String validLayout(String layout) {
        for (String value : LAYOUTS) if (value.equals(layout)) return value;
        return "us";
    }
    static String defaultLayout(Locale locale) { return validLayout(locale.getLanguage()); }
    String layout() { return mLayout; }
    int row() { return mRow; }
    int column() { return mColumn; }
    boolean stopRequested() { return mStop; }
    int modifier(int bit) { return mModifiers[bit]; }
    int mouseLocked() { return mMouseLocked; }
    static float rowWidth(int row) {
        float width = 0;
        for (Key key : ROWS[row]) width += key.width;
        return width;
    }
    static float center(int row, int column) {
        float x = 0;
        for (int i = 0; i < column; i++) x += ROWS[row][i].width;
        return (x + ROWS[row][column].width / 2) / rowWidth(row);
    }
    void move(int dx, int dy) {
        if (dx != 0) {
            mColumn = Math.floorMod(mColumn + dx, ROWS[mRow].length);
            mDesiredColumn = center(mRow, mColumn);
        }
        if (dy != 0) {
            mRow = Math.floorMod(mRow + dy, ROWS.length);
            float nearest = Float.MAX_VALUE;
            for (int i = 0; i < ROWS[mRow].length; i++) {
                final float distance = Math.abs(center(mRow, i) - mDesiredColumn);
                if (distance < nearest) { nearest = distance; mColumn = i; }
            }
        }
    }
    private void toggle(int bit) { mModifiers[bit] = (mModifiers[bit] + 1) % 3; }
    private void consumeModifiers() {
        for (int i = 0; i < 8; i++) if (mModifiers[i] == 1) mModifiers[i] = 0;
    }
    void reset() {
        if (macro != null) macro.reset();
        typer.abort(); mFileRequested = mDiscRequested = false;
        Arrays.fill(mModifiers, 0); Arrays.fill(mHeld, 0);
        mArmed = false; mPrevious = 0; mMouseLocked = 0; mWheel = 0; mDirection = -1; mActionHeld = 0;
    }
    private static boolean down(int buttons, int bit) { return (buttons & (1 << bit)) != 0; }

    // Native button indexes are Xbox positions; these shortcuts use the printed Nintendo labels.
    byte[] update(byte[] frame, long time) {
        if (frame.length != 16 || frame[0] != 1) { reset(); return packet(0, false); }
        if (macro != null) return macro.update(frame);
        final int buttons = (frame[1] & 255) | ((frame[2] & 255) << 8) | ((frame[3] & 255) << 16);
        if (frame[13] == 0) { reset(); return packet(0, false); }
        if (typer.active()) {
            final boolean abort = down(buttons,11) || frame[14] != 0 || (down(buttons,4) && down(buttons,5));
            final byte[] result = typer.update(time,abort);
            final int usage = result[2] & 255;
            final boolean letter = usage >= 4 && usage <= 0x1d || "de".equals(mLayout)
                    && (usage == 0x2f || usage == 0x33 || usage == 0x34) || "fr".equals(mLayout) && usage == 0x33;
            if ((frame[12] & 2) != 0 && letter && (result[1] & 64) == 0) result[1] ^= 2;
            mArmed = false; mPrevious = buttons;
            return result;
        }
        if (!mArmed) {
            boolean neutral = buttons == 0;
            for (int i = 4; i < 12; i += 2) {
                final int axis = (short) ((frame[i] & 255) | ((frame[i + 1] & 255) << 8));
                neutral &= Math.abs(axis) < 3277;
            }
            mArmed = neutral;
            mPrevious = buttons;
            return packet(0, false);
        }
        final int pressed = buttons & ~mPrevious;
        final boolean select = down(buttons, 5);
        mWheel = 0;
        // Releasing a shortcut ends the exact key chosen on its down edge, even after navigation.
        final int[] triggers = {12,11,14,13}; // A, B, X, Y
        for (int i = 0; i < 4; i++) {
            final boolean held = down(buttons, triggers[i]) || (i == 3 && down(buttons, 4));
            if (!held || select) {
                if (mHeld[i] != 0) { mHeld[i] = 0; consumeModifiers(); }
            }
        }
        if (!down(buttons, 12)) mActionHeld = 0;
        if (select) {
            mActionHeld = 0;
            Arrays.fill(mHeld, 0); consumeModifiers(); mDirection = -1;
        } else {
            if (down(pressed, 8)) toggle(1); // L1: Shift
            if (down(pressed, 9)) toggle(0); // R1: Ctrl
            int direction = -1;
            for (int i = 0; i < 4; i++) if (down(buttons, i)) { direction = i; break; }
            if (direction != mDirection || (direction >= 0 && time >= mRepeatAt)) {
                if (direction >= 0) {
                    move(direction == 2 ? -1 : direction == 3 ? 1 : 0,
                            direction == 0 ? -1 : direction == 1 ? 1 : 0);
                    mRepeatAt = time + (direction == mDirection ? 90 : 350);
                }
                mDirection = direction;
            }
            if (down(pressed, 12)) {
                final Key key = ROWS[mRow][mColumn];
                mActionHeld = key.action;
                if (key.usage >= 0xe0) toggle(key.usage - 0xe0);
                else if (key.action >= MOUSE_LEFT && key.action <= MOUSE_MIDDLE)
                    mMouseLocked ^= 1 << (key.action - 1);
                else if (key.action == STOP) mStop = true;
                else if (key.action == CHANGE_DISC) mDiscRequested = true;
                else if (key.action == TYPE_FILE) mFileRequested = true;
                else mHeld[0] = key.usage;
            }
            if (down(pressed, 11)) mHeld[1] = 0x2a;
            if (down(pressed, 14)) mHeld[2] = 0x2c;
            if (down(pressed, 13) || down(pressed, 4)) mHeld[3] = 0x28;
            if (down(buttons, 12)) {
                if (mActionHeld == WHEEL_UP) mWheel = 1;
                if (mActionHeld == WHEEL_DOWN) mWheel = -1;
            }
        }
        int mouse = mMouseLocked;
        if (down(buttons, 15) || (select && down(buttons, 12))) mouse |= 1;
        if (down(buttons, 16) || (select && down(buttons, 11))) mouse |= 2;
        if (down(buttons, 6) || down(buttons, 7) || (select && down(buttons, 14))) mouse |= 4;
        mPrevious = buttons;
        return packet(mouse, select);
    }

    private byte[] packet(int mouse, boolean scroll) {
        final byte[] packet = new byte[16];
        packet[0] = 1;
        for (int i = 0; i < 8; i++) if (mModifiers[i] != 0) packet[1] |= 1 << i;
        final byte[] keys = pack(mHeld);
        System.arraycopy(keys, 0, packet, 2, keys.length);
        packet[8] = (byte) mouse; packet[9] = (byte) (scroll ? 1 : 0); packet[10] = (byte) mWheel;
        packet[11] = (byte) (mArmed ? 1 : 0);
        return packet;
    }
    static byte[] pack(int... usages) {
        final byte[] keys = new byte[6];
        int count = 0;
        for (int usage : usages) {
            if (usage < 4 || usage > 0x65) continue;
            boolean duplicate = false;
            for (int i = 0; i < Math.min(count, 6); i++) duplicate |= keys[i] == (byte) usage;
            if (duplicate) continue;
            if (count == 6) { Arrays.fill(keys, (byte) 1); return keys; }
            keys[count++] = (byte) usage;
        }
        return keys;
    }

    String label(Key key) {
        final int u = key.usage;
        if ("de".equals(mLayout)) {
            if (u == 0x1c) return "Z";
            if (u == 0x1d) return "Y";
            switch (u) {
                case 0x2d: return "ß"; case 0x2e: return "´"; case 0x2f: return "Ü";
                case 0x30: return "+"; case 0x31: case 0x32: return "#"; case 0x33: return "Ö";
                case 0x34: return "Ä"; case 0x35: return "^"; case 0x38: return "-";
                case 0x64: return "<"; default: break;
            }
        }
        if ("fr".equals(mLayout)) {
            switch (u) {
                case 0x04: return "Q"; case 0x14: return "A"; case 0x1a: return "Z";
                case 0x1d: return "W"; case 0x10: return ","; case 0x33: return "M";
                case 0x1e: return "& 1"; case 0x1f: return "é 2"; case 0x20: return "\" 3";
                case 0x21: return "' 4"; case 0x22: return "( 5"; case 0x23: return "- 6";
                case 0x24: return "è 7"; case 0x25: return "_ 8"; case 0x26: return "ç 9";
                case 0x27: return "à 0"; case 0x2d: return ")"; case 0x2e: return "=";
                case 0x2f: return "^"; case 0x30: return "$"; case 0x31: case 0x32: return "*";
                case 0x34: return "ù"; case 0x35: return "²"; case 0x36: return ";";
                case 0x37: return ":"; case 0x38: return "!"; case 0x64: return "<"; default: break;
            }
        }
        return key.cap;
    }
}
