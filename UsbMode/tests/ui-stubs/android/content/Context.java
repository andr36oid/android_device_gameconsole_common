package android.content;

import android.view.inputmethod.InputMethodManager;

public final class Context {
    public InputMethodManager manager = new InputMethodManager();
    public <T> T getSystemService(Class<T> type) { return type.cast(manager); }
}
