package org.andr36oid.usbmode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.view.View;

/** The root helper owns allocation, partitioning and formatting. */
final class VirtualDriveDialog {
    static AlertDialog create(Activity activity, UsbDrive.Images images, Runnable changed) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding, padding, padding);
        EditText name = edit(activity, form, R.string.usb_virtual_name, false);
        Spinner size = spinner(activity, form, activity.getString(R.string.usb_virtual_sizes).split("\\|"));
        LinearLayout customRow = new LinearLayout(activity);
        customRow.setOrientation(LinearLayout.VERTICAL); form.addView(customRow);
        EditText custom = edit(activity, customRow, R.string.usb_virtual_size, true);
        customRow.setVisibility(View.GONE);
        size.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                customRow.setVisibility(position == 6 ? View.VISIBLE : View.GONE);
            }
        });
        Spinner fs = spinner(activity, form, activity.getString(R.string.usb_virtual_filesystems).split("\\|"));
        TextView hint = new TextView(activity); form.addView(hint);
        final int[] hints = {R.string.usb_virtual_fat32, R.string.usb_virtual_exfat,
                R.string.usb_virtual_ntfs, R.string.usb_virtual_ext4};
        fs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                hint.setText(hints[position]);
            }
        });
        EditText label = edit(activity, form, R.string.usb_virtual_label, false);
        label.setText(R.string.usb_virtual_default_label);
        TextView available = new TextView(activity);
        available.setText(activity.getString(R.string.usb_virtual_available,
                VirtualDrive.maximum(images.free, images.filesystem) / VirtualDrive.GB));
        form.addView(available);
        TextView allocation = new TextView(activity); allocation.setText(R.string.usb_virtual_allocation); form.addView(allocation);
        TextView error = new TextView(activity); form.addView(error);
        ScrollView scroll = new ScrollView(activity); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.usb_virtual_create)
                .setView(scroll).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.usb_virtual_create, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String basename = name.getText().toString();
            String filesystem = VirtualDrive.FILESYSTEMS[fs.getSelectedItemPosition()];
            String gb = size.getSelectedItemPosition() == 6 ? custom.getText().toString()
                    : new String[] {"1", "4", "8", "16", "32", "64"}[size.getSelectedItemPosition()];
            long bytes = VirtualDrive.size(gb);
            if (!VirtualDrive.name(basename)) { error.setText(R.string.usb_virtual_bad_name); name.requestFocus(); return; }
            if (bytes == 0 || bytes > VirtualDrive.maximum(images.free, images.filesystem)) {
                error.setText(("vfat".equals(images.filesystem) || "msdos".equals(images.filesystem)) && bytes >= 4 * VirtualDrive.GB
                        ? R.string.usb_virtual_fat_limit : R.string.usb_virtual_bad_size);
                size.requestFocus(); return;
            }
            if (!VirtualDrive.label(label.getText().toString(), filesystem)) {
                error.setText(R.string.usb_virtual_bad_label); label.requestFocus(); return;
            }
            UsbDriveControl.create(activity, images, basename, gb, filesystem, label.getText().toString());
            dialog.dismiss(); changed.run();
        }));
        return dialog;
    }

    private static EditText edit(Activity activity, LinearLayout form, int title, boolean numeric) {
        TextView caption = new TextView(activity); caption.setText(title); form.addView(caption);
        EditText edit = new EditText(activity); edit.setSingleLine(true);
        edit.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        form.addView(edit); return edit;
    }

    private static Spinner spinner(Activity activity, LinearLayout form, String[] entries) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); form.addView(spinner); return spinner;
    }
}
