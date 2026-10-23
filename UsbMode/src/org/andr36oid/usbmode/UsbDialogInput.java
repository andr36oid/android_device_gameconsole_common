package org.andr36oid.usbmode;

import android.app.AlertDialog;
import android.view.KeyEvent;
import android.widget.EditText;

/** Dialogs have their own window and receive gamepad keys before the activity. */
final class UsbDialogInput {
    static void show(AlertDialog dialog) {
        dialog.setOnKeyListener((d, key, event) -> {
            if (key == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_UP) dialog.dismiss();
                return true;
            }
            if (dialog.getCurrentFocus() instanceof EditText && UsbTextInput.activate((EditText) dialog.getCurrentFocus(), key, event)) return true;
            if (key == KeyEvent.KEYCODE_BUTTON_A) {
                dialog.dispatchKeyEvent(new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                        KeyEvent.KEYCODE_DPAD_CENTER, event.getRepeatCount())); return true;
            }
            return false;
        });
        dialog.show();
    }
}
