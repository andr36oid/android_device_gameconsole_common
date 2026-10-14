package org.andr36oid.playtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.TextView;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * Covers the screen when the play time is up. Only the PIN gets past it: then a parent
 * picks more time or a fresh allowance. The allowed apps can still be opened from it. ParentalService brings it back whenever something
 * else comes in front of it; the power button still puts the console to sleep.
 */
public class TimeUpActivity extends Activity implements PinPad.Listener {

    /** seen by ParentalService, which starts it again when it isn't in front */
    static volatile boolean sShown;

    private PinPad mPad;
    private TextView mText;
    private CharSequence mBaseText;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mCountdown = new Runnable() {
        @Override
        public void run() {
            final long at = ParentalService.sShutdownAt;
            if (at > 0) {
                final long s = Math.max(0, (at - SystemClock.elapsedRealtime() + 999) / 1000);
                mText.setText(mBaseText + "\n\n" + getString(R.string.parental_shutdown_in,
                        String.format("%d:%02d", s / 60, s % 60)));
            } else {
                mText.setText(mBaseText);
            }
            mHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mPad = new PinPad(this, false, this);
        final PinScreen screen = new PinScreen(this, mPad);
        screen.title.setText(R.string.parental_up_title);
        final int hours = Parental.refillHours(this);
        mText = screen.text;
        mBaseText = hours > 0
                ? getResources().getQuantityString(R.plurals.parental_up_text_rest, hours, hours)
                : getString(R.string.parental_up_text_pin);
        mText.setText(mBaseText);
        addAllowedApps(screen);
        mPad.focus();
    }

    /** A button for each allowed app, e.g. learning apps, which still open. */
    private void addAllowedApps(PinScreen screen) {
        final PackageManager pm = getPackageManager();
        boolean first = true;
        for (String pkg : Parental.allowedApps(this)) {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            final Intent open = launch;
            if (first) {
                final TextView still = new TextView(this);
                still.setText(R.string.parental_still_open);
                still.setPadding(0, Math.round(16 * getResources().getDisplayMetrics().density),
                        0, 0);
                screen.left.addView(still);
                first = false;
            }
            final Button b = new Button(this);
            try {
                b.setText(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            b.setAllCaps(false);
            b.setOnClickListener(v -> startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            screen.left.addView(b);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sShown = true;
        mHandler.post(mCountdown);
        if (Parental.leftMs(this) > 0 || !Parental.isOn(this)) finish();
    }

    @Override
    protected void onPause() {
        sShown = false;
        mHandler.removeCallbacks(mCountdown);
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return mPad.handleKey(event) || super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // only the PIN gets past this page
    }

    @Override
    public void onPin(String pin) {
        if (!Parental.checkPin(this, pin)) {
            final int wait = Parental.waitSeconds();
            mPad.setMessage(wait > 0 ? getString(R.string.pin_wait, wait)
                    : getString(R.string.pin_wrong));
            return;
        }
        showTimeChoices(this, this::finish);
    }

    @Override
    public void onCancel() {
    }

    /** More time or a fresh allowance, after the PIN; done runs once one was picked. */
    static void showTimeChoices(Context context, Runnable done) {
        final int[] minutes = Parental.EXTRA_MINUTES;
        final CharSequence[] items = new CharSequence[minutes.length + 1];
        for (int i = 0; i < minutes.length; i++) {
            items[i] = context.getString(R.string.parental_add,
                    Usage.format(context, minutes[i] * 60_000L));
        }
        items[minutes.length] = context.getString(R.string.parental_start_over,
                Usage.format(context, Parental.limitMinutes(context) * 60_000L));
        new AlertDialog.Builder(context)
                .setTitle(R.string.parental_more_time)
                .setItems(items, (d, which) -> {
                    if (which < minutes.length) {
                        Parental.addMinutes(context, minutes[which]);
                    } else {
                        Parental.refill(context);
                    }
                    done.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
