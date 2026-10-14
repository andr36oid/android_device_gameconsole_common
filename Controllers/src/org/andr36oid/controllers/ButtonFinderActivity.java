package org.andr36oid.controllers;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Buttons on this console > Find an unknown button. Some consoles have a button no firmware
 * uses. The root helper andr36oid-gpio-scan reads every GPIO level (and ADC channel) for 2
 * seconds twice: once with nothing touched, once with the unknown button held. A pin that was
 * steady both times but at the other level is the button.
 *
 * <p>"Use it as a button" adds the pin to the Kernel Tweaks setting extra_buttons
 * (persist.andr36oid.kt.extra_buttons, e.g. "2A5,3C1H") and has the Kernel Tweaks helper
 * write it for the next start; the joypad driver then reports it as the next free extra
 * button. ADC channels that moved are only reported.
 */
public class ButtonFinderActivity extends Activity {

    private static final String SCAN_SERVICE = "andr36oid-gpio-scan";
    private static final String SCAN_PHASE = "sys.andr36oid.gscan.phase";
    private static final String SCAN_REQUEST = "sys.andr36oid.gscan.request";
    private static final String SCAN_RESULT = "sys.andr36oid.gscan.result";
    /** 20 readings 0.1 s apart, and time for a slow start of the helper */
    private static final long SCAN_WAIT_MS = 10000;

    private static final String KT_SERVICE = "andr36oid-ktweaks";
    private static final String EXTRA_PROP = "persist.andr36oid.kt.extra_buttons";
    private static final String KT_REQUEST = "sys.andr36oid.kt.request";
    private static final String KT_DONE = "sys.andr36oid.kt.done";
    private static final String KT_FAILED = "sys.andr36oid.kt.failed";
    private static final String KT_PAUSED = "persist.andr36oid.kt.paused";
    private static final long KT_WAIT_MS = 15000;

    private static final long POLL_MS = 200;
    private static final int COUNTDOWN = 3;
    /** the kernel takes 8 extra pins at most */
    private static final int MAX_EXTRA = 8;
    /** an ADC channel that moved this much (of 1023) with the button held */
    private static final int ADC_MOVED = 100;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private TextView mMessage;
    private TextView mDetails;
    private ProgressBar mProgress;
    private Button mPrimary;
    private Button mSecondary;
    /** while a scan runs every button is ignored, the held one may be a known button */
    private boolean mScanning;
    /** the pin "Use it as a button" adds, like "2A5" or "3C1H" */
    private String mUsable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);
        setContentView(R.layout.button_finder);
        mMessage = findViewById(R.id.message);
        mDetails = findViewById(R.id.details);
        mProgress = findViewById(R.id.progress);
        mPrimary = findViewById(R.id.primary);
        mSecondary = findViewById(R.id.secondary);
        showIntro();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return mScanning || super.dispatchKeyEvent(event);
    }

    // ---- Screens

    private void show(CharSequence message, CharSequence details, boolean busy) {
        mMessage.setText(message);
        mDetails.setText(details);
        mDetails.setVisibility(details == null ? View.GONE : View.VISIBLE);
        mProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        mPrimary.setVisibility(View.GONE);
        mSecondary.setVisibility(View.GONE);
    }

    private void button(Button b, int text, Runnable action) {
        b.setText(text);
        b.setOnClickListener(v -> action.run());
        b.setVisibility(View.VISIBLE);
    }

    private void showIntro() {
        mScanning = false;
        show(getString(R.string.finder_intro), null, false);
        button(mPrimary, R.string.finder_start, this::scanIdle);
        button(mSecondary, R.string.check_done, this::finish);
        mPrimary.requestFocus();
    }

    private void scanIdle() {
        mScanning = true;
        show(getString(R.string.finder_idle), null, true);
        // a moment for the hand that chose Start to leave the buttons
        mHandler.postDelayed(() -> scan("idle", ok -> {
            if (ok) countdown(COUNTDOWN);
        }), 1000);
    }

    private void countdown(int left) {
        if (left == 0) {
            show(getString(R.string.finder_held), null, true);
            scan("held", ok -> {
                if (ok) showResult();
            });
            return;
        }
        show(getString(R.string.finder_get_ready, left), null, false);
        mHandler.postDelayed(() -> countdown(left - 1), 1000);
    }

    /** runs the helper for a phase, then done(true) or an error screen and done(false) */
    private void scan(String phase, Consumer<Boolean> done) {
        final String request = Long.toString(SystemClock.elapsedRealtime());
        final long until = SystemClock.uptimeMillis() + SCAN_WAIT_MS;
        SystemProperties.set(SCAN_PHASE, phase);
        SystemProperties.set(SCAN_REQUEST, request);
        SystemProperties.set("ctl.start", SCAN_SERVICE);
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                final String result = SystemProperties.get(SCAN_RESULT, "");
                if (result.equals(request + " ok")) {
                    done.accept(true);
                } else if (result.startsWith(request + " ")) {
                    fail(result.endsWith(" missing") ? R.string.finder_missing
                            : R.string.finder_no_answer);
                    done.accept(false);
                } else if (SystemClock.uptimeMillis() > until) {
                    fail(R.string.finder_no_answer);
                    done.accept(false);
                } else {
                    mHandler.postDelayed(this, POLL_MS);
                }
            }
        }, POLL_MS);
    }

    private void fail(int message) {
        mScanning = false;
        show(getString(message), null, false);
        button(mPrimary, R.string.finder_again, this::scanIdle);
        button(mSecondary, R.string.check_done, this::finish);
        mPrimary.requestFocus();
    }

    // ---- Result

    private void showResult() {
        mScanning = false;
        mUsable = null;
        final PinScan.Result idle = PinScan.read("idle");
        final PinScan.Result held = PinScan.read("held");
        if (idle == null || held == null) {
            fail(R.string.finder_no_answer);
            return;
        }
        final PinScan.Tree tree = PinScan.tree(this);
        final List<String> saved = savedExtras();

        // pins steady in both scans, at the other level with the button held
        final List<int[]> found = new ArrayList<>();
        for (int b = 0; b < PinScan.BANKS; b++) {
            if (!idle.present[b] || !held.present[b]) continue;
            // outputs are left out: an LED or a motor pin can switch between the scans
            final long moved = ((idle.low[b] & held.high[b]) | (idle.high[b] & held.low[b]))
                    & ~idle.output[b] & ~held.output[b];
            for (int p = 0; p < PinScan.PINS; p++) {
                if ((moved >> p & 1) != 0) {
                    found.add(new int[] {b, p, (int) (idle.high[b] >> p & 1)});
                }
            }
        }

        final List<String> lines = new ArrayList<>();
        String message;
        if (found.size() == 1) {
            final int[] f = found.get(0);
            final String name = PinScan.pinName(f[0], f[1]);
            final String code = PinScan.pinCode(f[0], f[1]);
            final String known = tree.buttons.get(f[0] * PinScan.PINS + f[1]);
            final int mux = PinScan.mux(held, f[0], f[1]);
            if (known != null) {
                message = getString(R.string.finder_known_one, name, known);
            } else if (saved.contains(code) || saved.contains(code + "L")
                    || saved.contains(code + "H")) {
                message = getString(R.string.finder_already, name);
            } else if (mux != 0) {
                message = getString(R.string.finder_other_use, name);
            } else {
                message = getString(R.string.finder_found, name);
                // idle high: pressed pulls it low, the kernel's default
                mUsable = f[2] == 1 ? code : code + "H";
            }
        } else if (found.size() > 1) {
            for (int[] f : found) lines.add(describe(f, tree, held));
            message = getString(R.string.finder_several);
        } else {
            message = null;
        }

        // ADC channels steady with nothing touched that moved with the button held
        final List<String> adcLines = new ArrayList<>();
        for (Map.Entry<String, int[]> e : held.adc.entrySet()) {
            final int[] before = idle.adc.get(e.getKey());
            final int[] now = e.getValue();
            if (before == null || before[1] - before[0] > ADC_MOVED / 2) continue;
            final int from = (before[0] + before[1]) / 2;
            final int to = Math.abs(now[0] - from) > Math.abs(now[1] - from) ? now[0] : now[1];
            if (Math.abs(to - from) < ADC_MOVED) continue;
            final String channel = e.getKey().substring(e.getKey().lastIndexOf(' ') + 1);
            final String user = tree.adcUsers.get(e.getKey());
            adcLines.add(user == null
                    ? getString(R.string.finder_adc_item, channel, from, to)
                    : getString(R.string.finder_adc_item_used, channel, from, to, user));
        }

        if (message == null && adcLines.isEmpty()) message = getString(R.string.finder_nothing);
        if (!adcLines.isEmpty()) {
            if (!lines.isEmpty()) lines.add("");
            lines.add(getString(R.string.finder_adc));
            lines.addAll(adcLines);
            lines.add(getString(R.string.finder_adc_report));
            if (message == null) message = getString(R.string.finder_adc_only);
        }
        show(message, lines.isEmpty() ? null : String.join("\n", lines), false);
        if (mUsable != null) {
            button(mPrimary, R.string.finder_use, this::use);
            button(mSecondary, R.string.finder_again, this::scanIdle);
        } else {
            button(mPrimary, R.string.finder_again, this::scanIdle);
            button(mSecondary, R.string.check_done, this::finish);
        }
        mPrimary.requestFocus();
    }

    private String describe(int[] f, PinScan.Tree tree, PinScan.Result held) {
        final String name = PinScan.pinName(f[0], f[1]);
        final String known = tree.buttons.get(f[0] * PinScan.PINS + f[1]);
        if (known != null) return getString(R.string.finder_item_known, name, known);
        if (PinScan.mux(held, f[0], f[1]) != 0) {
            return getString(R.string.finder_item_other_use, name);
        }
        return name;
    }

    // ---- Use it as a button

    private static List<String> savedExtras() {
        final String v = SystemProperties.get(EXTRA_PROP, "");
        final List<String> out = new ArrayList<>();
        if (v.isEmpty() || v.equals("none")) return out;
        for (String s : v.split(",")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private void use() {
        final List<String> list = savedExtras();
        if (list.size() >= MAX_EXTRA) {
            show(getString(R.string.finder_full), null, false);
            button(mPrimary, R.string.check_done, this::finish);
            mPrimary.requestFocus();
            return;
        }
        list.add(mUsable);
        final int number = extraNumber(list);
        SystemProperties.set(EXTRA_PROP, String.join(",", list));
        show(getString(R.string.finder_saving), null, true);

        final String request = Long.toString(SystemClock.elapsedRealtime());
        final long until = SystemClock.uptimeMillis() + KT_WAIT_MS;
        SystemProperties.set(KT_REQUEST, request);
        SystemProperties.set("ctl.start", KT_SERVICE);
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                final boolean answered = request.equals(SystemProperties.get(KT_DONE, ""));
                if (!answered && SystemClock.uptimeMillis() < until) {
                    mHandler.postDelayed(this, POLL_MS);
                    return;
                }
                final String paused = SystemProperties.get(KT_PAUSED, "");
                final boolean failed = !answered || Arrays.asList(
                        SystemProperties.get(KT_FAILED, "").split(" ")).contains("boot");
                final String message;
                if (failed) {
                    message = getString(R.string.finder_save_failed);
                } else if ("1".equals(paused) || "crash".equals(paused)) {
                    message = getString(R.string.finder_added_paused, number);
                } else {
                    message = getString(R.string.finder_added, number);
                }
                show(message, null, false);
                button(mPrimary, R.string.check_done, ButtonFinderActivity.this::finish);
                mPrimary.requestFocus();
            }
        }, POLL_MS);
    }

    /**
     * The Extra button number the last pin of the list gets: the kernel gives the extra
     * pins, in order, the extra button codes the device tree's buttons don't use.
     */
    private int extraNumber(List<String> list) {
        final Set<Integer> used = new HashSet<>(PinScan.tree(this).joypadCodes);
        int code = ButtonSettings.SCAN_EXTRA_FIRST;
        for (int i = 0; i < list.size(); i++) {
            while (used.contains(code)) code++;
            if (i < list.size() - 1) code++;
        }
        return code - ButtonSettings.SCAN_EXTRA_FIRST + 1;
    }
}
