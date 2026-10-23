package org.andr36oid.usbmode;

import android.app.AlertDialog;
import android.view.KeyEvent;
import android.widget.EditText;

public final class DialogInputTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Dialog input " + checks); }
    private static KeyEvent key(int action, int key) { return new KeyEvent(10, 20, action, key, 0); }
    public static void main(String[] args) {
        AlertDialog dialog = new AlertDialog(); UsbDialogInput.show(dialog);
        check(dialog.shown);
        check(dialog.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)));
        check(dialog.confirms == 0 && dialog.delivered.getAction() == KeyEvent.ACTION_DOWN);
        check(dialog.delivered.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER && dialog.delivered.getDownTime() == 10);
        check(dialog.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)));
        check(dialog.confirms == 1 && dialog.delivered.getEventTime() == 20);
        check(!dialog.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
        check(dialog.delivered.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT);
        check(dialog.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)) && !dialog.dismissed);
        check(dialog.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)) && dialog.dismissed);
        dialog = new AlertDialog(); EditText field = new EditText(); dialog.focus = field;
        field.getContext().manager.served = field; UsbDialogInput.show(dialog);
        check(dialog.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)));
        check(field.getContext().manager.attempts == 0);
        check(dialog.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)));
        field.drain(); check(field.getContext().manager.visible && dialog.confirms == 0);
        UsbTextInput.hide(field);
        check(dialog.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)));
        field.drain(); check(field.getContext().manager.visible && dialog.confirms == 0);
        System.out.println(checks + " dialog A/B remapping, navigation and system IME checks passed");
    }
}
