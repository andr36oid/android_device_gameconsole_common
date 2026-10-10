package org.andr36oid.guide;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemProperties;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The button chapters of the guide, read from res/raw/controls.txt. That file is the only
 * list of the console's button combinations; tests/check-controls.py compares it with
 * the key layout, the FN code, joyMouse and the seeded emulator settings, so the page can't
 * name a combination the build doesn't have. The file format is described at its top.
 */
final class Controls {
    private static final String TAG = "Controls";

    private final Context mContext;
    private final List<Chapters.Chapter> mChapters = new ArrayList<>();
    private Chapters.Chapter mChapter;
    private boolean mChapterHasRow;
    // What the conditions below the last item apply to
    private int mLevel;
    private boolean mTopicShown = true;
    private boolean mHeadingShown = true;
    private boolean mItemShown = true;
    private Chapters.Item mItem;

    private static final int LEVEL_TOPIC = 0;
    private static final int LEVEL_HEADING = 1;
    private static final int LEVEL_ITEM = 2;

    private Controls(Context context) {
        mContext = context;
    }

    static List<Chapters.Chapter> build(Context context) {
        return new Controls(context).read();
    }

    private List<Chapters.Chapter> read() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                mContext.getResources().openRawResource(R.raw.controls),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    parse(line);
                } catch (RuntimeException e) {
                    // One bad line must not lose the rest of the page
                    Log.w(TAG, "Skipped a bad line: " + line, e);
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Couldn't read the button list", e);
        }
        flushItem();
        finishChapter();
        return mChapters;
    }

    private void parse(String line) {
        if (line.trim().isEmpty() || line.startsWith("#")) {
            return;
        }
        if (line.startsWith(" ")) {
            condition(line.trim());
            return;
        }
        final int space = line.indexOf(' ');
        final String kind = space < 0 ? line : line.substring(0, space);
        final String rest = space < 0 ? "" : line.substring(space + 1);
        flushItem();
        switch (kind) {
            case "topic":
                finishChapter();
                mChapter = new Chapters.Chapter(text(rest));
                mChapterHasRow = false;
                mLevel = LEVEL_TOPIC;
                mTopicShown = true;
                mHeadingShown = true;
                break;
            case "heading":
                mLevel = LEVEL_HEADING;
                mHeadingShown = true;
                mItem = new Chapters.Item(new ArrayList<>(), text(rest), true);
                mItemShown = true;
                break;
            case "drawing":
                // The only drawing is the console
                mLevel = LEVEL_ITEM;
                mItem = Chapters.Item.drawing();
                mItemShown = true;
                break;
            case "text":
                mLevel = LEVEL_ITEM;
                mItem = new Chapters.Item(new ArrayList<>(), text(rest));
                mItemShown = true;
                break;
            case "row":
                mLevel = LEVEL_ITEM;
                final int equals = rest.lastIndexOf(" = ");
                if (equals < 0) {
                    Log.w(TAG, "Skipped a row without \" = \": " + line);
                    mItem = null;
                    break;
                }
                mItem = new Chapters.Item(keys(rest.substring(0, equals)),
                        text(rest.substring(equals + 3)));
                mItemShown = true;
                break;
            default:
                Log.w(TAG, "Unknown line: " + line);
        }
    }

    /** A condition hides the item above it, or a whole topic or heading. */
    private void condition(String line) {
        final int space = line.indexOf(' ');
        if (space < 0) {
            return;
        }
        final String kind = line.substring(0, space);
        final String value = line.substring(space + 1).trim();
        final boolean met;
        switch (kind) {
            case "if-package":
                met = hasPackage(value);
                break;
            case "if-file":
                met = new File(value).exists();
                break;
            case "if-prop":
                met = SystemProperties.getBoolean(value, false);
                break;
            default:
                // "check" lines are for the host check only
                return;
        }
        if (met) {
            return;
        }
        switch (mLevel) {
            case LEVEL_TOPIC:
                mTopicShown = false;
                break;
            case LEVEL_HEADING:
                mHeadingShown = false;
                mItemShown = false;
                break;
            default:
                mItemShown = false;
        }
    }

    private void flushItem() {
        if (mItem == null || mChapter == null) {
            mItem = null;
            return;
        }
        if (mTopicShown && mHeadingShown && mItemShown) {
            mChapter.items.add(mItem);
            if (!mItem.isParagraph()) {
                mChapterHasRow = true;
            }
        }
        mItem = null;
    }

    private void finishChapter() {
        // A topic whose rows all belong to missing apps says nothing useful
        if (mChapter != null && mTopicShown && mChapterHasRow) {
            mChapters.add(mChapter);
        }
        mChapter = null;
    }

    /** "Select + Up / Down" -> ["Select", "Up / Down"], @names read from the resources. */
    private List<String> keys(String spec) {
        final List<String> keys = new ArrayList<>();
        for (String group : spec.trim().split(" \\+ ")) {
            final String[] alternatives = group.split(" / ");
            final StringBuilder out = new StringBuilder();
            for (int i = 0; i < alternatives.length; i++) {
                if (i > 0) {
                    out.append(" / ");
                }
                out.append(label(alternatives[i].trim()));
            }
            keys.add(out.toString());
        }
        return keys;
    }

    private String label(String key) {
        if (!key.startsWith("@")) {
            return key;
        }
        int end = 1;
        while (end < key.length() && (Character.isLetterOrDigit(key.charAt(end))
                || key.charAt(end) == '_')) {
            end++;
        }
        final String suffix = key.substring(end).replace('-', '−');
        return string(key.substring(1, end)) + suffix;
    }

    private CharSequence text(String name) {
        if (name.startsWith("=")) {
            return name.substring(1);
        }
        final int id = resource(name);
        return id != 0 ? mContext.getText(id) : name;
    }

    private String string(String name) {
        final int id = resource(name);
        return id != 0 ? mContext.getString(id) : name;
    }

    private int resource(String name) {
        return mContext.getResources().getIdentifier(name, "string", mContext.getPackageName());
    }

    private boolean hasPackage(String packageName) {
        try {
            mContext.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
