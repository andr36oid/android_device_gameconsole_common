package android.app;

import android.view.KeyEvent;
import android.view.View;

public final class AlertDialog {
    public interface Key { boolean run(AlertDialog dialog, int key, KeyEvent event); }
    public View focus;
    public boolean shown, dismissed;
    public int confirms;
    public KeyEvent delivered;
    private Key listener;
    public void setOnKeyListener(Key listener) { this.listener = listener; }
    public View getCurrentFocus() { return focus; }
    public void show() { if (listener == null) throw new AssertionError("Missing gamepad listener"); shown = true; }
    public void dismiss() { dismissed = true; }
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (listener.run(this, event.getKeyCode(), event)) return true;
        delivered = event;
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER && event.getAction() == KeyEvent.ACTION_UP) confirms++;
        return false;
    }
}
