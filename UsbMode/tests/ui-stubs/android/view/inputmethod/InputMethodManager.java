package android.view.inputmethod;

import android.widget.EditText;

public final class InputMethodManager {
    public static final int SHOW_IMPLICIT = 1, SHOW_FORCED = 2;
    public EditText served;
    public int attempts, lastFlags;
    public boolean visible, suppressImplicit = true;
    public boolean showSoftInput(EditText field, int flags) {
        attempts++; lastFlags = flags;
        // Android may accept a request even when the IME declines to show its window.
        boolean accepted = field == served;
        visible = accepted && (!suppressImplicit || (flags & SHOW_IMPLICIT) == 0);
        return accepted;
    }
    public boolean hideSoftInputFromWindow(Object token, int flags) { visible = false; return true; }
}
