package android.view.inputmethod;

import android.widget.EditText;

public final class InputMethodManager {
    public static final int SHOW_IMPLICIT = 1;
    public EditText served;
    public int attempts;
    public boolean visible;
    public boolean showSoftInput(EditText field, int flags) {
        attempts++;
        visible = field == served;
        return visible;
    }
    public boolean hideSoftInputFromWindow(Object token, int flags) { visible = false; return true; }
}
