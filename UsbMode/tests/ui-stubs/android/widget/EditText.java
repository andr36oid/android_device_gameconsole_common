package android.widget;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import java.util.ArrayDeque;

public final class EditText extends View {
    public interface Click { void run(EditText field); }
    public interface Key { boolean run(EditText field, int key, KeyEvent event); }
    private final Context context = new Context();
    private final ArrayDeque<Runnable> posted = new ArrayDeque<>();
    public boolean attached = true, windowFocused = true, focused, showOnFocus, selectAll;
    public int selectionStart = 2, selectionEnd = 4;
    public Click click;
    public Key key;
    private OnAttachStateChangeListener attach;
    public void setShowSoftInputOnFocus(boolean enabled) { showOnFocus = enabled; }
    public void setSelectAllOnFocus(boolean enabled) { selectAll = enabled; }
    public void setOnClickListener(Click listener) { click = listener; }
    public void setOnKeyListener(Key listener) { key = listener; }
    public void addOnAttachStateChangeListener(OnAttachStateChangeListener listener) { attach = listener; }
    public void detach() { attached = false; if (attach != null) attach.onViewDetachedFromWindow(this); }
    public boolean requestFocus() {
        if (!focused && selectAll) { selectionStart = 0; selectionEnd = length(); }
        focused = true; return true;
    }
    public int length() { return 6; }
    public void setSelection(int position) { selectionStart = selectionEnd = position; }
    public Context getContext() { return context; }
    public boolean isAttachedToWindow() { return attached; }
    public boolean hasWindowFocus() { return windowFocused; }
    public boolean isFocused() { return focused; }
    public Object getWindowToken() { return this; }
    public boolean post(Runnable action) { posted.add(action); return true; }
    public boolean postDelayed(Runnable action, long delay) { posted.add(action); return true; }
    public void next() { posted.remove().run(); }
    public void drain() {
        int count = 0;
        while (!posted.isEmpty()) { if (++count > 20) throw new AssertionError("Unbounded IME retries"); next(); }
    }
}
