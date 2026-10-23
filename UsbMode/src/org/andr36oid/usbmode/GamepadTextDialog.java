package org.andr36oid.usbmode;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class GamepadTextDialog {
    interface Result { void finished(String value); }

    static AlertDialog create(Context context, String title, String initial, boolean numeric, Result result) {
        final StringBuilder value = new StringBuilder(initial);
        final LinearLayout form = new LinearLayout(context); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        final TextView preview = new TextView(context); preview.setText(initial); preview.setTextSize(20); form.addView(preview);
        final List<String> caps = new ArrayList<>();
        String characters = numeric ? "0123456789" : "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-";
        for (int i = 0; i < characters.length(); i++) caps.add(characters.substring(i, i + 1));
        if (!numeric) caps.add(context.getString(R.string.usb_wizard_space));
        caps.add(context.getString(R.string.usb_wizard_delete_character));
        final GridView grid = new GridView(context); grid.setNumColumns(8);
        grid.setAdapter(new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, caps) {
            @Override public View getView(int position, View view, ViewGroup parent) {
                TextView cell = (TextView) super.getView(position, view, parent);
                cell.setTextSize(14); cell.setGravity(Gravity.CENTER);
                cell.setPadding(0, 0, 0, 0); cell.setMinHeight(dp(context, 40));
                if (position == caps.size() - 1) cell.setText("⌫");
                cell.setContentDescription(caps.get(position));
                return cell;
            }
        });
        form.addView(grid, new LinearLayout.LayoutParams(-1, dp(context, 200)));
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position == caps.size() - 1) { if (value.length() > 0) value.delete(value.offsetByCodePoints(value.length(), -1), value.length()); }
            else if (value.length() < 87) value.append(position == characters.length() ? " " : caps.get(position));
            preview.setText(value.toString());
        });
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(title).setView(form).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> result.finished(value.toString())).create();
        dialog.setOnKeyListener((d, key, event) -> {
            if (key == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_UP) dialog.dismiss();
                return true;
            }
            if (key == KeyEvent.KEYCODE_BUTTON_A) {
                dialog.dispatchKeyEvent(new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                        KeyEvent.KEYCODE_DPAD_CENTER, event.getRepeatCount())); return true;
            }
            return false;
        });
        dialog.setOnShowListener(d -> grid.requestFocus());
        return dialog;
    }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
