package org.andr36oid.blocks;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.andr36oid.hints.HintBar;

/**
 * The falling-blocks game, a stand-alone full-screen app for the launcher. The whole game is
 * drawn by {@link GameView}; this activity only hosts it, attaches the shared {@link HintBar}
 * and routes the D-pad and gamepad keys into the view. There is no action bar - the view owns
 * every screen (mode menu, play, pause, game over).
 */
public class BlocksActivity extends Activity {

    private GameView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        view = new GameView(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        HintBar bar = HintBar.attach(this);
        view.setHintBar(bar);
        view.setOnExit(new Runnable() {
            @Override public void run() { finish(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.requestFocus();
        view.start();
    }

    @Override
    protected void onPause() {
        view.stop();          // drops the loop and pauses a live game
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP) {
            return true;
        }
        // The view owns Back on every screen (pause / resume / menu / exit), so it never
        // bubbles up to finish the activity mid-game.
        return view.key(code, down, event.getRepeatCount()) || super.dispatchKeyEvent(event);
    }
}
