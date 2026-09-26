package org.andr36oid.buttonmapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Named button layouts to switch between. The standard layout always comes first and can't be
 * changed, the user's profiles are stored one per line as "id TAB name TAB remap".
 */
final class ButtonProfiles {
    static final String STANDARD_ID = "standard";
    static final int MAX_NAME_LENGTH = 40;
    static final int MAX_PROFILES = 20;

    static final class Profile {
        final String id;
        // Null for the standard profile, its name is translated.
        String name;
        // Button remap in the format of ButtonRemap, empty for the stock layout.
        String remap;

        Profile(String id, String name, String remap) {
            this.id = id;
            this.name = name;
            this.remap = remap;
        }

        boolean isStandard() {
            return STANDARD_ID.equals(id);
        }
    }

    private final List<Profile> mProfiles = new ArrayList<>();
    private String mActiveId = STANDARD_ID;

    ButtonProfiles() {
        mProfiles.add(new Profile(STANDARD_ID, null, ""));
    }

    static ButtonProfiles parse(String profiles, String activeId) {
        final ButtonProfiles result = new ButtonProfiles();
        if (profiles != null) {
            for (String line : profiles.split("\n")) {
                final String[] fields = line.split("\t", -1);
                if (fields.length != 3 || fields[0].isEmpty() || STANDARD_ID.equals(fields[0])
                        || result.find(fields[0]) != null) {
                    continue;
                }
                final String name = sanitizeName(fields[1]);
                result.mProfiles.add(new Profile(fields[0], name.isEmpty() ? fields[0] : name,
                        fields[2].trim()));
            }
        }
        if (activeId != null && result.find(activeId) != null) {
            result.mActiveId = activeId;
        }
        return result;
    }

    /** Returns the user's profiles in the stored format, null when there are none. */
    String serialize() {
        final StringBuilder sb = new StringBuilder();
        for (Profile profile : mProfiles) {
            if (profile.isStandard()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(profile.id).append('\t').append(profile.name).append('\t')
                    .append(profile.remap);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    ButtonProfiles copy() {
        final ButtonProfiles copy = new ButtonProfiles();
        copy.mProfiles.clear();
        for (Profile profile : mProfiles) {
            copy.mProfiles.add(new Profile(profile.id, profile.name, profile.remap));
        }
        copy.mActiveId = mActiveId;
        return copy;
    }

    /** All profiles, the standard one first. */
    List<Profile> getAll() {
        return Collections.unmodifiableList(mProfiles);
    }

    Profile find(String id) {
        for (Profile profile : mProfiles) {
            if (profile.id.equals(id)) {
                return profile;
            }
        }
        return null;
    }

    Profile getActive() {
        return find(mActiveId);
    }

    void setActive(String id) {
        if (find(id) != null) {
            mActiveId = id;
        }
    }

    boolean isFull() {
        return mProfiles.size() > MAX_PROFILES;
    }

    /** Adds a profile after the others, without activating it. */
    Profile create(String name, String remap) {
        int id = 1;
        for (Profile profile : mProfiles) {
            try {
                id = Math.max(id, Integer.parseInt(profile.id) + 1);
            } catch (NumberFormatException e) {
                // The standard profile.
            }
        }
        final Profile profile = new Profile(Integer.toString(id), sanitizeName(name), remap);
        mProfiles.add(profile);
        return profile;
    }

    /** Renames a profile, returns false if the name is empty or the profile can't be renamed. */
    boolean rename(String id, String name) {
        final Profile profile = find(id);
        final String sanitized = sanitizeName(name);
        if (profile == null || profile.isStandard() || sanitized.isEmpty()) {
            return false;
        }
        profile.name = sanitized;
        return true;
    }

    /** Deletes a profile, the standard one becomes active if it was. */
    void delete(String id) {
        final Profile profile = find(id);
        if (profile == null || profile.isStandard()) {
            return;
        }
        mProfiles.remove(profile);
        if (id.equals(mActiveId)) {
            mActiveId = STANDARD_ID;
        }
    }

    /** Returns the first "Profile N" style name that is not taken, {@code format} has a %d. */
    String nextName(String format) {
        for (int number = 1; ; number++) {
            final String name = String.format(format, number);
            boolean taken = false;
            for (Profile profile : mProfiles) {
                if (name.equals(profile.name)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return name;
            }
        }
    }

    /** Returns the name without line breaks, tabs and other control characters. */
    static String sanitizeName(String name) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
        }
        String sanitized = sb.toString().trim();
        if (sanitized.length() > MAX_NAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_NAME_LENGTH).trim();
        }
        return sanitized;
    }
}
