package org.andr36oid.gamecomfort;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, selected-only repairs through the existing serialized setup worker. */
public final class RetroArchRepairActivity extends Activity {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private final CheckBox[] choices = new CheckBox[5];
    private final int[] labels = {
            R.string.ra_repair_shader, R.string.ra_repair_preemptive,
            R.string.ra_repair_gambatte, R.string.ra_repair_nestopia,
            R.string.ra_repair_fceumm
    };
    private boolean waiting;
    private Button preview, restore;
    private TextView status;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        scroll.addView(body);
        TextView about = new TextView(this);
        about.setText(R.string.ra_repair_about);
        body.addView(about);
        int selected = saved == null ? 31 : saved.getInt("repairs", 31);
        int[] explanations = {
                R.string.ra_repair_shader_about, R.string.ra_repair_preemptive_about,
                R.string.ra_repair_gambatte_about, R.string.ra_repair_nestopia_about,
                R.string.ra_repair_fceumm_about
        };
        for (int i = 0; i < choices.length; i++) {
            CheckBox choice = new CheckBox(this);
            choices[i] = choice;
            choice.setText(labels[i]);
            choice.setChecked((selected & (1 << i)) != 0);
            body.addView(choice);
            TextView explanation = new TextView(this);
            explanation.setText(explanations[i]);
            body.addView(explanation);
            choice.setOnCheckedChangeListener((button, checked) -> updateControls());
        }
        preview = new Button(this);
        preview.setText(R.string.ra_repair_preview_selected);
        preview.setOnClickListener(v -> request("preview", selection()));
        body.addView(preview);
        restore = new Button(this);
        restore.setText(R.string.ra_repair_restore);
        restore.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.ra_repair_restore)
                .setMessage(R.string.ra_repair_restore_about)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ra_repair_restore, (dialog, which) -> request("restore", 0))
                .show());
        body.addView(restore);
        status = new TextView(this);
        body.addView(status);
        setContentView(scroll);
        updateControls();
        choices[0].requestFocus();
    }

    private int selection() {
        int mask = 0;
        for (int i = 0; i < choices.length; i++) if (choices[i].isChecked()) mask |= 1 << i;
        return mask;
    }

    private void updateControls() {
        for (CheckBox choice : choices) choice.setEnabled(!waiting);
        preview.setEnabled(!waiting && selection() != 0);
        restore.setEnabled(!waiting);
    }

    @Override public void onSaveInstanceState(Bundle out) {
        out.putInt("repairs", selection());
        super.onSaveInstanceState(out);
    }

    private void request(String operation, int mask) {
        if (!RUNNING.compareAndSet(false, true)) {
            status.setText(R.string.ra_repair_wait);
            return;
        }
        waiting = true;
        updateControls();
        status.setText(R.string.ra_repair_wait);
        new Thread(() -> {
            String result = "error";
            try {
                String token = Long.toString(SystemClock.elapsedRealtimeNanos());
                String request = operation.equals("restore") ? operation : operation + ":" + mask;
                SystemProperties.set("sys.andr36oid.ra_repair_request", token + ":" + request);
                SystemProperties.set("ctl.start", "gameconsole-ra-repair");
                long end = SystemClock.elapsedRealtime() + 120000;
                result = "timeout";
                while (SystemClock.elapsedRealtime() < end) {
                    String reply = SystemProperties.get("sys.andr36oid.ra_repair_result", "");
                    if (reply.startsWith(token + ":")) {
                        result = reply.substring(token.length() + 1);
                        break;
                    }
                    SystemClock.sleep(100);
                }
            } catch (RuntimeException e) {
                result = "error";
            } finally {
                RUNNING.set(false);
            }
            final String answer = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                waiting = false;
                updateControls();
                if (answer.startsWith("preview:")) {
                    int count;
                    try {
                        count = Integer.parseInt(answer.substring(8));
                    } catch (NumberFormatException e) {
                        status.setText(R.string.ra_repair_error);
                        return;
                    }
                    status.setText(R.string.ra_repair_ready);
                    StringBuilder selected = new StringBuilder();
                    for (int i = 0; i < choices.length; i++) if ((mask & (1 << i)) != 0) {
                        if (selected.length() > 0) selected.append('\n');
                        selected.append(getString(labels[i]));
                    }
                    AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                            .setTitle(R.string.ra_repair_preview)
                            .setMessage(getString(R.string.ra_repair_preview_body, count, selected.toString()))
                            .setNegativeButton(android.R.string.cancel, null);
                    // Apply the mask captured for THIS preview, even if choices change later.
                    if (count > 0) dialog.setPositiveButton(R.string.ra_repair_apply,
                            (d, which) -> request("apply", mask));
                    dialog.show();
                } else {
                    int message = R.string.ra_repair_error;
                    switch (answer) {
                        case "applied": message = R.string.ra_repair_applied; break;
                        case "restored": message = R.string.ra_repair_restored; break;
                        case "unchanged": message = R.string.ra_repair_unchanged; break;
                        case "busy": message = R.string.ra_repair_busy; break;
                        case "backup": message = R.string.ra_repair_backup; break;
                        case "timeout": message = R.string.ra_repair_timeout; break;
                    }
                    status.setText(message);
                }
            });
        }, "RetroArchRepair").start();
    }

    @Override public boolean onNavigateUp() {
        finish();
        return true;
    }
}
