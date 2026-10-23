package org.andr36oid.usbmode;

import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/** Form fields use the system IME, including the ROM's GamepadKeyboard. */
final class UsbTextInput {
    static void bind(EditText field) {
        field.setShowSoftInputOnFocus(true);
        field.setOnClickListener(v -> show(field));
        field.setOnKeyListener((v, key, event) -> {
            if (key != KeyEvent.KEYCODE_BUTTON_A && key != KeyEvent.KEYCODE_DPAD_CENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) show(field);
            return true;
        });
    }
    static void show(EditText field) {
        field.requestFocus(); field.setSelection(field.length());
        field.getContext().getSystemService(InputMethodManager.class).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    }
    static void hide(EditText field) {
        field.getContext().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(field.getWindowToken(), 0);
    }
}
