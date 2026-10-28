package org.andr36oid.subsystemsetup;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Base64;

/** A bounded tail, including truncation/rotation and a boundary that hides the last boot. */
final class BootLogReader {
    static final int MAX_BYTES = 64 * 1024;
    private final File mFile;
    private long mStart;
    private String mFileKey;
    private byte[] mBoundary = new byte[0];

    BootLogReader(File file) {
        mFile = file;
    }

    void beginLaunch() {
        mStart = 0;
        mBoundary = new byte[0];
        mFileKey = null;
        try (RandomAccessFile input = new RandomAccessFile(mFile, "r")) {
            mStart = input.length();
            mFileKey = fileKey();
            mBoundary = boundary(input, mStart);
        } catch (IOException | SecurityException ignored) {
            // A log which does not exist yet is normal before the first boot.
        }
    }

    String read() throws IOException {
        if (!mFile.exists()) return "";
        try (RandomAccessFile input = new RandomAccessFile(mFile, "r")) {
            final long length = input.length();
            final String key = fileKey();
            if (length < mStart || (mFileKey != null && !mFileKey.equals(key))
                    || !Arrays.equals(mBoundary, boundary(input, mStart))) {
                mStart = 0;
                mBoundary = new byte[0];
            }
            mFileKey = key;
            final long from = Math.max(mStart, length - MAX_BYTES);
            input.seek(from);
            final byte[] bytes = new byte[(int) (length - from)];
            input.readFully(bytes);
            int start = 0;
            // Start at a complete line when trimming a long log (also keeps UTF-8 intact).
            if (from > mStart) {
                while (start < bytes.length && bytes[start] != '\n') start++;
                if (start < bytes.length) start++;
                else start = 0;
            }
            return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
        }
    }

    String save() {
        return mStart + "\n" + (mFileKey == null ? "" : mFileKey) + "\n"
                + Base64.getEncoder().encodeToString(mBoundary);
    }

    void restore(String state) {
        try {
            final String[] parts = state.split("\n", -1);
            if (parts.length != 3) return;
            final long start = Long.parseLong(parts[0]);
            final byte[] boundary = Base64.getDecoder().decode(parts[2]);
            if (start < 0 || boundary.length != Math.min(64, start)) return;
            mStart = start;
            mFileKey = parts[1].isEmpty() ? null : parts[1];
            mBoundary = boundary;
        } catch (IllegalArgumentException ignored) {
            // An obsolete or incomplete saved boundary starts at the available tail.
        }
    }

    private String fileKey() throws IOException {
        final Object key = Files.readAttributes(mFile.toPath(), BasicFileAttributes.class).fileKey();
        return key == null ? null : key.toString();
    }

    private static byte[] boundary(RandomAccessFile input, long end) throws IOException {
        final int size = (int) Math.min(64, end);
        final byte[] bytes = new byte[size];
        input.seek(end - size);
        input.readFully(bytes);
        return bytes;
    }
}
