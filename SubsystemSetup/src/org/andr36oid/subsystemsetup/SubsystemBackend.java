package org.andr36oid.subsystemsetup;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Property client only. Mounting, scanning and display ownership belong to the broker. */
final class SubsystemBackend {
    static final String DEFAULT = "persist.andr36oid.subsystem.default";
    static final String DEFAULT_TARGET = "persist.andr36oid.subsystem.default_target";
    static final String ROOTFS = "sys.andr36oid.subsystem.rootfs";
    static final String HANDOFF = "sys.andr36oid.subsystem.handoff";
    static final String PORT = "sys.andr36oid.subsystem.port";
    static final String REQUEST = "sys.andr36oid.subsystem.request";
    static final String IMAGE = "sys.andr36oid.subsystem.image";
    static final String SESSION = "sys.andr36oid.subsystem.session";
    private static final String URL = "persist.andr36oid.subsystem.image_url";
    private static final String TAG = "SubsystemSetup";
    private static final String DOWNLOAD_ID = "download_id";
    private static final String DOWNLOAD_DIRECTORY = "download_directory";
    private static final String IMAGE_NAME = "darkosen.img";
    private static final String PARTIAL_NAME = "darkosen.img.download";

    static final class Snapshot {
        String image = "", session = "", log = "";
        boolean online, downloading, logUnreadable;
        int downloadText = R.string.subsystem_download_offline;
        int downloadPercent = -1, notice;
        boolean canDownload;
    }

    private final Context mContext;
    private final SharedPreferences mPrefs;
    final BootLogReader log = new BootLogReader(new File("/data/local/tmp/portmaster/boot.log"));

    SubsystemBackend(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences("subsystem_setup", Context.MODE_PRIVATE);
        log.restore(mPrefs.getString("boot_log_boundary", ""));
    }

    static String get(String key) {
        try {
            return SystemProperties.get(key, "");
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read " + key, e);
            return "";
        }
    }

    static boolean set(String key, String value) {
        try {
            SystemProperties.set(key, value);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot write " + key, e);
            return false;
        }
    }

    static String defaultTarget() {
        return "PM".equals(get(DEFAULT_TARGET)) ? "PM" : "ES";
    }

    // Called on the activity's single IO executor, including DownloadManager and log IO.
    Snapshot poll(boolean readLog) {
        final Snapshot s = new Snapshot();
        checkDownload(s);
        s.image = get(IMAGE);
        s.session = get(SESSION);
        s.online = online();
        s.downloading = mPrefs.getLong(DOWNLOAD_ID, -1) != -1;
        if (s.downloading) s.downloadText = R.string.subsystem_download_pending;
        else if (!s.online) s.downloadText = R.string.subsystem_download_offline;
        else if (imageUrl() == null) s.downloadText = R.string.subsystem_download_unavailable;
        else if (gamesCard() == null) s.downloadText = R.string.subsystem_download_no_card;
        else {
            s.downloadText = R.string.subsystem_download_online;
            s.canDownload = true;
        }
        if (readLog) {
            try {
                s.log = log.read();
            } catch (IOException | SecurityException e) {
                s.logUnreadable = true;
            }
        }
        return s;
    }

    private boolean online() {
        try {
            final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
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
        final String url = get(URL).trim();
        if (url.isEmpty()) return null;
        final Uri uri = Uri.parse(url);
        final String scheme = uri.getScheme();
        return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                && uri.getHost() != null && !uri.getHost().isEmpty() ? uri : null;
    }

    private File gamesCard() {
        try {
            String id = get("sys.andr36oid.easyroms.id");
            if (id.isEmpty() && !"1".equals(get("sys.andr36oid.easyroms.ready"))) {
                id = "public:179,7";
            }
            final StorageManager sm = mContext.getSystemService(StorageManager.class);
            if (sm == null || id.isEmpty()) return null;
            for (VolumeInfo volume : sm.getVolumes()) {
                if (id.equals(volume.getId()) && volume.getType() == VolumeInfo.TYPE_PUBLIC
                        && volume.isMountedWritable()) return volume.getPath();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot find the games card", e);
        }
        return null;
    }

    int downloadImage() {
        if (mPrefs.getLong(DOWNLOAD_ID, -1) != -1) return R.string.subsystem_download_pending;
        if (!online()) return R.string.subsystem_download_offline;
        final Uri url = imageUrl();
        if (url == null) return R.string.subsystem_download_unavailable;
        final File card = gamesCard();
        if (card == null) return R.string.subsystem_download_no_card;
        try {
            final File directory = new File(card, "subsystem/darkosen");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Cannot create image directory");
            }
            if (new File(directory, IMAGE_NAME).exists()) return R.string.subsystem_download_exists;
            if (new File(directory, PARTIAL_NAME).exists()) {
                return R.string.subsystem_download_partial;
            }
            final DownloadManager dm = mContext.getSystemService(DownloadManager.class);
            if (dm == null) throw new IllegalStateException("DownloadManager unavailable");
            final DownloadManager.Request request = new DownloadManager.Request(url)
                    .setTitle(mContext.getString(R.string.subsystem_title))
                    .setDescription(mContext.getString(R.string.subsystem_download_online))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationUri(Uri.fromFile(new File(directory, PARTIAL_NAME)));
            final long id = dm.enqueue(request);
            mPrefs.edit().putLong(DOWNLOAD_ID, id)
                    .putString(DOWNLOAD_DIRECTORY, directory.getAbsolutePath()).apply();
            return R.string.subsystem_download_started;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot start image download", e);
            return R.string.subsystem_download_failed;
        }
    }

    private void checkDownload(Snapshot s) {
        final long id = mPrefs.getLong(DOWNLOAD_ID, -1);
        if (id == -1) return;
        try {
            final DownloadManager dm = mContext.getSystemService(DownloadManager.class);
            if (dm == null) throw new IllegalStateException("DownloadManager unavailable");
            try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor == null || !cursor.moveToFirst()) {
                    throw new IllegalStateException("Download no longer exists");
                }
                final int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_FAILED) throw new IllegalStateException("Download failed");
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    final long done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    final long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if (total > 0) s.downloadPercent = (int) Math.min(100, 100.0 * done / total);
                    return;
                }
            }
            final String path = mPrefs.getString(DOWNLOAD_DIRECTORY, "");
            if (path.isEmpty()) throw new IllegalStateException("Missing download directory");
            final File directory = new File(path);
            final File image = new File(directory, IMAGE_NAME);
            final File partial = new File(directory, PARTIAL_NAME);
            if (image.exists() || partial.length() == 0) {
                throw new IllegalStateException("Cannot finalize image without replacing a file");
            }
            Files.move(partial.toPath(), image.toPath());
            clearDownload();
            s.notice = set(REQUEST, "scan") ? R.string.subsystem_download_complete
                    : R.string.subsystem_scan_failed;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Cannot finish image download", e);
            clearDownload();
            s.notice = R.string.subsystem_download_failed;
        }
    }

    private void clearDownload() {
        mPrefs.edit().remove(DOWNLOAD_ID).remove(DOWNLOAD_DIRECTORY).apply();
    }

    List<File> storageRoots() {
        final Map<String, File> roots = new LinkedHashMap<>();
        addRoot(roots, gamesCard());
        try {
            final StorageManager sm = mContext.getSystemService(StorageManager.class);
            if (sm != null) {
                for (VolumeInfo volume : sm.getVolumes()) {
                    if (volume.getType() == VolumeInfo.TYPE_PUBLIC && volume.isMountedReadable()) {
                        addRoot(roots, volume.getPath());
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot enumerate volumes", e);
        }
        final File[] storage = new File("/storage").listFiles();
        if (storage != null) {
            for (File file : storage) {
                if (!"emulated".equals(file.getName()) && !"self".equals(file.getName())) {
                    addRoot(roots, file);
                }
            }
        }
        for (String path : new String[] {"/storage/emulated/0/subsystem", "/EASYROMS", "/SD2",
                "/USB", "/mnt/SD2", "/mnt/USB"}) addRoot(roots, new File(path));
        return new ArrayList<>(roots.values());
    }

    private static void addRoot(Map<String, File> roots, File file) {
        if (file == null) return;
        try {
            final File canonical = file.getCanonicalFile();
            if (canonical.isDirectory() && canonical.canRead()) {
                roots.put(canonical.getAbsolutePath(), canonical);
            }
        } catch (IOException | SecurityException ignored) {
            // Unmounted and inaccessible locations simply do not appear in the picker.
        }
    }

    String storageLabel(File root) {
        try {
            final File games = gamesCard();
            if (games != null && games.getCanonicalFile().equals(root)) {
                return mContext.getString(R.string.subsystem_games_card);
            }
            final StorageManager sm = mContext.getSystemService(StorageManager.class);
            if (sm != null) {
                for (VolumeInfo volume : sm.getVolumes()) {
                    final File path = volume.getPath();
                    if (path != null && path.getCanonicalFile().equals(root)) {
                        final String description = sm.getBestVolumeDescription(volume);
                        if (description != null && !description.isEmpty()) return description;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A volume disappearing during enumeration still has its path as a label.
        }
        return root.getName();
    }

    static boolean eligible(File file, List<File> roots) {
        try {
            final File canonical = file.getCanonicalFile();
            if (!canonical.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".img")
                    || !canonical.isFile() || !canonical.canRead() || canonical.length() == 0
                    || canonical.getAbsolutePath().getBytes(StandardCharsets.UTF_8).length
                            > SystemProperties.PROP_VALUE_MAX) return false;
            return inside(canonical, roots);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    static boolean inside(File file, List<File> roots) throws IOException {
        final String path = file.getCanonicalPath();
        for (File root : roots) {
            final String base = root.getCanonicalPath();
            if (path.equals(base) || path.startsWith(base + File.separator)) return true;
        }
        return false;
    }
}
