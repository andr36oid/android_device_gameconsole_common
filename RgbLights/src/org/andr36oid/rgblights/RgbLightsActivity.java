package org.andr36oid.rgblights;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.andr36oid.hints.HintBar;

/**
 * Settings > Display > Stick lights. A settings-style screen: the action bar owns
 * the back arrow and title, {@link RgbView} draws the preview rings and the preset
 * strip plus the "Lights off" sleep row, and the shared {@link HintBar} shows the
 * key hints. Up/Down move between the two focus zones (preset strip / sleep row)
 * and Left/Right change the focused zone's value live - the preset through the
 * stickled.preset property and the sleep behaviour through stickled.sleep, both of
 * which init writes to the keros LED driver. B or Back leaves. There is no stick
 * preview - the old follow-stick mode is gone.
 */
public class RgbLightsActivity extends Activity {

    private RgbView mView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.rgb_title);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        final FrameLayout root = new FrameLayout(this);
        mView = new RgbView(this);
        root.addView(mView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        HintBar.attach(this, "up/dn", "Select", "< >", "Change", "B", "Back");
    }

    @Override
    protected void onResume() {
        super.onResume();
        mView.requestFocus();
        mView.start();
    }

    @Override
    protected void onPause() {
        mView.stop();
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        final boolean first = event.getRepeatCount() == 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                mView.moveZone(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                mView.moveZone(+1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                mView.step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                mView.step(+1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) {
                    mView.step(+1);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (first) {
                    finish();
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }
}
