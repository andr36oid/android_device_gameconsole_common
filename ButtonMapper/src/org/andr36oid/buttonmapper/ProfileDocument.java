package org.andr36oid.buttonmapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Bounded, versioned UTF-8 interchange of named layouts, never live/Fn/rescue settings. */
final class ProfileDocument {
    static final int MAX_BYTES = 65536;
    static final String HEADER = "andr36oid-button-profiles-1";

    private ProfileDocument() { }

    static byte[] export(ButtonProfiles profiles, Predicate<String> usable) throws IOException {
        final StringBuilder document = new StringBuilder(HEADER).append('\n');
        final Set<String> ids = new HashSet<>();
        int count = 0;
        for (ButtonProfiles.Profile profile : profiles.getAll()) {
            if (profile.isStandard()) continue;
            validate(profile.id, profile.name, profile.remap, usable);
            if (!ids.add(profile.id) || ++count > ButtonProfiles.MAX_PROFILES) {
                throw new IOException("Too many or duplicate profiles");
            }
            document.append(profile.id).append('\t').append(profile.name).append('\t')
                    .append(profile.remap).append('\n');
        }
        if (count == 0) throw new IOException("No named profiles");
        final ByteBuffer bytes;
        try {
            bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(document));
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid UTF-8 text", e);
        }
        if (bytes.remaining() > MAX_BYTES) throw new IOException("Profile document too large");
        final byte[] out = new byte[bytes.remaining()];
        bytes.get(out);
        return out;
    }

    static ButtonProfiles read(InputStream input, Predicate<String> usable) throws IOException {
        if (input == null) throw new IOException("No input stream");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (bytes.size() + n > MAX_BYTES) throw new IOException("Profile document too large");
            bytes.write(buffer, 0, n);
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Invalid UTF-8 document", e);
        }
        final String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        if (lines.length < 2 || !HEADER.equals(lines[0])) throw new IOException("Unknown profile format");
        final StringBuilder stored = new StringBuilder();
        final Set<String> ids = new HashSet<>();
        int count = 0;
        for (int i = 1; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) continue;
            final String[] fields = lines[i].split("\t", -1);
            if (fields.length != 3) throw new IOException("Invalid profile entry");
            validate(fields[0], fields[1], fields[2], usable);
            if (!ids.add(fields[0]) || ++count > ButtonProfiles.MAX_PROFILES) {
                throw new IOException("Too many or duplicate profiles");
            }
            if (stored.length() > 0) stored.append('\n');
            stored.append(lines[i]);
        }
        if (count == 0) throw new IOException("No named profiles");
        return ButtonProfiles.parse(stored.toString(), ButtonProfiles.STANDARD_ID);
    }

    static ButtonProfiles append(ButtonProfiles snapshot, ButtonProfiles imported) throws IOException {
        // Validate the entire import before changing even the in-memory replacement.
        export(imported, value -> true);
        final int count = snapshot.getAll().size() + imported.getAll().size() - 2;
        if (count > ButtonProfiles.MAX_PROFILES) throw new IOException("Profile store full");
        final ButtonProfiles replacement = snapshot.copy();
        for (ButtonProfiles.Profile profile : imported.getAll()) {
            if (!profile.isStandard()) replacement.create(profile.name, profile.remap);
        }
        return replacement;
    }

    private static void validate(String id, String name, String remap, Predicate<String> usable)
            throws IOException {
        if (id == null || !id.matches("[1-9][0-9]{0,9}")) throw new IOException("Invalid profile ID");
        try {
            if (Integer.parseInt(id) <= 0) throw new IOException("Invalid profile ID");
        } catch (NumberFormatException e) { throw new IOException("Invalid profile ID", e); }
        if (name == null || name.isEmpty() || !name.equals(ButtonProfiles.sanitizeName(name))) {
            throw new IOException("Invalid profile name");
        }
        if (remap == null) throw new IOException("Missing layout");
        final Set<Integer> scans = new HashSet<>();
        int previous = 0;
        if (!remap.isEmpty()) for (String entry : remap.split(",", -1)) {
            final String[] fields = entry.split(":", -1);
            if (fields.length != 2 || !fields[0].matches("[1-9][0-9]{0,4}")) {
                throw new IOException("Invalid button entry");
            }
            final int scan = Integer.parseInt(fields[0]);
            if (scan > 65535 || !scans.add(scan) || scan <= previous) {
                throw new IOException("Invalid or duplicate scan code");
            }
            previous = scan;
            final String action = fields[1];
            if (ButtonMapping.FN_ACTION.equals(action) || (ButtonActions.find(action) == null
                    && !action.matches("BUTTON_([1-9]|1[0-6])"))) {
                throw new IOException("Unsupported action");
            }
        }
        if (!usable.test(remap)) throw new IOException("Unsupported or unsafe layout");
    }
}
