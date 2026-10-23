package org.andr36oid.usbmode;

import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

public final class TextInputTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("IME check " + checks); }
    public static void main(String[] args) {
        EditText field = new EditText();
        field.getContext().manager.served = field;
        check(field.getContext().manager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT));
        check(!field.getContext().manager.visible); // accepted does not mean the keyboard appeared
        field = new EditText();
        UsbTextInput.bind(field);
        check(field.showOnFocus && field.selectAll);
        check(!field.key.run(field, KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN)));
        check(!field.key.run(field, KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN)));
        check(field.key.run(field, KeyEvent.KEYCODE_BUTTON_A, new KeyEvent(KeyEvent.ACTION_DOWN)));
        check(field.getContext().manager.attempts == 0);
        check(field.key.run(field, KeyEvent.KEYCODE_BUTTON_A, new KeyEvent(KeyEvent.ACTION_UP)));
        check(field.getContext().manager.attempts == 0); // served editor is not ready in this dispatch
        field.getContext().manager.served = field;
        field.drain(); check(field.getContext().manager.visible);
        check(field.getContext().manager.lastFlags == InputMethodManager.SHOW_FORCED);
        check(field.selectionStart == 0 && field.selectionEnd == 6);
        UsbTextInput.hide(field); check(!field.getContext().manager.visible);
        field.selectionStart = 2; field.selectionEnd = 4;
        UsbTextInput.show(field); field.drain();
        check(field.selectionStart == 2 && field.selectionEnd == 4);
        field = new EditText(); UsbTextInput.show(field); field.next();
        check(!field.getContext().manager.visible);
        check(field.getContext().manager.lastFlags == InputMethodManager.SHOW_FORCED);
        field.getContext().manager.served = field; field.drain();
        check(field.getContext().manager.visible && field.getContext().manager.attempts == 2);
        for (int key : new int[] {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER}) {
            field = new EditText(); field.getContext().manager.served = field;
            // Activity/dialog dispatch uses this same handler before the EditText receives the key.
            check(UsbTextInput.activate(field, key, new KeyEvent(KeyEvent.ACTION_DOWN)));
            check(field.getContext().manager.attempts == 0);
            check(UsbTextInput.activate(field, key, new KeyEvent(KeyEvent.ACTION_UP)));
            field.drain(); check(field.getContext().manager.visible);
            check(field.getContext().manager.lastFlags == InputMethodManager.SHOW_FORCED);
            UsbTextInput.hide(field); check(!field.getContext().manager.visible);
        }
        field = new EditText(); UsbTextInput.bind(field); field.getContext().manager.served = field;
        field.click.run(field); field.drain();
        check(field.getContext().manager.visible && field.getContext().manager.lastFlags == InputMethodManager.SHOW_FORCED);
        field.detach(); check(!field.getContext().manager.visible);
        field = new EditText(); UsbTextInput.bind(field); UsbTextInput.show(field); field.detach(); field.drain();
        check(field.getContext().manager.attempts == 0);
        field = new EditText(); field.getContext().manager.served = field; UsbTextInput.show(field); field.drain();
        EditText other = new EditText(); other.getContext().manager.served = other; UsbTextInput.show(other); other.drain();
        UsbTextInput.hide(field.getContext());
        check(!field.getContext().manager.visible && other.getContext().manager.visible);
        UsbTextInput.hide(other.getContext()); check(!other.getContext().manager.visible);
        field = new EditText(); UsbTextInput.show(field); UsbTextInput.hide(field.getContext()); field.drain();
        check(field.getContext().manager.attempts == 0);
        for (int cancelled = 0; cancelled < 4; cancelled++) {
            field = new EditText(); field.getContext().manager.served = field; UsbTextInput.show(field);
            if (cancelled == 0) UsbTextInput.hide(field);
            if (cancelled == 1) field.focused = false;
            if (cancelled == 2) field.attached = false;
            if (cancelled == 3) field.windowFocused = false;
            field.drain(); check(field.getContext().manager.attempts == 0);
        }
        field = new EditText(); UsbTextInput.show(field); field.drain();
        check(field.getContext().manager.attempts == 3 && !field.getContext().manager.visible);
        field = new EditText(); field.getContext().manager.served = field;
        UsbTextInput.show(field); UsbTextInput.show(field); field.drain();
        check(field.getContext().manager.attempts == 1);
        field = new EditText(); field.getContext().manager = null; UsbTextInput.show(field); field.drain(); UsbTextInput.hide(field);
        check(field.key == null);
        System.out.println(checks + " system IME focus, activation, retry, cancellation and selection checks passed");
    }
}
