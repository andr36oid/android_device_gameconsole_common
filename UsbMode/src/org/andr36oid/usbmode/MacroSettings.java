package org.andr36oid.usbmode;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.List;

final class MacroSettings {
    static MacroProfiles load(Context context) {
        try { return MacroProfiles.read(PreferenceManager.getDefaultSharedPreferences(context).getString("macro_profiles","")); }
        catch (IllegalArgumentException e) { return MacroProfiles.examples(context.getString(R.string.usb_macro_streaming),context.getString(R.string.usb_macro_editing)); }
    }
    static String label(Context context, MacroProfiles.Combo combo) {
        String label = combo.label();
        if (combo.media == 0) return label;
        String[] names = context.getString(R.string.usb_macro_media).split("\\|"); StringBuilder media = new StringBuilder();
        for (int i = 0; i < 6; i++) if ((combo.media & (1 << i)) != 0) { if (media.length() > 0) media.append('+'); media.append(names[i]); }
        return label.replace("♪" + Integer.toHexString(combo.media),media.toString());
    }
    static int selected(Context context, MacroProfiles profiles) {
        return Math.max(0,Math.min(profiles.profiles.size() - 1,PreferenceManager.getDefaultSharedPreferences(context).getInt("macro_profile",0)));
    }
    static void show(Context context, Runnable changed) {
        MacroProfiles profiles = load(context);
        String[] names = new String[profiles.profiles.size()]; for (int i = 0; i < names.length; i++) names[i] = profiles.profiles.get(i).name;
        new AlertDialog.Builder(context).setTitle(R.string.usb_macro_profiles).setSingleChoiceItems(names,selected(context,profiles),(dialog,which) -> {
            save(context,profiles,which,changed); dialog.dismiss(); actions(context,profiles,which,changed);
        }).setPositiveButton(R.string.usb_macro_create,(dialog,which) -> name(context,profiles,-1,false,changed))
                .setNegativeButton(android.R.string.cancel,null).show();
    }
    private static void save(Context context, MacroProfiles profiles, int selected, Runnable changed) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString("macro_profiles",profiles.write()).putInt("macro_profile",selected).apply(); changed.run();
    }
    private static void actions(Context context, MacroProfiles profiles, int index, Runnable changed) {
        String[] actions = {context.getString(R.string.usb_macro_edit),context.getString(R.string.usb_macro_rename),
                context.getString(R.string.usb_macro_duplicate),context.getString(R.string.usb_macro_delete)};
        new AlertDialog.Builder(context).setTitle(profiles.profiles.get(index).name).setItems(actions,(dialog,which) -> {
            if (which == 0) buttons(context,profiles,index,changed);
            if (which == 1 || which == 2) name(context,profiles,index,which == 2,changed);
            if (which == 3 && profiles.profiles.size() > 1) {
                profiles.profiles.remove(index); save(context,profiles,0,changed); show(context,changed);
            }
        }).setNegativeButton(android.R.string.cancel,null).show();
    }
    private static void name(Context context, MacroProfiles profiles, int index, boolean duplicate, Runnable changed) {
        if ((index < 0 || duplicate) && profiles.profiles.size() >= 32) return;
        EditText name = new EditText(context); name.setSingleLine(true);
        if (index >= 0) name.setText(profiles.profiles.get(index).name);
        UsbTextInput.bind(name);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(R.string.usb_macro_name).setView(name)
                .setPositiveButton(android.R.string.ok,null).setNegativeButton(android.R.string.cancel,null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String value = MacroProfiles.validName(name.getText().toString()); int selected = index;
                if (duplicate) { profiles.duplicate(index,value); selected = profiles.profiles.size() - 1; }
                else if (index < 0) { profiles.profiles.add(new MacroProfiles.Profile(value)); selected = profiles.profiles.size() - 1; }
                else profiles.profiles.get(index).name = value;
                save(context,profiles,selected,changed); dialog.dismiss(); actions(context,profiles,selected,changed);
            } catch (IllegalArgumentException e) { name.setError(context.getString(R.string.usb_macro_bad_name)); }
        })); dialog.show();
    }
    private static void buttons(Context context, MacroProfiles profiles, int index, Runnable changed) {
        String[] labels = new String[12];
        for (int i = 0; i < 12; i++) labels[i] = MacroProfiles.CAPS[i] + " → " + label(context,profiles.profiles.get(index).buttons[i]);
        new AlertDialog.Builder(context).setTitle(R.string.usb_macro_edit).setItems(labels,(dialog,which) -> combo(context,profiles,index,which,changed))
                .setNegativeButton(android.R.string.cancel,null).show();
    }
    private static void combo(Context context, MacroProfiles profiles, int profile, int button, Runnable changed) {
        List<Integer> usages = new ArrayList<>();
        List<String> names = new ArrayList<>();
        String[] mods = {"Ctrl","Shift","Alt","Win","RCtrl","RShift","AltGr","RWin"};
        for (int i = 0; i < 8; i++) { usages.add(0xe0 + i); names.add(mods[i]); }
        for (KeyboardModel.Key[] row : KeyboardModel.ROWS) for (KeyboardModel.Key key : row) if (key.usage > 0 && key.usage < 0xe0 && !usages.contains(key.usage)) {
            usages.add(key.usage); names.add(MacroProfiles.keyName(key.usage));
        }
        for (int i = 0x68; i <= 0x73; i++) { usages.add(i); names.add(MacroProfiles.keyName(i)); }
        String[] media = context.getString(R.string.usb_macro_media).split("\\|");
        for (int i = 0; i < 6; i++) { usages.add(0x100 + i); names.add(media[i]); }
        boolean[] checked = new boolean[names.size()]; MacroProfiles.Combo old = profiles.profiles.get(profile).buttons[button];
        for (int i = 0; i < checked.length; i++) {
            int usage = usages.get(i);
            checked[i] = usage >= 0x100 ? (old.media & (1 << (usage - 0x100))) != 0 : usage >= 0xe0 ? (old.modifiers & (1 << (usage - 0xe0))) != 0 : false;
            for (int key : old.keys) if (key == usage) checked[i] = true;
        }
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(MacroProfiles.CAPS[button])
                .setMultiChoiceItems(names.toArray(new String[0]),checked,(d,which,value) -> checked[which] = value)
                .setPositiveButton(R.string.usb_macro_save,null).setNegativeButton(android.R.string.cancel,null)
                .setNeutralButton(R.string.usb_macro_clear,(d,which) -> { profiles.profiles.get(profile).buttons[button] = new MacroProfiles.Combo(); save(context,profiles,profile,changed); buttons(context,profiles,profile,changed); }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            MacroProfiles.Combo combo = new MacroProfiles.Combo(); List<Integer> keys = new ArrayList<>();
            for (int i = 0; i < checked.length; i++) if (checked[i]) {
                int usage = usages.get(i);
                if (usage >= 0x100) combo.media |= 1 << (usage - 0x100);
                else if (usage >= 0xe0) combo.modifiers |= 1 << (usage - 0xe0);
                else keys.add(usage);
            }
            if (keys.size() > 6) { dialog.setTitle(R.string.usb_macro_six); return; }
            combo.keys = new int[keys.size()]; for (int i = 0; i < keys.size(); i++) combo.keys[i] = keys.get(i);
            profiles.profiles.get(profile).buttons[button] = combo; save(context,profiles,profile,changed); dialog.dismiss(); buttons(context,profiles,profile,changed);
        })); dialog.show();
    }
}
