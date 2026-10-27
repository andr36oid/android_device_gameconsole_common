package org.andr36oid.subsystemsetup;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.widget.Toast;

import java.io.File;

/** Settings > System. Only the root broker scans images and launches the subsystem. */
public class SubsystemSetupFragment extends PreferenceFragment {
    private static final String TAG = "SubsystemSetup";
    private static final String IMAGE_PROP = "sys.andr36oid.subsystem.image";
    private static final String REQUEST_PROP = "sys.andr36oid.subsystem.request";
    private static final String PORT_PROP = "sys.andr36oid.subsystem.port";
    private static final String URL_PROP = "persist.andr36oid.subsystem.image_url";
    private static final String SCAN_REQUESTED = "scan_requested";
    private static final String DOWNLOAD_ID = "download_id";
    private static final String DOWNLOAD_DIRECTORY = "download_directory";
    private static final String IMAGE_NAME = "darkosen.img";
    // The broker searches *.img, so an unfinished download must have another suffix.
    private static final String PARTIAL_NAME = "darkosen.img.download";
    private static final long REFRESH_MS = 2000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            if (getActivity() == null) return;
            checkDownload();
            refresh();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };
    private SharedPreferences mPrefs;
    private Preference mStatus;
    private Preference mEmulationStation;
    private Preference mPortMaster;
    private Preference mDownload;
    private boolean mScanRequested;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context c = getActivity();
        mPrefs = c.getSharedPreferences("subsystem_setup", Context.MODE_PRIVATE);
        mScanRequested = savedInstanceState != null
                && savedInstanceState.getBoolean(SCAN_REQUESTED);
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        setPreferenceScreen(screen);

        final Preference intro = new Preference(c);
        intro.setSummary(R.string.subsystem_intro);
        intro.setSelectable(false);
        screen.addPreference(intro);

        final Preference about = new Preference(c);
        about.setTitle(R.string.subsystem_about_title);
        about.setSummary(R.string.subsystem_about);
        about.setSelectable(false);
        screen.addPreference(about);

        mStatus = new Preference(c);
        mStatus.setTitle(R.string.subsystem_status);
        mStatus.setSelectable(false);
        screen.addPreference(mStatus);

        final PreferenceCategory setup = new PreferenceCategory(c);
        setup.setTitle(R.string.subsystem_setup);
        screen.addPreference(setup);
        setup.addPreference(instruction(c, R.string.subsystem_step_image,
                R.string.subsystem_step_image_summary));
        setup.addPreference(instruction(c, R.string.subsystem_step_portmaster,
                R.string.subsystem_step_portmaster_summary));
        setup.addPreference(instruction(c, R.string.subsystem_step_ports,
                R.string.subsystem_step_ports_summary));

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
        refresh();
    }

    private Preference instruction(Context c, int title, int summary) {
        final Preference row = new Preference(c);
        row.setTitle(title);
        row.setSummary(summary);
        row.setSelectable(false);
        return row;
    }

    @Override
    public void onResume() {
        super.onResume();
        mHandler.post(mRefresh);
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mRefresh);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SCAN_REQUESTED, mScanRequested);
    }

    private void refresh() {
        if (getActivity() == null) return;
        final String image = property(IMAGE_PROP);
        final boolean installed = "1".equals(image);
        mStatus.setSummary(installed ? R.string.subsystem_installed
                : image.isEmpty() ? R.string.subsystem_checking : R.string.subsystem_missing);
        if (image.isEmpty() && !mScanRequested) {
            mScanRequested = true;
            requestScan();
        }

        final int disabled = image.isEmpty() ? R.string.subsystem_launch_checking
                : R.string.subsystem_launch_disabled;
        // Also avoid launching against a stale installed property while downloading.
        final boolean downloading = mPrefs.getLong(DOWNLOAD_ID, -1) != -1;
        mEmulationStation.setEnabled(installed && !downloading);
        mPortMaster.setEnabled(installed && !downloading);
        mEmulationStation.setSummary(downloading ? R.string.subsystem_download_pending
                : installed ? R.string.subsystem_open_es_summary : disabled);
        mPortMaster.setSummary(downloading ? R.string.subsystem_download_pending
                : installed ? R.string.subsystem_open_pm_summary : disabled);

        mDownload.setEnabled(false);
        if (downloading) {
            mDownload.setSummary(R.string.subsystem_download_pending);
        } else if (!online()) {
            mDownload.setSummary(R.string.subsystem_download_offline);
        } else if (imageUrl() == null) {
            mDownload.setSummary(R.string.subsystem_download_unavailable);
        } else if (gamesCard() == null) {
            mDownload.setSummary(R.string.subsystem_download_no_card);
        } else {
            mDownload.setSummary(R.string.subsystem_download_online);
            mDownload.setEnabled(true);
        }
    }

    private boolean launch(String target) {
        if (!"1".equals(property(IMAGE_PROP)) || mPrefs.getLong(DOWNLOAD_ID, -1) != -1) {
            refresh();
            message(R.string.subsystem_launch_disabled);
            return true;
        }
        try {
            // Match LaunchPortActivity: set the target before triggering the root broker.
            SystemProperties.set(PORT_PROP, target);
            SystemProperties.set(REQUEST_PROP, "run");
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not request subsystem launch", e);
            message(R.string.subsystem_launch_failed);
        }
        return true;
    }

    private void requestScan() {
        try {
            SystemProperties.set(REQUEST_PROP, "scan");
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not request image scan", e);
            message(R.string.subsystem_scan_failed);
        }
    }

    private String property(String name) {
        try {
            return SystemProperties.get(name, "");
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read " + name, e);
            return "";
        }
    }

    private boolean online() {
        try {
            final ConnectivityManager cm = getActivity().getSystemService(ConnectivityManager.class);
            if (cm == null) return false;
            final Network network = cm.getActiveNetwork();
            final NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Uri imageUrl() {
        final String url = property(URL_PROP).trim();
        if (url.isEmpty()) return null;
        final Uri uri = Uri.parse(url);
        final String scheme = uri.getScheme();
        return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && uri.getHost() != null && !uri.getHost().isEmpty() ? uri : null;
    }

    private File gamesCard() {
        try {
            // Match SaveBackup.MainFragment: use init's selected card, including older boots.
            String id = property("sys.andr36oid.easyroms.id");
            if (id.isEmpty() && !"1".equals(property("sys.andr36oid.easyroms.ready"))) {
                id = "public:179,7";
            }
            final StorageManager sm = getActivity().getSystemService(StorageManager.class);
            if (sm == null || id.isEmpty()) return null;
            for (VolumeInfo volume : sm.getVolumes()) {
                if (id.equals(volume.getId()) && volume.getType() == VolumeInfo.TYPE_PUBLIC
                        && volume.isMountedWritable()) {
                    return volume.getPath();
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not find the games card", e);
        }
        return null;
    }

    private boolean downloadImage() {
        if (mPrefs.getLong(DOWNLOAD_ID, -1) != -1 || !online()) {
            refresh();
            return true;
        }
        final Uri url = imageUrl();
        if (url == null) {
            message(R.string.subsystem_download_unavailable);
            refresh();
            return true;
        }
        final File card = gamesCard();
        if (card == null) {
            message(R.string.subsystem_download_no_card);
            refresh();
            return true;
        }
        try {
            final File directory = new File(card, "subsystem/darkosen");
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
            requestScan();
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
