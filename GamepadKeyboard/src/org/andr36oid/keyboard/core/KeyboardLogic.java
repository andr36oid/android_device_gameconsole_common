package org.andr36oid.keyboard.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the keyboard does with button presses and stick moves, without Android. The
 * input method service feeds it events and carries out the returned effects.
 */
public final class KeyboardLogic {
    public static final int MODE_GRID = 0;
    public static final int MODE_STICKS = 1;

    public static final int SHIFT_OFF = 0;
    public static final int SHIFT_ONCE = 1;
    public static final int SHIFT_LOCK = 2;

    /** Something for the service to do. */
    public static final class Effect {
        public static final int TEXT = 1;
        public static final int BACKSPACE = 2;   // arg true: fresh B press (may close on empty)
        public static final int ENTER = 3;
        public static final int CURSOR_LEFT = 4;
        public static final int CURSOR_RIGHT = 5;
        public static final int HIDE = 6;
        public static final int SWITCH_IME = 7;
        public static final int BIG_TEXT = 8;

        public final int type;
        public final String text;
        public final boolean fresh;

        Effect(int type, String text, boolean fresh) {
            this.type = type;
            this.text = text;
            this.fresh = fresh;
        }

        @Override
        public String toString() {
            switch (type) {
                case TEXT: return "TEXT(" + text + ")";
                case BACKSPACE: return fresh ? "BACKSPACE!" : "BACKSPACE";
                case ENTER: return "ENTER";
                case CURSOR_LEFT: return "CURSOR_LEFT";
                case CURSOR_RIGHT: return "CURSOR_RIGHT";
                case HIDE: return "HIDE";
                case SWITCH_IME: return "SWITCH_IME";
                case BIG_TEXT: return "BIG_TEXT";
                default: return "?";
            }
        }
    }

    private final Page letters;
    private final Page symbols;
    private final GridNav nav;
    private final Radial radial = new Radial();
    private final StickRepeat stick = new StickRepeat();
    private final String language;

    private int mode = MODE_GRID;
    private int shift = SHIFT_OFF;
    private boolean shiftByAuto;       // SHIFT_ONCE came from autoCaps, not from Y
    private boolean onSymbols;

    // Long press of A for accents
    private boolean aPending;          // A is down on a key with accents, not typed yet
    private List<String> variants;     // open accent strip, or null
    private int variantIndex;
    private boolean swallowAUp;        // the A release that opened the strip types nothing

    // Stick clicks (L3/R3) act on release unless both were held (joyMouse's mouse combo)
    private boolean thumbL;
    private boolean thumbR;
    private boolean thumbCancelled;

    private final List<Effect> out = new ArrayList<>();

    public KeyboardLogic(String family, String language, boolean startInSticks) {
        letters = Layouts.letters(family);
        symbols = Layouts.symbols();
        nav = new GridNav(letters);
        this.language = language;
        mode = startInSticks ? MODE_STICKS : MODE_GRID;
    }

    // ---- state for drawing ----

    public int mode() { return mode; }
    public int shift() { return shift; }
    public boolean shifted() { return shift != SHIFT_OFF; }
    public boolean onSymbols() { return onSymbols; }
    public GridNav nav() { return nav; }
    public Radial radial() { return radial; }
    public List<String> variants() { return variants; }
    public int variantIndex() { return variantIndex; }
    public Page page() { return nav.page(); }

    /** New text field: back to letters, the middle of the grid, no accent strip. */
    public void reset() {
        onSymbols = false;
        nav.setPage(letters);
        nav.home();
        radial.setSymbols(false);
        radial.reset();
        stick.reset();
        closeVariants();
        aPending = false;
        thumbL = thumbR = thumbCancelled = false;
        if (shift != SHIFT_LOCK) shift = SHIFT_OFF;
        shiftByAuto = false;
    }

    /** Capitalise the next letter or not, as the text field asks (never undoes caps lock). */
    public void autoCaps(boolean capsNeeded) {
        if (shift == SHIFT_LOCK) return;
        if (capsNeeded) {
            if (shift == SHIFT_OFF) shiftByAuto = true;
            shift = SHIFT_ONCE;
        } else if (shift == SHIFT_ONCE && shiftByAuto) {
            shift = SHIFT_OFF;
            shiftByAuto = false;
        }
    }

    // ---- buttons ----

    /** A button went down. repeat is the key repeat count (0 = fresh press). */
    public List<Effect> down(int action, int repeat) {
        out.clear();
        if (action == Buttons.SWITCH_IME || action == Buttons.STICK_MODE) {
            if (repeat == 0) {
                if (action == Buttons.SWITCH_IME) thumbL = true; else thumbR = true;
                if (thumbL && thumbR) thumbCancelled = true;
            } else if (repeat > 2) {
                thumbCancelled = true; // held: someone is doing a combo, not a click
            }
            return out;
        }
        if (variants != null && handleVariants(action, repeat)) return out;

        switch (action) {
            case Buttons.NAV_UP:
            case Buttons.NAV_DOWN:
            case Buttons.NAV_LEFT:
            case Buttons.NAV_RIGHT:
                aPending = false;
                navigate(action);
                break;
            case Buttons.PRESS:
                press(repeat);
                break;
            case Buttons.BACKSPACE:
                out.add(new Effect(Effect.BACKSPACE, null, repeat == 0));
                break;
            case Buttons.SPACE:
                type(" ");
                break;
            case Buttons.SHIFT:
                if (repeat == 0) cycleShift();
                break;
            case Buttons.CURSOR_LEFT:
                out.add(new Effect(Effect.CURSOR_LEFT, null, repeat == 0));
                break;
            case Buttons.CURSOR_RIGHT:
                out.add(new Effect(Effect.CURSOR_RIGHT, null, repeat == 0));
                break;
            case Buttons.HIDE:
                if (repeat == 0) out.add(new Effect(Effect.HIDE, null, true));
                break;
            case Buttons.BIG_TEXT:
                if (repeat == 0) out.add(new Effect(Effect.BIG_TEXT, null, true));
                break;
            case Buttons.ENTER:
                if (repeat == 0) out.add(new Effect(Effect.ENTER, null, true));
                break;
            case Buttons.PAGE:
                if (repeat == 0) togglePage();
                break;
            default:
                break;
        }
        return out;
    }

    /** A held A this long opens the accents even if the system sends no key repeats. */
    public static final long LONG_PRESS_MS = 450;

    /** A button came up after being held for heldMs. */
    public List<Effect> up(int action, long heldMs) {
        out.clear();
        if (action == Buttons.SWITCH_IME || action == Buttons.STICK_MODE) {
            boolean wasDown = action == Buttons.SWITCH_IME ? thumbL : thumbR;
            if (action == Buttons.SWITCH_IME) thumbL = false; else thumbR = false;
            if (wasDown && !thumbCancelled) {
                if (action == Buttons.SWITCH_IME) {
                    out.add(new Effect(Effect.SWITCH_IME, null, true));
                } else {
                    toggleMode();
                }
            }
            if (!thumbL && !thumbR) thumbCancelled = false;
            return out;
        }
        if (action == Buttons.PRESS) {
            if (swallowAUp) {
                swallowAUp = false;
            } else if (aPending) {
                aPending = false;
                Key k = nav.current();
                if (heldMs >= LONG_PRESS_MS) {
                    openVariants(k);
                    swallowAUp = false;
                } else {
                    typeKey(k);
                }
            }
        }
        return out;
    }

    private void press(int repeat) {
        if (mode == MODE_STICKS) {
            if (repeat == 0) {
                String s = radial.pressA();
                if (s != null) type(caseFor(s));
            }
            return;
        }
        Key k = nav.current();
        boolean accented = k.code == Key.CHAR && Accents.has(k.text);
        if (repeat == 0) {
            if (accented) {
                aPending = true; // typed on release, or the accents open if held
            } else {
                typeKey(k);
            }
            return;
        }
        if (accented) {
            if (aPending) {
                aPending = false;
                openVariants(k);
                swallowAUp = true;
            }
        } else if (k.code == Key.CHAR || k.code == Key.SPACE || k.code == Key.DELETE) {
            typeKey(k); // held: repeat like a real keyboard
        }
    }

    /** Returns true if the action was used by the accent strip. */
    private boolean handleVariants(int action, int repeat) {
        switch (action) {
            case Buttons.NAV_LEFT:
                variantIndex = (variantIndex - 1 + variants.size()) % variants.size();
                return true;
            case Buttons.NAV_RIGHT:
                variantIndex = (variantIndex + 1) % variants.size();
                return true;
            case Buttons.PRESS:
                if (repeat == 0) {
                    String s = variants.get(variantIndex);
                    closeVariants();
                    type(s);
                    if (shift == SHIFT_ONCE) shift = SHIFT_OFF;
                    swallowAUp = true;
                }
                return true;
            case Buttons.BACKSPACE:
            case Buttons.NAV_UP:
            case Buttons.NAV_DOWN:
                closeVariants();
                return true;
            default:
                closeVariants();
                return false;
        }
    }

    private void openVariants(Key k) {
        variants = Accents.variants(k.text, language, shifted());
        variantIndex = variants.size() > 1 ? 1 : 0;
    }

    private void closeVariants() {
        variants = null;
        variantIndex = 0;
    }

    private void navigate(int action) {
        if (mode == MODE_STICKS) {
            if (action == Buttons.NAV_LEFT) radial.rotate(-1);
            if (action == Buttons.NAV_RIGHT) radial.rotate(1);
            if (action == Buttons.NAV_UP) radial.rotate(-2);
            if (action == Buttons.NAV_DOWN) radial.rotate(2);
            return;
        }
        nav.move(toDir(action));
    }

    private static int toDir(int action) {
        switch (action) {
            case Buttons.NAV_UP: return GridNav.UP;
            case Buttons.NAV_DOWN: return GridNav.DOWN;
            case Buttons.NAV_LEFT: return GridNav.LEFT;
            default: return GridNav.RIGHT;
        }
    }

    private void typeKey(Key k) {
        switch (k.code) {
            case Key.CHAR:
                type(k.output(shifted()));
                if (k.isLetter() && shift == SHIFT_ONCE) shift = SHIFT_OFF;
                break;
            case Key.SHIFT:
                cycleShift();
                break;
            case Key.PAGE:
                togglePage();
                break;
            case Key.SPACE:
                type(" ");
                break;
            case Key.DELETE:
                out.add(new Effect(Effect.BACKSPACE, null, false));
                break;
            case Key.ENTER:
                out.add(new Effect(Effect.ENTER, null, true));
                break;
            case Key.SWITCH_IME:
                out.add(new Effect(Effect.SWITCH_IME, null, true));
                break;
            default:
                break;
        }
    }

    private void type(String s) {
        if (s != null && !s.isEmpty()) out.add(new Effect(Effect.TEXT, s, true));
    }

    private String caseFor(String s) {
        if (s.length() == 1 && Character.isLetter(s.charAt(0)) && shifted()) {
            if (shift == SHIFT_ONCE) shift = SHIFT_OFF;
            return s.toUpperCase(java.util.Locale.ROOT);
        }
        return s;
    }

    private void cycleShift() {
        shiftByAuto = false;
        shift = (shift + 1) % 3;
    }

    private void togglePage() {
        onSymbols = !onSymbols;
        nav.setPage(onSymbols ? symbols : letters);
        radial.setSymbols(onSymbols);
        closeVariants();
    }

    private void toggleMode() {
        mode = mode == MODE_GRID ? MODE_STICKS : MODE_GRID;
        closeVariants();
        aPending = false;
        stick.reset();
    }

    // ---- sticks ----

    /** Left stick: moves the highlight in the grid, picks the group in stick mode. */
    public boolean leftStick(float x, float y, long now) {
        if (mode == MODE_STICKS) return radial.left(x, y);
        int d = stick.update(x, y, now);
        if (d < 0) return false;
        stepFromStick(d);
        return true;
    }

    /** Right stick: types in stick mode (it also switches to stick mode when pushed). */
    public List<Effect> rightStick(float x, float y) {
        out.clear();
        if (mode != MODE_STICKS) {
            if (Math.max(Math.abs(x), Math.abs(y)) < Radial.PICK) return out;
            // Pushing the right stick in the grid is a natural way to ask for stick typing.
            toggleMode();
        }
        String s = radial.right(x, y);
        if (s != null) type(caseFor(s));
        return out;
    }

    /** Timer while the left stick is held in the grid. Returns true if the highlight moved. */
    public boolean tick(long now) {
        if (mode != MODE_GRID) return false;
        int d = stick.tick(now);
        if (d < 0) return false;
        stepFromStick(d);
        return true;
    }

    /** When the stick timer should fire next, or -1 if the stick is not held. */
    public long nextTick() {
        return mode == MODE_GRID && stick.direction() >= 0 ? stick.nextAt() : -1;
    }

    private void stepFromStick(int dir) {
        if (variants != null) {
            if (dir == GridNav.LEFT || dir == GridNav.RIGHT) {
                handleVariants(dir == GridNav.LEFT ? Buttons.NAV_LEFT : Buttons.NAV_RIGHT, 0);
            } else {
                closeVariants();
            }
            return;
        }
        aPending = false;
        nav.move(dir);
    }
}
