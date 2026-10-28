package org.andr36oid.subsystemsetup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

/** Host regression checks for real log lifecycle events, without an Android runtime. */
public final class BootLogReaderTest {
    public static void main(String[] args) throws Exception {
        final Path directory = Files.createTempDirectory("subsystem-log-test-");
        try {
            final Path path = directory.resolve("boot.log");
            final BootLogReader reader = new BootLogReader(path.toFile());
            equal("", reader.read(), "log not created yet");
            reader.beginLaunch();
            write(path, "first boot\n");
            equal("first boot\n", reader.read(), "log created after launch");

            reader.beginLaunch();
            equal("", reader.read(), "previous session hidden");
            append(path, "new session\n");
            equal("new session\n", reader.read(), "new output appended to old log");
            append(path, "Linux démarré · 日本語\n");
            equal("new session\nLinux démarré · 日本語\n", reader.read(), "Unicode output preserved");

            final BootLogReader restored = new BootLogReader(path.toFile());
            restored.restore(reader.save());
            equal(reader.read(), restored.read(), "boundary survives activity/process recreation");

            write(path, "truncated\n");
            equal("truncated\n", restored.read(), "truncate in place");
            reader.beginLaunch();
            write(path, "replacement output longer than the old log\n");
            equal("replacement output longer than the old log\n", reader.read(),
                    "truncate and rewrite between polls, larger than old offset");

            reader.beginLaunch();
            Files.move(path, directory.resolve("boot.log.1"));
            write(path, "rotated\n");
            equal("rotated\n", reader.read(), "rotation replaces inode");
            Files.delete(path);
            equal("", reader.read(), "rotation gap");
            write(path, "created again\n");
            equal("created again\n", reader.read(), "log reappears after rotation gap");

            final StringBuilder large = new StringBuilder();
            for (int i = 0; i < 12000; i++) large.append("boot ").append(i).append(" · 日本語\n");
            write(path, large.toString());
            final String tail = new BootLogReader(path.toFile()).read();
            check(tail.getBytes(StandardCharsets.UTF_8).length <= BootLogReader.MAX_BYTES, "bounded tail");
            check(tail.startsWith("boot "), "tail starts on a complete line");
            check(tail.endsWith("boot 11999 · 日本語\n"), "latest output retained");
            check(!tail.contains("\uFFFD"), "UTF-8 not split by tail boundary");

            final BootLogReader invalid = new BootLogReader(path.toFile());
            invalid.restore("invalid state");
            equal(tail, invalid.read(), "malformed state falls back to the latest tail");
            invalid.restore("-1\nkey\n");
            equal(tail, invalid.read(), "negative saved offset ignored");

            final BootLogReader inaccessible = new BootLogReader(directory.toFile());
            boolean failed = false;
            try { inaccessible.read(); } catch (IOException expected) { failed = true; }
            check(failed, "unreadable log is reported, not mistaken for an empty boot");
            System.out.println("BootLogReader: 18 lifecycle and tail checks passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void write(Path path, String value) throws IOException {
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void append(Path path, String value) throws IOException {
        Files.write(path, value.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    private static void equal(String expected, String actual, String description) {
        check(expected.equals(actual), description);
    }

    private static void check(boolean pass, String description) {
        if (!pass) throw new AssertionError(description);
    }
}
