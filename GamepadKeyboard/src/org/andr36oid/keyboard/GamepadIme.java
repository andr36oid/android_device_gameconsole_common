package org.andr36oid.keyboard;

import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.Toast;

import org.andr36oid.keyboard.core.Buttons;
import org.andr36oid.keyboard.core.KeyboardLogic;
import org.andr36oid.keyboard.core.Layouts;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The gamepad keyboard. All typing logic lives in core.KeyboardLogic; this class turns
 * buttons and stick moves into calls on it and carries out what it returns.
 */
public class GamepadIme extends InputMethodService {
    static final String PREFS = "settings";
    static final String PREF_LAYOUT = "layout";
    static final String PREF_STICKS = "start_sticks";
    static final String PREF_HINTS = "show_hints";
    static final String PREF_AUTO_BIG = "auto_big_text";

    private static final long COVER_CHECK_MS = 400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> consumedDown = new HashSet<>();

    private SharedPreferences prefs;
    private KeyboardView view;
    private KeyboardLogic logic;
    private String logicKey;

    // Big text line (fullscreen mode with the text above the keyboard)
    private boolean bigWanted;      // R2 turned it on for this field
    private boolean bigAuto;        // turned on because the field was hidden by the keyboard
    private boolean bigRefused;     // R2 turned it off, don't turn it on again automatically
    private float cursorBottom = Float.NaN;

    // Hat switches of external pads: handled here, because taking the motion event from
    // the app also stops Android from turning them into d-pad presses.
    private int hatX;
    private int hatY;

    private final Runnable stickTick = new Runnable() {
        @Override
        public void run() {
            if (logic == null) return;
            if (logic.tick(SystemClock.uptimeMillis())) redraw();
            scheduleTick();
        }
    };

    private final Runnable coverCheck = new Runnable() {
        @Override
        public void run() {
            checkCovered();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    public View onCreateInputView() {
        view = new KeyboardView(this);
        ensureLogic();
        view.setLogic(logic);
        return view;
    }

    private void ensureLogic() {
        Locale loc = subtypeLocale();
        String choice = prefs.getString(PREF_LAYOUT, Layouts.AUTO);
        String family = Layouts.resolve(choice, loc.getLanguage(), loc.getCountry());
        boolean sticks = prefs.getBoolean(PREF_STICKS, false);
        String key = family + "|" + loc.getLanguage() + "|" + sticks;
        if (logic == null || !key.equals(logicKey)) {
            logic = new KeyboardLogic(family, loc.getLanguage(), sticks);
            logicKey = key;
            if (view != null) view.setLogic(logic);
        }
        if (view != null) view.setShowHints(prefs.getBoolean(PREF_HINTS, true));
    }

    private Locale subtypeLocale() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        InputMethodSubtype st = imm == null ? null : imm.getCurrentInputMethodSubtype();
        if (st != null && !TextUtils.isEmpty(st.getLocale())) {
            String[] parts = st.getLocale().split("_");
            return new Locale(parts[0], parts.length > 1 ? parts[1] : "");
        }
        return getResources().getConfiguration().getLocales().get(0);
    }

    @Override
    protected void onCurrentInputMethodSubtypeChanged(InputMethodSubtype newSubtype) {
        ensureLogic();
        redraw();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        if (!restarting) {
            bigWanted = false;
            bigAuto = false;
            bigRefused = false;
            cursorBottom = Float.NaN;
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        ensureLogic();
        if (!restarting) logic.reset();
        consumedDown.clear();
        hatX = hatY = 0;
        updateAutoCaps();
        if (view != null) view.setEnterLabel(enterLabel(info));
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && prefs.getBoolean(PREF_AUTO_BIG, true)) {
            ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE
                    | InputConnection.CURSOR_UPDATE_MONITOR);
        }
        redraw();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        handler.removeCallbacks(stickTick);
        handler.removeCallbacks(coverCheck);
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.requestCursorUpdates(0);
    }

    private String enterLabel(EditorInfo info) {
        if (info == null || (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
            return "↵";
        }
        switch (info.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_GO: return getString(R.string.key_go);
            case EditorInfo.IME_ACTION_SEARCH: return getString(R.string.key_search);
            case EditorInfo.IME_ACTION_SEND: return getString(R.string.key_send);
            case EditorInfo.IME_ACTION_NEXT: return getString(R.string.key_next);
            case EditorInfo.IME_ACTION_DONE: return getString(R.string.key_done);
            case EditorInfo.IME_ACTION_PREVIOUS: return getString(R.string.key_previous);
            default: return "↵";
        }
    }

    // ---- big text line (fullscreen / extract mode) ----

    @Override
    public boolean onEvaluateFullscreenMode() {
        EditorInfo ei = getCurrentInputEditorInfo();
        if (ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0) {
            return false;
        }
        return bigWanted || bigAuto;
    }

    @Override
    public void onUpdateCursorAnchorInfo(CursorAnchorInfo info) {
        float x = info.getInsertionMarkerHorizontal();
        float y = info.getInsertionMarkerBottom();
        if (Float.isNaN(x) || Float.isNaN(y)) return;
        float[] pt = {x, y};
        Matrix m = info.getMatrix();
        if (m != null) m.mapPoints(pt);
        cursorBottom = pt[1];
        handler.removeCallbacks(coverCheck);
        handler.postDelayed(coverCheck, COVER_CHECK_MS);
    }

    /**
     * If the text cursor is still under the keyboard after the app had time to move its
     * window up, show the text above the keyboard instead (fullscreen mode).
     */
    private void checkCovered() {
        if (bigAuto || bigWanted || bigRefused || isFullscreenMode()) return;
        if (view == null || !isInputViewShown() || Float.isNaN(cursorBottom)) return;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        if (cursorBottom > loc[1] + 2) {
            bigAuto = true;
            updateFullscreenMode();
        }
    }

    private void toggleBigText() {
        if (isFullscreenMode()) {
            bigWanted = false;
            bigAuto = false;
            bigRefused = true;
        } else {
            bigWanted = true;
        }
        updateFullscreenMode();
    }

    // ---- input ----

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int action = Buttons.action(keyCode);
        if (action == Buttons.NONE || logic == null || !isInputViewShown()) {
            return super.onKeyDown(keyCode, event);
        }
        consumedDown.add(keyCode);
        apply(logic.down(action, event.getRepeatCount()));
        scheduleTick();
        redraw();
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!consumedDown.remove(keyCode)) return super.onKeyUp(keyCode, event);
        if (logic != null) {
            apply(logic.up(Buttons.action(keyCode), event.getEventTime() - event.getDownTime()));
            redraw();
        }
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (logic == null || !isInputViewShown()
                || !e.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || e.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(e);
        }
        long now = e.getEventTime();
        boolean changed = logic.leftStick(e.getAxisValue(MotionEvent.AXIS_X),
                e.getAxisValue(MotionEvent.AXIS_Y), now);
        List<KeyboardLogic.Effect> fx = logic.rightStick(e.getAxisValue(MotionEvent.AXIS_Z),
                e.getAxisValue(MotionEvent.AXIS_RZ));
        changed |= !fx.isEmpty();
        apply(fx);
        changed |= hat(e);
        scheduleTick();
        // The stick mode picture follows the sticks, so always redraw there.
        if (changed || logic.mode() == KeyboardLogic.MODE_STICKS) redraw();
        return true;
    }

    private boolean hat(MotionEvent e) {
        int hx = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_X));
        int hy = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_Y));
        boolean moved = false;
        if (hx != hatX) {
            hatX = hx;
            if (hx != 0) {
                apply(logic.down(hx < 0 ? Buttons.NAV_LEFT : Buttons.NAV_RIGHT, 0));
                moved = true;
            }
        }
        if (hy != hatY) {
            hatY = hy;
            if (hy != 0) {
                apply(logic.down(hy < 0 ? Buttons.NAV_UP : Buttons.NAV_DOWN, 0));
                moved = true;
            }
        }
        return moved;
    }

    private void scheduleTick() {
        handler.removeCallbacks(stickTick);
        long due = logic == null ? -1 : logic.nextTick();
        if (due >= 0) {
            handler.postDelayed(stickTick, Math.max(0, due - SystemClock.uptimeMillis()));
        }
    }

    private void redraw() {
        if (view != null) view.invalidate();
    }

    // ---- effects ----

    private void apply(List<KeyboardLogic.Effect> effects) {
        for (KeyboardLogic.Effect fx : effects) {
            switch (fx.type) {
                case KeyboardLogic.Effect.TEXT:
                    commit(fx.text);
                    break;
                case KeyboardLogic.Effect.BACKSPACE:
                    backspace(fx.fresh);
                    break;
                case KeyboardLogic.Effect.ENTER:
                    if (!sendDefaultEditorAction(true)) sendKeyChar('\n');
                    break;
                case KeyboardLogic.Effect.CURSOR_LEFT:
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT);
                    break;
                case KeyboardLogic.Effect.CURSOR_RIGHT:
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT);
                    break;
                case KeyboardLogic.Effect.HIDE:
                    requestHideSelf(0);
                    break;
                case KeyboardLogic.Effect.SWITCH_IME:
                    switchToOther();
                    break;
                case KeyboardLogic.Effect.BIG_TEXT:
                    toggleBigText();
                    break;
                default:
                    break;
            }
        }
    }

    private boolean isRawKeys() {
        EditorInfo ei = getCurrentInputEditorInfo();
        return ei == null || ei.inputType == InputType.TYPE_NULL;
    }

    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (isRawKeys()) {
            // Apps without a real text field (games, terminals) get key presses instead.
            for (int i = 0; i < s.length(); i++) sendKeyChar(s.charAt(i));
        } else {
            ic.commitText(s, 1);
        }
        updateAutoCaps();
    }

    private void backspace(boolean freshB) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (!isRawKeys()) {
            CharSequence sel = ic.getSelectedText(0);
            if (!TextUtils.isEmpty(sel)) {
                ic.commitText("", 1);
                updateAutoCaps();
                return;
            }
            if (freshB) {
                CharSequence before = ic.getTextBeforeCursor(1, 0);
                CharSequence after = ic.getTextAfterCursor(1, 0);
                if (before != null && after != null && before.length() == 0
                        && after.length() == 0) {
                    // B on an empty field: nothing to delete, so B means "back".
                    requestHideSelf(0);
                    return;
                }
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        updateAutoCaps();
    }

    private void updateAutoCaps() {
        if (logic == null) return;
        InputConnection ic = getCurrentInputConnection();
        EditorInfo ei = getCurrentInputEditorInfo();
        if (ic == null || ei == null || ei.inputType == InputType.TYPE_NULL) {
            logic.autoCaps(false);
            return;
        }
        logic.autoCaps(ic.getCursorCapsMode(ei.inputType) != 0);
    }

    private void switchToOther() {
        if (!ImeSwitch.leanbackInstalled(this)) {
            Toast.makeText(this, R.string.no_other_keyboard, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ImeSwitch.ensureEnabled(this, ImeSwitch.LEANBACK);
            switchInputMethod(ImeSwitch.LEANBACK);
        } catch (RuntimeException e) {
            // Not allowed or not known yet: fall back to the setting Android watches.
            ImeSwitch.makeDefault(this, ImeSwitch.LEANBACK);
        }
        Toast.makeText(this, R.string.switched_to_tv, Toast.LENGTH_LONG).show();
    }
}
