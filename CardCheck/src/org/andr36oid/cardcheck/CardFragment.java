package org.andr36oid.cardcheck;

import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.text.format.Formatter;
import android.widget.Toast;

import java.util.List;
import java.util.Map;

/**
 * The card's details and what they mean, the speed test and the real size check (which
 * can be paused and continued), with live progress.
 */
public class CardFragment extends PreferenceFragment {

    private static final long POLL_MS = 1000;
    /** How long the service may take to show up after ctl.start. */
    private static final long START_GRACE_MS = 5000;
    private static final String KEY_CARD = "card";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPoll = this::refresh;
    /** Status files, sysfs and free space are read here, not on the main thread. */
    private HandlerThread mThread;
    private Handler mWorker;
    /** Counts refresh() calls; a read that finishes after a newer one is dropped. */
    private int mGeneration;

    private ListPreference mCardPref;
    private Preference mDetails, mAdvice, mSpeed, mFull, mFullDelete;
    private PreferenceCategory mFullCategory;
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
        mThread = new HandlerThread("CardCheck");
        mThread.start();
        mWorker = new Handler(mThread.getLooper());
        mCardPref = (ListPreference) findPreference("card");
        mDetails = findPreference("details");
        mAdvice = findPreference("advice");
        mSpeed = findPreference("speed");
        mFull = findPreference("full");
        mFullDelete = findPreference("full_delete");
        mFullCategory = (PreferenceCategory) findPreference("full_category");
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
        mFullDelete.setOnPreferenceClickListener(pref -> {
            start(Checker.DELETE);
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
        // A speed test that was cut short (reboot, power loss) leaves its file behind. A
        // real size check keeps its test data and progress: it shows as paused.
        final Map<String, String> s = Checker.status();
        if ("running".equals(s.get("state")) && Checker.SPEED.equals(s.get("action"))
                && !Checker.running()) {
            final Card card = find(s.get("uuid"));
            if (card != null) Checker.start(Checker.CLEANUP, card);
        }
        refresh();
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mPoll);
        mGeneration++;
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mThread.quitSafely();
        super.onDestroy();
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
            mAdvice.setSummary(null);
            mSpeed.setEnabled(false);
            mFull.setEnabled(false);
            mFullCategory.removePreference(mFullDelete);
            return;
        }
        if (mCardPref.getValue() != null) mCardPref.setSummary(card.name);

        final int generation = ++mGeneration;
        mWorker.post(() -> {
            final Snapshot snap = new Snapshot(card);
            mHandler.post(() -> {
                if (generation == mGeneration && isResumed() && getActivity() != null) {
                    show(getActivity(), card, snap);
                }
            });
        });
    }

    /** Everything refresh() shows that has to be read from files. */
    private static final class Snapshot {
        final Map<String, String> status, progress, speed, full;
        final boolean serviceRunning;
        final CardId id;
        final String manfid, product, date;
        final long freeBytes;

        Snapshot(Card card) {
            serviceRunning = Checker.running();
            status = Checker.status();
            progress = Checker.progress(card);
            speed = Checker.result(Checker.SPEED, card);
            full = Checker.result(Checker.FULL, card);
            id = card.id();
            manfid = card.sd ? card.attr("manfid") : null;
            product = card.sd ? card.attr("name") : null;
            date = card.sd ? card.attr("date") : null;
            freeBytes = card.freeBytes();
        }
    }

    private void show(Context c, Card card, Snapshot snap) {
        final Map<String, String> s = snap.status;
        final boolean running = snap.serviceRunning || (mStartedAt != 0
                && SystemClock.elapsedRealtime() - mStartedAt < START_GRACE_MS
                && !"done".equals(s.get("state")) && !"failed".equals(s.get("state"))
                && !"stopped".equals(s.get("state")) && !"paused".equals(s.get("state")));
        if (!running) {
            mStartedAt = 0;
            mStartedAction = null;
            mStopping = false;
        }
        // a resumed check writes action=full like a new one
        final String action = s.get("action") != null ? s.get("action")
                : (running ? mStartedAction : null);
        final boolean thisCard = s.get("uuid") != null ? card.uuid.equals(s.get("uuid"))
                : running && mStartedAction != null;
        final boolean fullHere = running && thisCard
                && (Checker.FULL.equals(action) || Checker.RESUME.equals(action));
        final boolean paused = !fullHere
                && Checker.paused(card, snap.progress, snap.serviceRunning, s);

        final CardId id = snap.id;
        final Map<String, String> speed = snap.speed;
        final Map<String, String> full = snap.full;
        showDetails(c, card, snap, speed);
        mAdvice.setSummary(Texts.advice(c, card, id, speed, full, paused));

        // speed
        if (running && Checker.SPEED.equals(action) && thisCard) {
            final String phase = s.get("phase");
            mSpeed.setSummary("random".equals(phase) ? R.string.speed_running_small
                    : "read".equals(phase) ? R.string.speed_running_read
                    : R.string.speed_running_write);
        } else if (paused) {
            mSpeed.setSummary(R.string.error_paused);
        } else {
            final String text = Texts.speedResult(c, speed);
            mSpeed.setSummary(text != null ? text : c.getString(R.string.speed_summary_small));
        }
        mSpeed.setEnabled(!running && !paused);

        // real size
        if (fullHere) {
            mFull.setTitle(R.string.full_title_stop);
            mFull.setSummary(mStopping ? c.getString(R.string.full_stopping)
                    : fullProgress(c, s, speed));
            mFull.setEnabled(!mStopping);
        } else if (paused) {
            mFull.setTitle(R.string.full_title_continue);
            mFull.setSummary(Texts.paused(c, snap.progress));
            mFull.setEnabled(true);
        } else {
            mFull.setTitle(R.string.full_title);
            final String text = Texts.fullResult(c, full);
            mFull.setSummary(text != null ? text : fullIntro(c, snap.freeBytes, speed));
            mFull.setEnabled(!running);
        }
        if (paused) {
            mFullCategory.addPreference(mFullDelete);
            mFullDelete.setSummary(c.getString(R.string.full_delete_summary, Texts.size(c,
                    Checker.number(snap.progress, "written_mb"))));
        } else {
            mFullCategory.removePreference(mFullDelete);
        }

        if (running) mHandler.postDelayed(mPoll, POLL_MS);
    }

    private void showDetails(Context c, Card card, Snapshot snap, Map<String, String> speed) {
        final String size = Formatter.formatShortFileSize(c, card.diskBytes);
        mDetails.setTitle(c.getString(card.sd ? R.string.details_title
                : R.string.details_title_usb, card.name, size));
        final StringBuilder sb = new StringBuilder();
        if (card.sd) {
            final String mid = snap.manfid;
            String maker = Card.maker(mid);
            if (maker == null && mid != null) maker = c.getString(R.string.maker_unknown, mid);
            final String product = snap.product;
            final String date = snap.date;
            if (maker != null && product != null && date != null) {
                sb.append(c.getString(R.string.details_card, maker, product, date)).append('\n');
            }
        }
        final String lines = Texts.cardLines(c, snap.id, speed);
        if (!lines.isEmpty()) sb.append(lines).append('\n');
        sb.append(c.getString(R.string.details_free,
                Formatter.formatFileSize(c, snap.freeBytes)));
        mDetails.setSummary(sb);
    }

    private String fullIntro(Context c, long freeBytes, Map<String, String> speed) {
        final long freeMb = freeBytes / (1024 * 1024);
        final String free = Texts.size(c, freeMb);
        final double secs = Texts.fullEstimate(speed, freeMb);
        if (Double.isNaN(secs)) return c.getString(R.string.full_summary_unknown_time, free);
        return c.getString(R.string.full_summary, free, Texts.duration(c, secs));
    }

    private String fullProgress(Context c, Map<String, String> s, Map<String, String> speed) {
        final String phase = s.get("phase");
        final long done = Checker.number(s, "done_mb");
        final long total = Checker.number(s, "total_mb");
        // a resumed check starts counting from where it was paused
        final long base = Math.max(0, Checker.number(s, "base_mb"));
        final double now = Checker.seconds(s, "now");
        if ("write".equals(phase) && done > base) {
            final double rate = (done - base) / (now - Checker.seconds(s, "started"));
            // what's left to write, plus reading it all back
            final double left = (total - done) / rate + total / readRate(speed, rate);
            return rate > 0 ? c.getString(R.string.full_writing, Texts.size(c, done),
                    Texts.size(c, total), Texts.duration(c, left))
                    : c.getString(R.string.full_writing_start, Texts.size(c, done),
                    Texts.size(c, total));
        }
        if ("write".equals(phase)) {
            return c.getString(R.string.full_writing_start, Texts.size(c, Math.max(0, done)),
                    Texts.size(c, total));
        }
        if ("verify".equals(phase) && done > base) {
            final double rate = (done - base) / (now - Checker.seconds(s, "read0"));
            return rate > 0 ? c.getString(R.string.full_reading, Texts.size(c, done),
                    Texts.size(c, total), Texts.duration(c, (total - done) / rate))
                    : c.getString(R.string.full_reading_start, Texts.size(c, done),
                    Texts.size(c, total));
        }
        if ("verify".equals(phase)) {
            return c.getString(R.string.full_reading_start, Texts.size(c, Math.max(0, done)),
                    Texts.size(c, total));
        }
        return c.getString(R.string.full_probe);
    }

    /** Read speed from the last speed test, else twice the write speed seen now. */
    private static double readRate(Map<String, String> speed, double writeRate) {
        final double r = Checker.speed(speed, Checker.number(speed, "size_mb"), "r0", "r1");
        return r > 0 ? r : writeRate * 2;
    }

    /** Starts the service for the selected card, unless something is running. */
    private void start(String action) {
        final Card card = find(mUuid);
        if (card == null) return;
        if (Checker.running()) {
            Toast.makeText(getActivity(), R.string.busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Checker.RESUME.equals(action) || Checker.DELETE.equals(action)) {
            getActivity().getSystemService(NotificationManager.class)
                    .cancel(FinishedReceiver.PAUSED_ID);
        }
        if (Checker.start(action, card)) {
            mStartedAt = SystemClock.elapsedRealtime();
            mStartedAction = action;
        }
        refresh();
    }

    private void startSpeed() {
        start(Checker.SPEED);
    }

    private void fullClicked() {
        final Card card = find(mUuid);
        if (card == null) return;
        final Map<String, String> s = Checker.status();
        final Context c = getActivity();
        if (Checker.running()) {
            if (Checker.FULL.equals(s.get("action")) && card.uuid.equals(s.get("uuid"))) {
                new AlertDialog.Builder(c)
                        .setTitle(R.string.stop_title)
                        .setMessage(R.string.stop_text)
                        .setPositiveButton(R.string.stop_pause, (d, which) -> stop(true))
                        .setNegativeButton(R.string.stop_delete, (d, which) -> stop(false))
                        .show();
            } else {
                Toast.makeText(c, R.string.busy, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (Checker.paused(card)) {
            start(Checker.RESUME);
            return;
        }
        final double secs = Texts.fullEstimate(Checker.result(Checker.SPEED, card),
                card.freeBytes() / (1024 * 1024));
        final String time = Double.isNaN(secs) ? c.getString(R.string.confirm_time_unknown)
                : Texts.duration(c, secs);
        new AlertDialog.Builder(c)
                .setTitle(R.string.confirm_title)
                .setMessage(c.getString(R.string.confirm_text_pause, card.name, time))
                .setPositiveButton(R.string.confirm_start, (d, which) -> start(Checker.FULL))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void stop(boolean pause) {
        Checker.stop(pause);
        mStopping = true;
        refresh();
    }
}
