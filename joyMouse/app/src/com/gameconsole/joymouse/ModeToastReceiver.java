package com.gameconsole.joymouse;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import java.util.Arrays;

/**
 * The joyMouse daemon broadcasts every mode change here; this turns it into a
 * toast that tells the user what just happened and how the controls work now.
 */
public class ModeToastReceiver extends BroadcastReceiver {
    static final String ACTION_MODE_CHANGED = "com.gameconsole.joymouse.action.MODE_CHANGED";

    private static Toast sToast;
    private static long sLastSequence = Long.MIN_VALUE;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_MODE_CHANGED.equals(intent.getAction())) return;

        // Broadcasts can overtake each other; never let an old state win.
        final long sequence = intent.getLongExtra("seq", 0);
        if (sequence < sLastSequence) return;
        sLastSequence = sequence;

        final ControlsText.Setup setup = new ControlsText.Setup();
        setup.pointerStick = extra(intent, "pointer", "right");
        setup.scrollStick = extra(intent, "scroll", "none");
        final String toggle = extra(intent, "toggle", "none");
        if (!"none".equals(toggle)) setup.toggle.addAll(Arrays.asList(toggle.split("\\+")));
        setup.holdMs = intent.getIntExtra("hold_ms", 1000);
        final String bindings = extra(intent, "bindings", "none");
        if (!"none".equals(bindings)) {
            for (String binding : bindings.split(",")) {
                final int eq = binding.indexOf('=');
                if (eq > 0) setup.bindings.put(binding.substring(0, eq), binding.substring(eq + 1));
            }
        }
        final boolean active = intent.getBooleanExtra("active", false);

        // Replace the previous toast instead of queueing behind it.
        if (sToast != null) sToast.cancel();
        sToast = Toast.makeText(context.getApplicationContext(),
                ControlsText.announcement(context, setup, active), Toast.LENGTH_LONG);
        sToast.show();
    }

    private static String extra(Intent intent, String name, String fallback) {
        final String value = intent.getStringExtra(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
