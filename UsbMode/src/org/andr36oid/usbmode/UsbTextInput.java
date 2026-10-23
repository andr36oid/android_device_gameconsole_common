package org.andr36oid.usbmode;

import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import java.util.WeakHashMap;

/** Form fields use the system IME, including the ROM's GamepadKeyboard. */
final class UsbTextInput {
    private static final WeakHashMap<EditText,Long> sRequests = new WeakHashMap<>();
    private static long sRequest;

    static void bind(EditText field) {
        field.setShowSoftInputOnFocus(true); field.setSelectAllOnFocus(true);
        field.setOnClickListener(v -> show(field));
        field.setOnKeyListener((v, key, event) -> {
            if (key != KeyEvent.KEYCODE_BUTTON_A && key != KeyEvent.KEYCODE_DPAD_CENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) show(field);
            return true;
        });
    }
    static void show(EditText field) {
        if (!field.requestFocus()) return;
        final long request = ++sRequest;
        sRequests.put(field, request);
        // The focused editor's InputConnection may not exist until the next UI turn.
        field.post(() -> show(field, request, 0));
    }
    private static void show(EditText field, long request, int attempt) {
        if (!Long.valueOf(request).equals(sRequests.get(field)) || !field.isAttachedToWindow()
                || !field.hasWindowFocus() || !field.isFocused()) return;
        InputMethodManager manager = field.getContext().getSystemService(InputMethodManager.class);
        if (manager != null && !manager.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) && attempt < 2)
            field.postDelayed(() -> show(field, request, attempt + 1), 50);
    }
    static void hide(EditText field) {
        sRequests.remove(field);
        InputMethodManager manager = field.getContext().getSystemService(InputMethodManager.class);
        if (manager != null) manager.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }
}
