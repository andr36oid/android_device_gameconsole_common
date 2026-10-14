package org.andr36oid.playtime;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * Asks for the PIN (MODE_CHECK) or for a new one, typed twice (MODE_NEW). RESULT_OK when
 * it was right or is set.
 */
public class PinActivity extends Activity implements PinPad.Listener {

    static final String EXTRA_MODE = "mode";
    static final int MODE_CHECK = 0;
    static final int MODE_NEW = 1;

    private PinPad mPad;
    private PinScreen mScreen;
    private int mMode;
    private String mFirst;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMode = getIntent().getIntExtra(EXTRA_MODE, MODE_CHECK);
        mPad = new PinPad(this, true, this);
        mScreen = new PinScreen(this, mPad);
        if (mMode == MODE_NEW) {
            mScreen.title.setText(R.string.pin_new_title);
            mScreen.text.setText(R.string.pin_new_text);
        } else {
            mScreen.title.setText(R.string.pin_check_title);
            mScreen.text.setText(R.string.pin_check_text);
        }
        mPad.focus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return mPad.handleKey(event) || super.dispatchKeyEvent(event);
    }

    @Override
    public void onPin(String pin) {
        if (mMode == MODE_CHECK) {
            if (Parental.checkPin(this, pin)) {
                setResult(RESULT_OK);
                finish();
                return;
            }
            final int wait = Parental.waitSeconds();
            mPad.setMessage(wait > 0 ? getString(R.string.pin_wait, wait)
                    : getString(R.string.pin_wrong));
            return;
        }
        if (mFirst == null) {
            mFirst = pin;
            mScreen.title.setText(R.string.pin_again_title);
            mPad.setMessage("");
        } else if (mFirst.equals(pin)) {
            Parental.setPin(this, pin);
            setResult(RESULT_OK);
            finish();
        } else {
            mFirst = null;
            mScreen.title.setText(R.string.pin_new_title);
            mPad.setMessage(getString(R.string.pin_mismatch));
        }
    }

    @Override
    public void onCancel() {
        setResult(RESULT_CANCELED);
        finish();
    }
}
