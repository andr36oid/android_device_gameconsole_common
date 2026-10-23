package org.andr36oid.usbmode;

import android.view.KeyEvent;
import android.widget.EditText;

public final class TextInputTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("IME check " + checks); }
    public static void main(String[] args) {
        EditText field = new EditText();
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
        check(field.selectionStart == 0 && field.selectionEnd == 6);
        UsbTextInput.hide(field); check(!field.getContext().manager.visible);
        field.selectionStart = 2; field.selectionEnd = 4;
        UsbTextInput.show(field); field.drain();
        check(field.selectionStart == 2 && field.selectionEnd == 4);
        field = new EditText(); UsbTextInput.show(field); field.next();
        check(!field.getContext().manager.visible);
        field.getContext().manager.served = field; field.drain();
        check(field.getContext().manager.visible && field.getContext().manager.attempts == 2);
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
