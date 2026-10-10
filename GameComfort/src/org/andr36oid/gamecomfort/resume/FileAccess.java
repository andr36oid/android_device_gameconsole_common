package org.andr36oid.gamecomfort.resume;

import java.util.List;
import java.util.Map;

/**
 * Reading other apps' files. On the console the root helper does it (the system user can't
 * read the internal storage or RetroArch's folders); the tests use a folder on the PC. All
 * paths are root paths (see {@link Paths}).
 */
public interface FileAccess {

    /** A file that is there: its modification time in seconds, size, and text if read. */
    final class Found {
        public final String path;
        public final long mtime;
        public final long size;
        public final String text;

        public Found(String path, long mtime, long size, String text) {
            this.path = path;
            this.mtime = mtime;
            this.size = size;
            this.text = text;
        }
    }

    /** Reads the small text files that exist, by path. Missing ones are left out. */
    Map<String, Found> read(List<String> paths);

    /** Size and time of the files that exist, by path. */
    Map<String, Found> stat(List<String> paths);

    /**
     * Files in the folders, and in their direct subfolders, whose name starts with one of the
     * prefixes (compared as plain text, not as patterns).
     */
    List<Found> list(List<String> dirs, List<String> prefixes);
}
