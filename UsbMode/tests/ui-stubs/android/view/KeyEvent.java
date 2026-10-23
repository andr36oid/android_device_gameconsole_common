package android.view;

public final class KeyEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, KEYCODE_BUTTON_A = 96, KEYCODE_DPAD_CENTER = 23;
    private final int action;
    public KeyEvent(int action) { this.action = action; }
    public int getAction() { return action; }
}
