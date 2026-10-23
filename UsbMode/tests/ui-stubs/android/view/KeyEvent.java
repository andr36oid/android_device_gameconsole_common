package android.view;

public final class KeyEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1, KEYCODE_BUTTON_A = 96, KEYCODE_BUTTON_B = 97,
            KEYCODE_DPAD_CENTER = 23, KEYCODE_DPAD_LEFT = 21, KEYCODE_DPAD_RIGHT = 22;
    private final int action, key, repeat;
    private final long downTime, eventTime;
    public KeyEvent(int action) { this(0, 0, action, 0, 0); }
    public KeyEvent(long downTime, long eventTime, int action, int key, int repeat) {
        this.downTime = downTime; this.eventTime = eventTime; this.action = action; this.key = key; this.repeat = repeat;
    }
    public int getAction() { return action; }
    public int getKeyCode() { return key; }
    public int getRepeatCount() { return repeat; }
    public long getDownTime() { return downTime; }
    public long getEventTime() { return eventTime; }
}
