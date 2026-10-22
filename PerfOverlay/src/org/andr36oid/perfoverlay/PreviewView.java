package org.andr36oid.perfoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * A strip of stand-in game screen above the settings with the overlay drawn at its real size
 * in the chosen spot, size and colours. It stays put while the settings scroll, so every
 * change shows right away. The readings are made up and the same every time.
 */
public class PreviewView extends FrameLayout
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private TextView mHud;

    public PreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHud = findViewById(R.id.preview_hud);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Hud.prefs(getContext()).registerOnSharedPreferenceChangeListener(this);
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        Hud.prefs(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        update();
    }

    private void update() {
        final HudFormat format = new HudFormat(getContext());
        format.style(mHud);
        mHud.setText(format.format(sample()));
        final LayoutParams lp = (LayoutParams) mHud.getLayoutParams();
        lp.gravity = format.gravity();
        // the overlay's distance from the screen edges
        final int edge = Math.round(4 * getResources().getDisplayMetrics().density);
        lp.setMargins(edge, edge, edge, edge);
        mHud.setLayoutParams(lp);
    }

    /** Typical readings of a game running well. */
    private Stats sample() {
        final Stats s = new Stats(getContext());
        s.fps = 60;
        s.cpuPercent = 42;
        s.cpuMhz = 1296;
        s.gpuMhz = 400;
        s.gpuPercent = 35;
        s.memMhz = 666;
        s.tempC = 52;
        s.batteryPercent = 78;
        s.batteryWatts = 2.4f;
        s.charging = false;
        s.ramPercent = 61;
        s.storagePercent = 3;
        return s;
    }
}
