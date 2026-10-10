package org.andr36oid.storagecare;

import android.content.Context;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.text.format.DateUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * One group to review: every file or folder with its place, size and date, each with a
 * check box, biggest first. "Pick all" / "Pick none" at the top. A very long list shows
 * the biggest MAX_SHOWN; the rest follow the "all"/"none" choice.
 */
public class GroupFragment extends PreferenceFragment {

    private static final int MAX_SHOWN = 300;

    private String mGroup;
    private final List<CheckBoxPreference> mBoxes = new ArrayList<>();
    private Preference mAll;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mGroup = getArguments().getString(GroupActivity.GROUP);
        final Context c = getActivity();
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);
        if (Picks.scan == null) return;

        final Preference about = new Preference(c);
        about.setSummary(Texts.groupAbout(c, mGroup));
        about.setSelectable(false);
        screen.addPreference(about);

        mAll = new Preference(c);
        mAll.setOnPreferenceClickListener(p -> {
            final boolean pick = Picks.count(mGroup)[0] < Picks.scan.group(mGroup).size();
            for (Scan.Item i : Picks.scan.group(mGroup)) {
                if (pick) {
                    Picks.picked.add(i.path);
                } else {
                    Picks.picked.remove(i.path);
                }
            }
            for (CheckBoxPreference b : mBoxes) b.setChecked(pick);
            updateAll();
            return true;
        });
        screen.addPreference(mAll);

        final List<Scan.Item> items = Picks.scan.group(mGroup);
        items.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        for (int n = 0; n < items.size() && n < MAX_SHOWN; n++) {
            final Scan.Item i = items.get(n);
            final CheckBoxPreference b = new CheckBoxPreference(c);
            b.setPersistent(false);
            b.setTitle(new File(i.path).getName());
            b.setSummary(summary(c, i));
            b.setChecked(Picks.picked.contains(i.path));
            b.setOnPreferenceChangeListener((p, v) -> {
                if ((Boolean) v) {
                    Picks.picked.add(i.path);
                } else {
                    Picks.picked.remove(i.path);
                }
                postUpdate();
                return true;
            });
            mBoxes.add(b);
            screen.addPreference(b);
        }
        if (items.size() > MAX_SHOWN) {
            final Preference more = new Preference(c);
            more.setSummary(getString(R.string.group_more, items.size() - MAX_SHOWN));
            more.setSelectable(false);
            screen.addPreference(more);
        }
        updateAll();
    }

    private void postUpdate() {
        getListView().post(this::updateAll);
    }

    private void updateAll() {
        final Context c = getActivity();
        final long[] n = Picks.count(mGroup);
        final int total = Picks.scan.group(mGroup).size();
        mAll.setTitle(n[0] < total ? R.string.pick_all : R.string.pick_none);
        mAll.setSummary(getString(R.string.picked_some, n[0], total, Texts.size(c, n[1])));
    }

    private static String summary(Context c, Scan.Item i) {
        final String place = Picks.scan.display(new File(i.path).getParent());
        final String date = DateUtils.formatDateTime(c, i.mtime,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_ABBREV_MONTH);
        String s = c.getString(R.string.item_summary, place, Texts.size(c, i.bytes), date);
        if (i.keep != null) {
            s += "\n" + c.getString(R.string.item_same_as, Picks.scan.display(i.keep));
        }
        return s;
    }
}
