package org.andr36oid.subsystemsetup;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Settings owns preferences and presentation; init owns sessions and conversion. */
public class SubsystemSetupFragment extends PreferenceFragment {
    private static final String TAG = "SubsystemSetup";
    private static final String PROP = "sys.andr36oid.subsystem.";
    private static final String IMAGE_PROP = PROP + "image";
    private static final String REQUEST_PROP = PROP + "request";
    private static final String PORT_PROP = PROP + "port";
    private static final String SELECT_PROP = "persist.sys.andr36oid.subsystem.selected";
    private static final String PREFER_PROP = "persist.sys.andr36oid.subsystem.prefer_erofs";
    private static final String URL_PROP = "persist.andr36oid.subsystem.image_url";
    private static final String SELECTED_ID = "selected_image_id";
    private static final String SELECTED_NAME = "selected_image_name";
    private static final String SELECTED_UUID = "selected_image_uuid";
    private static final String JOB_ID = "opt_requested_job";
    private static final String JOB_PENDING = "opt_pending_until";
    private static final String DOWNLOAD_ID = "download_id";
    private static final String DOWNLOAD_DIRECTORY = "download_directory";
    private static final String IMAGE_NAME = "darkosen.img";
    private static final String PARTIAL_NAME = "darkosen.img.download";
    private static final long REFRESH_MS = 2000;
    private static final long ACK_MS = 15000;
    private static final Comparator<File> BYTE_ORDER = (a, b) -> compareUtf8(a.getName(), b.getName());

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mImages = Executors.newSingleThreadExecutor();
    private final Runnable mRefresh = new Runnable() {
        @Override public void run() {
            if (!mResumed || getActivity() == null) return;
            checkDownload();
            refresh();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };
    private final BroadcastReceiver mMounts = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { enumerateImages(false); }
    };
    private Context mContext;
    private SharedPreferences mPrefs;
    private Preference mStatus, mEmulationStation, mPortMaster, mDownload;
    private ImageListPreference mPicker;
    private SwitchPreference mPrefer;
    private ProgressPreference mOptimize;
    private List<ImageRow> mRows = new ArrayList<>();
    private boolean mResumed, mEnumerating, mEnumerateAgain, mOpenChooser;
    private boolean mWritableCard, mListUnreadable, mListReady, mScanPending;
    private boolean mLaunchPending;
    private long mAwaitSeq = -1, mLaunchSeq, mLaunchDeadline;
    private String mObservedSelection, mObservedPrefer, mLastTerminalJob = "";
    private int mEnumerationGeneration;

    private final class ImageListPreference extends ListPreference {
        ImageListPreference(Context context) { super(context); }
        @Override protected void onClick() { enumerateImages(true); }
        void openChooser() { super.onClick(); }
    }

    private static final class ProgressPreference extends Preference {
        private boolean mActive;
        ProgressPreference(Context context) { super(context); }
        void setActive(boolean active) {
            if (mActive != active) { mActive = active; notifyChanged(); }
        }
        @Override protected View onCreateView(ViewGroup parent) {
            final View view = super.onCreateView(parent);
            final ViewGroup widget = view.findViewById(android.R.id.widget_frame);
            if (widget != null) {
                final ProgressBar spinner = new ProgressBar(getContext(), null,
                        android.R.attr.progressBarStyleSmall);
                spinner.setIndeterminate(true);
                widget.addView(spinner);
            }
            return view;
        }
        @Override protected void onBindView(View view) {
            super.onBindView(view);
            final View widget = view.findViewById(android.R.id.widget_frame);
            if (widget != null) widget.setVisibility(mActive ? View.VISIBLE : View.GONE);
        }
    }

    private static final class Card {
        final File path;
        final String uuid;
        final boolean writable;
        Card(VolumeInfo volume) {
            path = volume.getPath();
            uuid = volume.getFsUuid().toLowerCase(Locale.ROOT);
            writable = volume.isMountedWritable();
        }
    }

    private static final class ImageRow {
        final String id, name, uuid, original;
        final long size;
        final boolean optimized, valid;
        ImageRow(String id, String name, String uuid, long size,
                boolean optimized, boolean valid, String original) {
            this.id = id; this.name = name; this.uuid = uuid; this.size = size;
            this.optimized = optimized; this.valid = valid; this.original = original;
        }
    }

    private static final class Resolution {
        String seq, resolved, active, state, image, selection, prefer;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context c = getActivity();
        mContext = c.getApplicationContext();
        mPrefs = c.getSharedPreferences("subsystem_setup", Context.MODE_PRIVATE);
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);
        screen.addPreference(instruction(c, 0, R.string.subsystem_intro));
        screen.addPreference(instruction(c, R.string.subsystem_about_title, R.string.subsystem_about));
        mStatus = instruction(c, R.string.subsystem_status, R.string.subsystem_checking);
        screen.addPreference(mStatus);
        final PreferenceCategory setup = new PreferenceCategory(c);
        setup.setTitle(R.string.subsystem_setup);
        screen.addPreference(setup);
        setup.addPreference(instruction(c, R.string.subsystem_step_image, R.string.subsystem_step_image_summary));
        mPicker = new ImageListPreference(c);
        mPicker.setTitle(R.string.subsystem_image_picker);
        mPicker.setPersistent(false);
        mPicker.setEntries(new CharSequence[]{getString(R.string.subsystem_image_automatic)});
        mPicker.setEntryValues(new CharSequence[]{""});
        mPicker.setOnPreferenceChangeListener((p, value) -> { saveSelection((String) value); return false; });
        setup.addPreference(mPicker);
        mPrefer = new SwitchPreference(c);
        mPrefer.setTitle(R.string.subsystem_prefer_erofs);
        mPrefer.setSummary(R.string.subsystem_prefer_erofs_summary);
        mPrefer.setPersistent(false);
        mPrefer.setOnPreferenceChangeListener((p, value) -> { savePreference((Boolean) value); return false; });
        setup.addPreference(mPrefer);
        mOptimize = new ProgressPreference(c);
        mOptimize.setTitle(R.string.subsystem_optimize);
        mOptimize.setOnPreferenceClickListener(p -> optimize());
        setup.addPreference(mOptimize);
        setup.addPreference(instruction(c, R.string.subsystem_step_portmaster, R.string.subsystem_step_portmaster_summary));
        setup.addPreference(instruction(c, R.string.subsystem_step_ports, R.string.subsystem_step_ports_summary));
        mEmulationStation = new Preference(c);
        mEmulationStation.setTitle(R.string.subsystem_open_es);
        mEmulationStation.setOnPreferenceClickListener(p -> launch("ES"));
        screen.addPreference(mEmulationStation);
        mPortMaster = new Preference(c);
        mPortMaster.setTitle(R.string.subsystem_open_pm);
        mPortMaster.setOnPreferenceClickListener(p -> launch("PM"));
        screen.addPreference(mPortMaster);
        mDownload = new Preference(c);
        mDownload.setTitle(R.string.subsystem_get_image);
        mDownload.setOnPreferenceClickListener(p -> downloadImage());
        screen.addPreference(mDownload);
    }

    private Preference instruction(Context c, int title, int summary) {
        final Preference row = new Preference(c);
        if (title != 0) row.setTitle(title);
        row.setSummary(summary); row.setSelectable(false); return row;
    }

    @Override public void onResume() {
        super.onResume();
        mResumed = true;
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addDataScheme("file");
        getActivity().registerReceiver(mMounts, filter);
        // Never restore properties from cached preferences. Re-scan even after 0.
        enumerateImages(false);
        mHandler.post(mRefresh);
    }

    @Override public void onPause() {
        mResumed = false;
        mHandler.removeCallbacks(mRefresh);
        getActivity().unregisterReceiver(mMounts);
        setKeepScreenOn(false);
        super.onPause();
    }

    @Override public void onDestroy() {
        ++mEnumerationGeneration;
        mImages.shutdownNow();
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static int compareUtf8(String a, String b) {
        final byte[] aa = a.getBytes(StandardCharsets.UTF_8), bb = b.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < Math.min(aa.length, bb.length); ++i) {
            final int d = (aa[i] & 255) - (bb[i] & 255);
            if (d != 0) return d;
        }
        return aa.length - bb.length;
    }

    // Same vectors as broker selection_id and erofs-support id, in erofs-tools/selection-vectors.tsv.
    static String selectionId(String uuid, String name) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(uuid.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return hex(digest.digest(name.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static String hex(byte[] bytes) {
        final StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private static boolean safeName(String name) {
        if (!name.endsWith(".img") || name.length() < 4 || name.indexOf('/') >= 0) return false;
        for (int i = 0; i < name.length(); ++i) if (Character.isISOControl(name.charAt(i))) return false;
        return true;
    }

    private static long le32(RandomAccessFile file, long offset) throws IOException {
        file.seek(offset); return Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
    }

    // Small format headers only. The root resolver is authoritative for usability.
    private static boolean erofsHeader(RandomAccessFile file) throws IOException {
        return file.length() >= 4096 && le32(file, 1024) == 0xe0f5e1e2L;
    }

    private static boolean originalHeader(RandomAccessFile file) throws IOException {
        if (file.length() < 4096) return false;
        file.seek(510); if (file.readUnsignedShort() != 0x55aa) return false;
        for (int i = 0; i < 4; ++i) {
            final long base = 446 + i * 16;
            file.seek(base + 4); if (file.readUnsignedByte() != 0x83) continue;
            final long offset = le32(file, base + 8) * 512;
            final long length = le32(file, base + 12) * 512;
            if (offset < 512 || length < 69632 || offset > file.length() || length > file.length() - offset) return false;
            file.seek(offset + 65536 + 64);
            final byte[] magic = new byte[8]; file.readFully(magic);
            return Arrays.equals(magic, "_BHRfS_M".getBytes(StandardCharsets.US_ASCII));
        }
        return false;
    }

    private static String completedOriginal(File image, Card card, RandomAccessFile file) throws IOException {
        final File meta = new File(image.getPath() + ".meta");
        if (!meta.isFile() || meta.length() > 4096 || Files.isSymbolicLink(meta.toPath())
                || !meta.getCanonicalFile().equals(new File(image.getParentFile().getCanonicalFile(), meta.getName()))) return null;
        final List<String> lines = Files.readAllLines(meta.toPath(), StandardCharsets.UTF_8);
        final Map<String, String> values = new HashMap<>();
        for (String line : lines) {
            final int eq = line.indexOf('=');
            if (eq < 1 || values.put(line.substring(0, eq), line.substring(eq + 1)) != null) return null;
        }
        if (values.size() != 13 || !"1".equals(values.get("version"))
                || !card.uuid.equals(values.get("card_uuid"))
                || !values.getOrDefault("job", "").matches("[0-9a-f]{16}")
                || !values.getOrDefault("generation", "").matches("[0-9a-f]{64}")
                || !"erofs-utils-1.8.10".equals(values.get("tool"))
                || !"b4096,lz4,no-xattr-filter,preserve-mtime,workers1".equals(values.get("options"))
                || !Long.toString(file.length()).equals(values.get("output_size"))) return null;
        final String encoded = values.getOrDefault("source_name_hex", "");
        if (!encoded.matches("(?:[0-9a-f]{2}){1,255}")) return null;
        final byte[] bytes = new byte[encoded.length() / 2];
        for (int i = 0; i < bytes.length; ++i) bytes[i] = (byte) Integer.parseInt(encoded.substring(i * 2, i * 2 + 2), 16);
        final String name = new String(bytes, StandardCharsets.UTF_8);
        if (!safeName(name) || !hex(name.getBytes(StandardCharsets.UTF_8)).equals(encoded)
                || !selectionId(card.uuid, name).equals(values.get("source_id"))) return null;
        try {
            final long offset = Long.parseLong(values.getOrDefault("offset", ""));
            final long sectors = Long.parseLong(values.getOrDefault("sectors", ""));
            final long size = Long.parseLong(values.getOrDefault("source_size", ""));
            if (offset < 512 || offset % 512 != 0 || sectors < 136 || sectors > 0xffffffffL
                    || offset > size || sectors * 512 > size - offset) return null;
        } catch (NumberFormatException e) { return null; }
        file.seek(1024 + 12); if (file.readUnsignedByte() != 12 || (le32(file, 1104) & ~1L) != 0) return null;
        final long blocks = le32(file, 1060);
        if (blocks == 0 || blocks * 4096 > file.length()) return null;
        file.seek(1072); final byte[] uuid = new byte[16]; file.readFully(uuid);
        return hex(uuid).equals(values.get("output_uuid")) ? name : null;
    }

    private void enumerateImages(boolean chooser) {
        if (getActivity() == null || mImages.isShutdown()) return;
        mOpenChooser |= chooser;
        if (mEnumerating) { mEnumerateAgain = true; return; }
        mEnumerating = true;
        final int generation = ++mEnumerationGeneration;
        mImages.execute(() -> {
            final List<ImageRow> rows = new ArrayList<>();
            final Card card = gamesCard(false);
            boolean unreadable = false;
            try {
                if (card != null) {
                    if (!card.path.isDirectory() || !card.path.canRead()) throw new IOException("Unreadable games card");
                    final File directory = new File(card.path, "subsystem/darkosen");
                    // Canonicalize through /storage's volume alias, but reject a linked
                    // subsystem directory or file escaping that canonical directory.
                    final File root = card.path.getCanonicalFile();
                    final File expected = new File(root, "subsystem/darkosen");
                    if (!directory.getCanonicalFile().equals(expected)) throw new IOException("Linked image directory");
                    final File[] files = directory.listFiles(f -> safeName(f.getName()));
                    if (files == null && directory.exists()) throw new IOException("Unreadable image directory");
                    if (files != null) {
                        Arrays.sort(files, BYTE_ORDER);
                        final List<ImageRow> optimized = new ArrayList<>();
                        for (File image : files) {
                            if (!image.isFile() || Files.isSymbolicLink(image.toPath())
                                    || !image.getCanonicalFile().equals(new File(expected, image.getName()))) continue;
                            boolean valid = false, erofs = false;
                            String original = "";
                            try (RandomAccessFile input = new RandomAccessFile(image, "r")) {
                                erofs = erofsHeader(input);
                                if (erofs) {
                                    original = completedOriginal(image, card, input);
                                    if (original == null) continue; // Uncommitted generated output.
                                    valid = true;
                                } else {
                                    if (image.getName().endsWith(".erofs.img")) continue;
                                    valid = originalHeader(input);
                                }
                            } catch (IOException | RuntimeException e) {
                                if (image.getName().endsWith(".erofs.img")) continue;
                            }
                            final ImageRow row = new ImageRow(selectionId(card.uuid, image.getName()),
                                    image.getName(), card.uuid, image.length(), erofs, valid, original);
                            (erofs ? optimized : rows).add(row);
                        }
                        rows.addAll(optimized);
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Could not enumerate games-card images", e); unreadable = true;
            }
            final boolean failed = unreadable;
            mHandler.post(() -> {
                if (generation != mEnumerationGeneration || getActivity() == null) return;
                mEnumerating = false; mRows = rows; mListReady = true;
                mWritableCard = card != null && card.writable;
                mListUnreadable = failed;
                final List<CharSequence> entries = new ArrayList<>(), ids = new ArrayList<>();
                entries.add(getString(R.string.subsystem_image_automatic)); ids.add("");
                for (ImageRow row : rows) {
                    final String format = !row.valid ? getString(R.string.subsystem_image_unavailable)
                            : row.optimized ? getString(R.string.subsystem_image_optimized_from, row.original)
                            : getString(R.string.subsystem_image_original);
                    entries.add(row.name + " (" + format + ", " + Formatter.formatFileSize(mContext, row.size) + ")");
                    ids.add(row.id);
                }
                mPicker.setEntries(entries.toArray(new CharSequence[0]));
                mPicker.setEntryValues(ids.toArray(new CharSequence[0]));
                scanAfterChange(); refresh();
                if (mEnumerateAgain) {
                    mEnumerateAgain = false; enumerateImages(false);
                } else if (mOpenChooser && mResumed && !busy()) {
                    mOpenChooser = false; mPicker.openChooser();
                } else { mOpenChooser = false; }
            });
        });
    }

    private ImageRow row(String id) {
        for (ImageRow row : mRows) if (row.id.equals(id)) return row;
        return null;
    }

    private void cacheSelection(String id) {
        final ImageRow row = row(id);
        final SharedPreferences.Editor editor = mPrefs.edit().putString(SELECTED_ID, id);
        if (id.isEmpty()) editor.remove(SELECTED_NAME).remove(SELECTED_UUID);
        else if (row != null) editor.putString(SELECTED_NAME, row.name).putString(SELECTED_UUID, row.uuid);
        // Retain a missing selection's display name only for the same ID.
        else if (!id.equals(mPrefs.getString(SELECTED_ID, ""))) editor.remove(SELECTED_NAME).remove(SELECTED_UUID);
        editor.apply();
    }

    private void saveSelection(String id) {
        if (busy()) return;
        final ImageRow candidate = row(id);
        if (!id.isEmpty() && (candidate == null || !candidate.valid)) {
            message(R.string.subsystem_image_unavailable); return;
        }
        try {
            SystemProperties.set(SELECT_PROP, id);
            if (!id.equals(property(SELECT_PROP))) throw new IllegalStateException("Selection not persisted");
            cacheSelection(id); mObservedSelection = id; mPicker.setValue(id);
            scanAfterChange(); refresh();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not save selection", e); message(R.string.subsystem_image_select_failed);
        }
    }

    private void savePreference(boolean prefer) {
        if (busy()) return;
        final String value = prefer ? "1" : "0";
        try {
            SystemProperties.set(PREFER_PROP, value);
            if (!value.equals(property(PREFER_PROP))) throw new IllegalStateException("Preference not persisted");
            mObservedPrefer = value; mPrefer.setChecked(prefer);
            scanAfterChange(); refresh();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not save companion preference", e); message(R.string.subsystem_image_select_failed);
        }
    }

    private void scanAfterChange() {
        mAwaitSeq = decimal(property(PROP + "resolve_seq"));
        mScanPending = true;
        requestScanIfIdle();
    }

    private void requestScanIfIdle() {
        if (!mScanPending || mLaunchPending || ("run".equals(property(PROP + "operation"))
                && "running".equals(property("init.svc.andr36oid-subsystem")))
                || !property(REQUEST_PROP).isEmpty()) return;
        try { SystemProperties.set(REQUEST_PROP, "scan"); mScanPending = false; }
        catch (RuntimeException e) {
            mScanPending = false; Log.w(TAG, "Could not request scan", e);
            message(R.string.subsystem_scan_failed);
        }
    }

    private Resolution resolution() {
        // resolve_seq is published last. Re-read to avoid accepting mixed details.
        final Resolution r = new Resolution();
        for (int i = 0; i < 2; ++i) {
            final String guard = property(PROP + "resolve_guard");
            r.seq = property(PROP + "resolve_seq");
            if (!guard.equals(r.seq)) continue;
            r.resolved = property(PROP + "resolved"); r.active = property(PROP + "active");
            r.selection = property(PROP + "resolve_selection"); r.prefer = property(PROP + "resolve_prefer");
            r.state = property(PROP + "resolve_state"); r.image = property(IMAGE_PROP);
            if (guard.equals(property(PROP + "resolve_guard"))
                    && r.seq.equals(property(PROP + "resolve_seq"))) return r;
        }
        r.image = ""; return r;
    }

    private boolean activeJob(String state) {
        return "preparing".equals(state) || "building".equals(state)
                || "verifying".equals(state) || "publishing".equals(state);
    }

    private boolean busy() {
        return mLaunchPending || !property(PROP + "operation").isEmpty()
                || activeJob(property(PROP + "opt_state"))
                || mPrefs.getLong(JOB_PENDING, 0) > System.currentTimeMillis();
    }

    private static long decimal(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return 0; }
    }

    private void refresh() {
        if (getActivity() == null) return;
        final String selected = property(SELECT_PROP), prefer = property(PREFER_PROP);
        if (!selected.equals(mObservedSelection) || !prefer.equals(mObservedPrefer)) {
            mObservedSelection = selected; mObservedPrefer = prefer;
            scanAfterChange();
        }
        cacheSelection(selected); mPicker.setValue(selected); mPrefer.setChecked(!"0".equals(prefer));
        final Resolution r = resolution();
        if (mAwaitSeq >= 0 && decimal(r.seq) > mAwaitSeq && !"busy".equals(r.state)
                && selected.equals(r.selection) && ("0".equals(prefer) ? "0" : "1").equals(r.prefer)
                && (selected.isEmpty() || selected.equals(r.resolved) || !"1".equals(r.image))) mAwaitSeq = -1;
        if (mAwaitSeq >= 0 && "busy".equals(r.state) && property(PROP + "operation").isEmpty()
                && !activeJob(property(PROP + "opt_state"))) mScanPending = true;
        // A killed worker may leave properties behind until root reconciles its
        // journal. Retry scan after any short-lived inherited lock has drained.
        if (activeJob(property(PROP + "opt_state"))
                && !"running".equals(property("init.svc.andr36oid-subsystem-optimize"))) mScanPending = true;
        if ("run".equals(property(PROP + "operation"))
                && !"running".equals(property("init.svc.andr36oid-subsystem"))) mScanPending = true;
        checkLaunchAcknowledgement();
        updateOptimize(r);
        requestScanIfIdle();
        final boolean downloading = mPrefs.getLong(DOWNLOAD_ID, -1) != -1;
        final boolean busy = busy();
        final boolean installed = mListReady && mAwaitSeq < 0 && "1".equals(r.image)
                && (selected.isEmpty() || selected.equals(r.resolved));
        mPicker.setEnabled(!busy && !downloading && mListReady);
        mPrefer.setEnabled(!busy && !downloading);
        final ImageRow source = row(r.resolved), effective = row(r.active);
        if (!selected.isEmpty() && ("selected_missing".equals(r.state)
                || row(selected) == null && mListReady && !"invalid_image".equals(r.state))) {
            final String name = mPrefs.getString(SELECTED_NAME, "");
            final String uuid = mPrefs.getString(SELECTED_UUID, "");
            mPicker.setSummary((name.isEmpty() ? "" : name + " (" + uuid + ")\n") + getString(R.string.subsystem_image_missing));
        } else if ("invalid_image".equals(r.state)) mPicker.setSummary(R.string.subsystem_image_invalid);
        else if (mListUnreadable) mPicker.setSummary(R.string.subsystem_image_unreadable);
        else if (source != null && installed) {
            mPicker.setSummary(getString(R.string.subsystem_image_using, source.name,
                    !source.optimized && "0".equals(prefer) ? getString(R.string.subsystem_image_original)
                            : effective == null ? source.name : effective.name));
        } else mPicker.setSummary(mRows.isEmpty() ? R.string.subsystem_image_none : R.string.subsystem_checking);
        mStatus.setSummary(busy ? R.string.subsystem_optimize_busy
                : "selected_missing".equals(r.state) ? R.string.subsystem_image_missing
                : "invalid_image".equals(r.state) ? R.string.subsystem_image_invalid
                : "fallback_plain".equals(r.state) ? R.string.subsystem_image_fallback
                : installed ? R.string.subsystem_installed
                : mAwaitSeq >= 0 ? R.string.subsystem_checking : R.string.subsystem_missing);
        mEmulationStation.setEnabled(installed && !busy && !downloading);
        mPortMaster.setEnabled(installed && !busy && !downloading);
        final int disabled = busy ? R.string.subsystem_optimize_busy : downloading ? R.string.subsystem_download_pending
                : mAwaitSeq >= 0 ? R.string.subsystem_launch_checking : R.string.subsystem_launch_disabled;
        mEmulationStation.setSummary(installed && !busy && !downloading ? R.string.subsystem_open_es_summary : disabled);
        mPortMaster.setSummary(installed && !busy && !downloading ? R.string.subsystem_open_pm_summary : disabled);
        mDownload.setEnabled(false);
        if (busy) mDownload.setSummary(R.string.subsystem_optimize_busy);
        else if (downloading) mDownload.setSummary(R.string.subsystem_download_pending);
        else if (!online()) mDownload.setSummary(R.string.subsystem_download_offline);
        else if (imageUrl() == null) mDownload.setSummary(R.string.subsystem_download_unavailable);
        else if (!mWritableCard) mDownload.setSummary(R.string.subsystem_download_no_card);
        else { mDownload.setSummary(R.string.subsystem_download_online); mDownload.setEnabled(true); }
    }

    private boolean launch(String target) {
        refresh();
        final Resolution r = resolution();
        if (busy() || mAwaitSeq >= 0 || !"1".equals(r.image) || mPrefs.getLong(DOWNLOAD_ID, -1) != -1) {
            refresh(); message(R.string.subsystem_launch_disabled); return true;
        }
        try {
            mLaunchSeq = decimal(property(PROP + "run_seq"));
            SystemProperties.set(PORT_PROP, target);
            mLaunchPending = true; mLaunchDeadline = SystemClock.elapsedRealtime() + ACK_MS;
            SystemProperties.set(REQUEST_PROP, "run");
        } catch (RuntimeException e) {
            mLaunchPending = false; Log.w(TAG, "Could not request subsystem launch", e);
            message(R.string.subsystem_launch_failed);
        }
        refresh(); return true;
    }

    private void checkLaunchAcknowledgement() {
        if (!mLaunchPending) return;
        if (decimal(property(PROP + "run_seq")) > mLaunchSeq) {
            mLaunchPending = false;
            if (!"accepted".equals(property(PROP + "run_state"))) message(R.string.subsystem_launch_failed);
        } else if (SystemClock.elapsedRealtime() > mLaunchDeadline) {
            mLaunchPending = false; message(R.string.subsystem_launch_no_ack);
        }
    }

    private boolean optimize() {
        refresh();
        final Resolution r = resolution();
        final ImageRow source = row(r.resolved);
        if (busy() || mAwaitSeq >= 0 || !mWritableCard || !"1".equals(r.image)
                || source == null || !source.valid || source.optimized || mPrefs.getLong(DOWNLOAD_ID, -1) != -1) return true;
        final String job = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            SystemProperties.set(PROP + "opt_source", source.id);
            SystemProperties.set(PROP + "opt_request_id", job);
            mPrefs.edit().putString(JOB_ID, job).putLong(JOB_PENDING, System.currentTimeMillis() + ACK_MS)
                    .putString("opt_source_name", source.name).apply();
            SystemProperties.set(REQUEST_PROP, "optimize");
        } catch (RuntimeException e) {
            mPrefs.edit().remove(JOB_PENDING).apply();
            Log.w(TAG, "Could not request optimization", e); message(R.string.subsystem_optimize_failed);
        }
        refresh(); return true;
    }

    private void updateOptimize(Resolution r) {
        final String accepted = property(PROP + "opt_job");
        final String status = property(PROP + "opt_status");
        final String state = !accepted.isEmpty() && status.startsWith(accepted + ":")
                ? status.substring(accepted.length() + 1) : "";
        String expected = mPrefs.getString(JOB_ID, "");
        final long deadline = mPrefs.getLong(JOB_PENDING, 0);
        if (deadline != 0) {
            if (expected.equals(accepted) && !state.isEmpty()) mPrefs.edit().remove(JOB_PENDING).apply();
            else if (expected.equals(property(PROP + "opt_reject_job"))) {
                mPrefs.edit().remove(JOB_PENDING).apply(); message(R.string.subsystem_optimize_busy);
            } else if (System.currentTimeMillis() > deadline) {
                mPrefs.edit().remove(JOB_PENDING).apply(); message(R.string.subsystem_optimize_no_ack);
            }
        } else if (activeJob(state) && !accepted.isEmpty() && !accepted.equals(expected)) {
            expected = accepted; mPrefs.edit().putString(JOB_ID, accepted).apply(); // Reattach, never restart.
        }
        final boolean matching = !expected.isEmpty() && expected.equals(accepted);
        final boolean running = matching && activeJob(state);
        final boolean waiting = mPrefs.getLong(JOB_PENDING, 0) != 0;
        setKeepScreenOn(mResumed && (running || waiting));
        mOptimize.setActive(running || waiting);
        mOptimize.setEnabled(false);
        final String bytes = Formatter.formatFileSize(mContext, decimal(property(PROP + "opt_bytes")));
        final String elapsed = DateUtils.formatElapsedTime(decimal(property(PROP + "opt_elapsed")));
        if (running || waiting) {
            final String phase = getString("building".equals(state) ? R.string.subsystem_optimize_building_phase
                    : "verifying".equals(state) ? R.string.subsystem_optimize_verifying
                    : "publishing".equals(state) ? R.string.subsystem_optimize_finishing : R.string.subsystem_optimize_preparing);
            String summary = getString(R.string.subsystem_optimize_progress, phase, bytes, elapsed);
            final long required = decimal(property(PROP + "opt_required"));
            if (required > 0) summary += "\n" + getString(R.string.subsystem_optimize_budget,
                    Formatter.formatFileSize(mContext, required),
                    Formatter.formatFileSize(mContext, decimal(property(PROP + "opt_available"))));
            mOptimize.setSummary(summary);
            return;
        }
        if (matching && ("done".equals(state) || "failed".equals(state) || "interrupted".equals(state))) {
            if (!(accepted + state).equals(mLastTerminalJob)) {
                mLastTerminalJob = accepted + state;
                if ("done".equals(state)) enumerateImages(false);
            }
            if ("done".equals(state)) {
                final String source = mPrefs.getString("opt_source_name", "dArkOSen.img");
                mOptimize.setSummary(getString(R.string.subsystem_optimize_complete,
                        source.endsWith(".img") ? source.substring(0, source.length() - 4) + ".erofs.img" : source));
            } else mOptimize.setSummary(optimizeError(property(PROP + "opt_error")));
        } else mOptimize.setSummary(R.string.subsystem_optimize_time);
        final ImageRow source = row(r.resolved);
        if (busy() || mPrefs.getLong(DOWNLOAD_ID, -1) != -1) mOptimize.setSummary(R.string.subsystem_optimize_busy);
        else if (!mWritableCard) mOptimize.setSummary(R.string.subsystem_optimize_no_card);
        else if (source != null && source.optimized) mOptimize.setSummary(R.string.subsystem_optimize_already);
        else if (mAwaitSeq < 0 && "1".equals(r.image) && source != null && source.valid) {
            mOptimize.setEnabled(true);
            if (!matching || !("done".equals(state) || "failed".equals(state) || "interrupted".equals(state))) {
                mOptimize.setSummary(getString(R.string.subsystem_optimize_summary, source.name) + "\n" + getString(R.string.subsystem_optimize_time));
            }
        }
    }

    private String optimizeError(String error) {
        final int text;
        switch (error) {
            case "no_space":
                return getString(R.string.subsystem_optimize_space,
                        Formatter.formatFileSize(mContext, decimal(property(PROP + "opt_required"))),
                        Formatter.formatFileSize(mContext, decimal(property(PROP + "opt_available"))));
            case "no_card": case "selected_missing": case "no_image": text = R.string.subsystem_optimize_no_card; break;
            case "source_changed": text = R.string.subsystem_optimize_source_changed; break;
            case "invalid_image": text = R.string.subsystem_optimize_invalid; break;
            case "output_exists": text = R.string.subsystem_optimize_exists; break;
            case "tool_missing": text = R.string.subsystem_optimize_tool_missing; break;
            case "busy": text = R.string.subsystem_optimize_busy; break;
            case "mkfs_failed": text = R.string.subsystem_optimize_mkfs_failed; break;
            case "verify_failed": text = R.string.subsystem_optimize_verify_failed; break;
            case "io_error": text = R.string.subsystem_optimize_io_error; break;
            case "interrupted": text = R.string.subsystem_optimize_interrupted; break;
            case "power_hold": text = R.string.subsystem_optimize_power_hold; break;
            case "already_optimized": text = R.string.subsystem_optimize_already; break;
            case "name_too_long": text = R.string.subsystem_optimize_name_too_long; break;
            case "data_space": text = R.string.subsystem_optimize_data_space; break;
            case "file_limit": text = R.string.subsystem_optimize_file_limit; break;
            default: text = R.string.subsystem_optimize_failed;
        }
        return getString(text);
    }

    private void setKeepScreenOn(boolean active) {
        if (getActivity() instanceof SubsystemSetupActivity) ((SubsystemSetupActivity) getActivity()).setOptimizationActive(active);
    }

    private String property(String name) {
        try { return SystemProperties.get(name, ""); }
        catch (RuntimeException e) { Log.w(TAG, "Could not read " + name, e); return ""; }
    }

    private boolean online() {
        try {
            final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
            if (cm == null) return false;
            final Network network = cm.getActiveNetwork();
            final NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) { return false; }
    }

    private Uri imageUrl() {
        final String url = property(URL_PROP).trim();
        if (url.isEmpty()) return null;
        final Uri uri = Uri.parse(url);
        final String scheme = uri.getScheme();
        return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && uri.getHost() != null && !uri.getHost().isEmpty() ? uri : null;
    }

    private Card gamesCard(boolean writable) {
        try {
            String id = property("sys.andr36oid.easyroms.id");
            if (id.isEmpty() && !"1".equals(property("sys.andr36oid.easyroms.ready"))) id = "public:179,7";
            final StorageManager sm = mContext.getSystemService(StorageManager.class);
            if (sm == null || id.isEmpty()) return null;
            for (VolumeInfo volume : sm.getVolumes()) {
                if (id.equals(volume.getId()) && volume.getType() == VolumeInfo.TYPE_PUBLIC
                        && volume.isMountedReadable() && (!writable || volume.isMountedWritable())
                        && volume.getPath() != null && volume.getFsUuid() != null && !volume.getFsUuid().isEmpty()) return new Card(volume);
            }
        } catch (RuntimeException e) { Log.w(TAG, "Could not find the games card", e); }
        return null;
    }

    private boolean downloadImage() {
        if (busy() || mPrefs.getLong(DOWNLOAD_ID, -1) != -1 || !online()) {
            refresh();
            return true;
        }
        final Uri url = imageUrl();
        if (url == null) {
            message(R.string.subsystem_download_unavailable);
            refresh();
            return true;
        }
        final Card card = gamesCard(true);
        if (card == null) {
            message(R.string.subsystem_download_no_card);
            refresh();
            return true;
        }
        try {
            final File directory = new File(card.path, "subsystem/darkosen");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Could not create image directory");
            }
            if (new File(directory, IMAGE_NAME).exists()) {
                message(R.string.subsystem_download_exists);
                return true;
            }
            if (new File(directory, PARTIAL_NAME).exists()) {
                throw new IllegalStateException("An unfinished image is already present");
            }
            final DownloadManager dm = getActivity().getSystemService(DownloadManager.class);
            if (dm == null) throw new IllegalStateException("DownloadManager unavailable");
            final DownloadManager.Request request = new DownloadManager.Request(url)
                    .setTitle(getString(R.string.subsystem_title))
                    .setDescription(getString(R.string.subsystem_download_online))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationUri(Uri.fromFile(new File(directory, PARTIAL_NAME)));
            final long id = dm.enqueue(request);
            mPrefs.edit().putLong(DOWNLOAD_ID, id)
                    .putString(DOWNLOAD_DIRECTORY, directory.getAbsolutePath()).apply();
            message(R.string.subsystem_download_started);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not enqueue image download", e);
            message(R.string.subsystem_download_failed);
        }
        refresh();
        return true;
    }

    private void checkDownload() {
        final long id = mPrefs.getLong(DOWNLOAD_ID, -1);
        if (id == -1) return;
        try {
            final DownloadManager dm = getActivity().getSystemService(DownloadManager.class);
            if (dm == null) throw new IllegalStateException("DownloadManager unavailable");
            try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor == null || !cursor.moveToFirst()) {
                    throw new IllegalStateException("Download no longer exists");
                }
                final int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_FAILED) {
                    throw new IllegalStateException("Image download failed");
                }
                if (status != DownloadManager.STATUS_SUCCESSFUL) return;
            }
            // Finalize only a complete image. If the page was closed, this runs on its next visit.
            final String path = mPrefs.getString(DOWNLOAD_DIRECTORY, "");
            if (path.isEmpty()) throw new IllegalStateException("Missing download directory");
            final File directory = new File(path);
            final File image = new File(directory, IMAGE_NAME);
            if (image.exists() || !new File(directory, PARTIAL_NAME).renameTo(image)) {
                throw new IllegalStateException("Could not finalize image without replacing a file");
            }
            clearDownload();
            enumerateImages(false);
            message(R.string.subsystem_download_complete);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not finish image download", e);
            clearDownload();
            message(R.string.subsystem_download_failed);
        }
    }

    private void clearDownload() {
        mPrefs.edit().remove(DOWNLOAD_ID).remove(DOWNLOAD_DIRECTORY).apply();
    }

    private void message(int text) {
        final Context c = getActivity();
        if (c != null) Toast.makeText(c, text, Toast.LENGTH_LONG).show();
    }
}
