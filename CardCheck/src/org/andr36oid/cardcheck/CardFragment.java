package org.andr36oid.cardcheck;

import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.text.format.Formatter;
import android.widget.Toast;

import java.util.List;
import java.util.Map;

/** The card's details, the speed test and the real size check, with live progress. */
public class CardFragment extends PreferenceFragment {

    private static final long POLL_MS = 1000;
    /** How long the service may take to show up after ctl.start. */
    private static final long START_GRACE_MS = 5000;
    private static final String KEY_CARD = "card";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPoll = this::refresh;

    private ListPreference mCardPref;
    private Preference mDetails, mSpeed, mFull;
    private List<Card> mCards;
    private String mUuid;
    private long mStartedAt;
    /** What we started, until the service has written its first status. */
    private String mStartedAction;
    private boolean mStopping;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.card);
        mCardPref = (ListPreference) findPreference("card");
        mDetails = findPreference("details");
        mSpeed = findPreference("speed");
        mFull = findPreference("full");
        if (savedInstanceState != null) mUuid = savedInstanceState.getString(KEY_CARD);

        mCardPref.setOnPreferenceChangeListener((pref, value) -> {
            mUuid = (String) value;
            refresh();
            return true;
        });
        mSpeed.setOnPreferenceClickListener(pref -> {
            startSpeed();
            return true;
        });
        mFull.setOnPreferenceClickListener(pref -> {
            fullClicked();
            return true;
        });
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_CARD, mUuid);
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().getSystemService(NotificationManager.class)
                .cancel(FinishedReceiver.NOTIFICATION_ID);
        loadCards();
        // A check that was cut short (reboot, power loss) leaves test files behind.
        final Map<String, String> s = Checker.status();
        if ("running".equals(s.get("state")) && !Checker.running()) {
            final Card card = find(s.get("uuid"));
            if (card != null) Checker.start(Checker.CLEANUP, card);
        }
        refresh();
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mPoll);
        super.onPause();
    }

    private void loadCards() {
        mCards = Card.list(getActivity());
        if (find(mUuid) == null) mUuid = mCards.isEmpty() ? null : mCards.get(0).uuid;
        if (mCards.size() < 2) {
            getPreferenceScreen().removePreference(mCardPref);
            return;
        }
        getPreferenceScreen().addPreference(mCardPref);
        final String[] names = new String[mCards.size()];
        final String[] ids = new String[mCards.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = mCards.get(i).name;
            ids[i] = mCards.get(i).uuid;
        }
        mCardPref.setEntries(names);
        mCardPref.setEntryValues(ids);
        mCardPref.setValue(mUuid);
        mCardPref.setSummary(find(mUuid).name);
    }

    private Card find(String uuid) {
        if (uuid == null || mCards == null) return null;
        for (Card c : mCards) {
            if (uuid.equals(c.uuid)) return c;
        }
        return null;
    }

    private void refresh() {
        mHandler.removeCallbacks(mPoll);
        final Context c = getActivity();
        if (c == null) return;
        final Card card = find(mUuid);
        if (card == null) {
            mDetails.setTitle(null);
            mDetails.setSummary(R.string.no_card);
            mSpeed.setEnabled(false);
            mFull.setEnabled(false);
            return;
        }
        if (mCardPref.getValue() != null) mCardPref.setSummary(card.name);
        showDetails(c, card);

        final Map<String, String> s = Checker.status();
        final boolean running = Checker.running() || (mStartedAt != 0
                && SystemClock.elapsedRealtime() - mStartedAt < START_GRACE_MS
                && !"done".equals(s.get("state")) && !"failed".equals(s.get("state"))
                && !"stopped".equals(s.get("state")));
        if (!running) {
            mStartedAt = 0;
            mStartedAction = null;
            mStopping = false;
        }
        final String action = s.get("action") != null ? s.get("action")
                : (running ? mStartedAction : null);
        final boolean thisCard = s.get("uuid") != null ? card.uuid.equals(s.get("uuid"))
                : running && mStartedAction != null;

        // speed
        final Map<String, String> speed = Checker.result(Checker.SPEED, card);
        if (running && Checker.SPEED.equals(action) && thisCard) {
            mSpeed.setSummary("read".equals(s.get("phase"))
                    ? R.string.speed_running_read : R.string.speed_running_write);
        } else {
            final String text = Texts.speedResult(c, speed);
            mSpeed.setSummary(text != null ? text : c.getString(R.string.speed_summary));
        }
        mSpeed.setEnabled(!running);

        // real size
        if (running && Checker.FULL.equals(action) && thisCard) {
            mFull.setTitle(R.string.full_title_stop);
            mFull.setSummary(mStopping ? c.getString(R.string.full_stopping)
                    : fullProgress(c, s, speed));
            mFull.setEnabled(!mStopping);
        } else {
            mFull.setTitle(R.string.full_title);
            final String text = Texts.fullResult(c, Checker.result(Checker.FULL, card));
            mFull.setSummary(text != null ? text : fullIntro(c, card, speed));
            mFull.setEnabled(!running);
        }

        if (running) mHandler.postDelayed(mPoll, POLL_MS);
    }

    private void showDetails(Context c, Card card) {
        final String size = Formatter.formatShortFileSize(c, card.diskBytes);
        mDetails.setTitle(c.getString(card.sd ? R.string.details_title
                : R.string.details_title_usb, card.name, size));
        final StringBuilder sb = new StringBuilder();
        if (card.sd) {
            final String id = card.attr("manfid");
            String maker = Card.maker(id);
            if (maker == null && id != null) maker = c.getString(R.string.maker_unknown, id);
            final String product = card.attr("name");
            final String date = card.attr("date");
            if (maker != null && product != null && date != null) {
                sb.append(c.getString(R.string.details_card, maker, product, date)).append('\n');
            }
        }
        sb.append(c.getString(R.string.details_free,
                Formatter.formatFileSize(c, card.freeBytes())));
        mDetails.setSummary(sb);
    }

    private String fullIntro(Context c, Card card, Map<String, String> speed) {
        final long freeMb = card.freeBytes() / (1024 * 1024);
        final String free = Texts.size(c, freeMb);
        final double secs = Texts.fullEstimate(speed, freeMb);
        if (Double.isNaN(secs)) return c.getString(R.string.full_summary_unknown_time, free);
        return c.getString(R.string.full_summary, free, Texts.duration(c, secs));
    }

    private String fullProgress(Context c, Map<String, String> s, Map<String, String> speed) {
        final String phase = s.get("phase");
        final long done = Checker.number(s, "done_mb");
        final long total = Checker.number(s, "total_mb");
        final double now = Checker.seconds(s, "now");
        if ("write".equals(phase) && done > 0) {
            final double rate = done / (now - Checker.seconds(s, "started"));
            // what's left to write, plus reading it all back
            final double left = (total - done) / rate + total / readRate(speed, rate);
            return rate > 0 ? c.getString(R.string.full_writing, Texts.size(c, done),
                    Texts.size(c, total), Texts.duration(c, left))
                    : c.getString(R.string.full_writing_start, Texts.size(c, done),
                    Texts.size(c, total));
        }
        if ("verify".equals(phase) && done > 0) {
            final double rate = done / (now - Checker.seconds(s, "read0"));
            return rate > 0 ? c.getString(R.string.full_reading, Texts.size(c, done),
                    Texts.size(c, total), Texts.duration(c, (total - done) / rate))
                    : c.getString(R.string.full_reading_start, Texts.size(c, done),
                    Texts.size(c, total));
        }
        if ("verify".equals(phase)) {
            return c.getString(R.string.full_reading_start, Texts.size(c, 0),
                    Texts.size(c, total));
        }
        return c.getString(R.string.full_probe);
    }

    /** Read speed from the last speed test, else twice the write speed seen now. */
    private static double readRate(Map<String, String> speed, double writeRate) {
        final double r = Checker.speed(speed, Checker.number(speed, "size_mb"), "r0", "r1");
        return r > 0 ? r : writeRate * 2;
    }

    private void startSpeed() {
        final Card card = find(mUuid);
        if (card == null) return;
        if (Checker.running()) {
            Toast.makeText(getActivity(), R.string.busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Checker.start(Checker.SPEED, card)) {
            mStartedAt = SystemClock.elapsedRealtime();
            mStartedAction = Checker.SPEED;
        }
        refresh();
    }

    private void fullClicked() {
        final Card card = find(mUuid);
        if (card == null) return;
        final Map<String, String> s = Checker.status();
        if (Checker.running()) {
            if (Checker.FULL.equals(s.get("action")) && card.uuid.equals(s.get("uuid"))) {
                Checker.stop();
                mStopping = true;
                refresh();
            } else {
                Toast.makeText(getActivity(), R.string.busy, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        final Context c = getActivity();
        final double secs = Texts.fullEstimate(Checker.result(Checker.SPEED, card),
                card.freeBytes() / (1024 * 1024));
        final String time = Double.isNaN(secs) ? c.getString(R.string.confirm_time_unknown)
                : Texts.duration(c, secs);
        new AlertDialog.Builder(c)
                .setTitle(R.string.confirm_title)
                .setMessage(c.getString(R.string.confirm_text, card.name, time))
                .setPositiveButton(R.string.confirm_start, (d, which) -> {
                    if (Checker.start(Checker.FULL, card)) {
                        mStartedAt = SystemClock.elapsedRealtime();
                        mStartedAction = Checker.FULL;
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
