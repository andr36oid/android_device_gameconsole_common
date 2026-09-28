package org.andr36oid.guide;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The quick start guide, laid out like a Settings screen: a list of topics, and a topic opens
 * as a page of its own with the stock action bar. All driven by the buttons: the D-pad picks a
 * topic, A opens it and the D-pad then scrolls it, B goes back to the topics and closes the
 * guide from there. L1 and R1 step through the topics, X opens the hands-on tutorial.
 */
public class GuideActivity extends Activity {

    // activity-alias in the manifest: the Help icon in the app list
    private static final String HELP_ALIAS = ".HelpActivity";

    private List<Chapters.Chapter> mChapters;
    // The topic open as a page, or -1 while the list shows
    private int mShown = -1;

    private ListView mList;
    private ScrollView mPage;
    private LinearLayout mPageContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Opened by hand, so it doesn't need to open by itself any more
        FirstBootReceiver.markShown(this);

        mChapters = Chapters.build(this);

        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        buildList();
        buildPage();

        final int start = savedInstanceState != null
                ? savedInstanceState.getInt("chapter", -1) : -1;
        if (start >= 0 && start < mChapters.size()) {
            showChapter(start);
        } else {
            showList(getFirstChapter());
        }
    }

    /** The Help app icon opens the guide on the first help topic. */
    private int getFirstChapter() {
        final ComponentName component = getIntent().getComponent();
        if (component != null && component.getClassName().endsWith(HELP_ALIAS)) {
            for (int i = 0; i < mChapters.size(); i++) {
                if (mChapters.get(i).helpStart) {
                    return i;
                }
            }
        }
        return 0;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("chapter", mShown);
    }

    private void buildList() {
        final List<CharSequence> titles = new ArrayList<>();
        for (Chapters.Chapter chapter : mChapters) {
            titles.add(chapter.title);
        }
        mList = new ListView(this);
        mList.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, titles));
        mList.setOnItemClickListener((parent, view, position, id) -> showChapter(position));
    }

    private void buildPage() {
        mPageContent = new LinearLayout(this);
        mPageContent.setOrientation(LinearLayout.VERTICAL);
        mPageContent.setPadding(0, 0, 0, dp(16));

        mPage = new ScrollView(this);
        mPage.setFocusable(true);
        mPage.setFocusableInTouchMode(true);
        // The whole page is one focus stop, a highlight around all of it would only be noise
        mPage.setDefaultFocusHighlightEnabled(false);
        mPage.addView(mPageContent);
    }

    private void showList(int select) {
        mShown = -1;
        setTitle(R.string.guide_title);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            final String version = android.os.SystemProperties.get("ro.andr36oid.version", "");
            actionBar.setSubtitle(version.isEmpty() ? "andr36oid" : "andr36oid " + version);
        }
        setContentView(mList);
        mList.requestFocus();
        mList.setSelection(select);
    }

    private void showChapter(int index) {
        mShown = index;
        final Chapters.Chapter chapter = mChapters.get(index);
        setTitle(chapter.title);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setSubtitle(null);
        }

        mPageContent.removeAllViews();
        for (Chapters.Item item : chapter.items) {
            mPageContent.addView(item.heading ? buildHeading(item)
                    : item.isParagraph() ? buildParagraph(item) : buildRow(item));
        }
        if (mPage.getParent() == null) {
            setContentView(mPage);
        }
        mPage.scrollTo(0, 0);
        mPage.requestFocus();
    }

    /** A question or problem, styled like a preference category title. */
    private View buildHeading(Chapters.Item item) {
        final TextView view = new TextView(this);
        view.setText(item.text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextColor(themeColor(android.R.attr.colorAccent));
        view.setPadding(listPadding(), dp(24), listPadding(), dp(4));
        return view;
    }

    private View buildParagraph(Chapters.Item item) {
        final TextView view = new TextView(this);
        view.setText(item.text);
        view.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        view.setTextColor(themeColor(android.R.attr.textColorSecondary));
        view.setLineSpacing(0, 1.2f);
        view.setPadding(listPadding(), dp(12), listPadding(), dp(4));
        return view;
    }

    /** Buttons and what they do, like a two-line preference: the buttons, then the action. */
    private View buildRow(Chapters.Item item) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(listPadding(), dp(12), listPadding(), dp(12));

        final TextView keys = new TextView(this);
        keys.setText(TextUtils.join(" " + getString(R.string.key_plus) + " ", item.keys));
        keys.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        keys.setTextColor(themeColor(android.R.attr.textColorPrimary));
        row.addView(keys);

        final TextView text = new TextView(this);
        text.setText(item.text);
        text.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        text.setTextColor(themeColor(android.R.attr.textColorSecondary));
        row.addView(text);
        return row;
    }

    private void stepChapter(int direction) {
        final int next = Math.max(0, Math.min(mChapters.size() - 1, mShown + direction));
        if (next != mShown) {
            showChapter(next);
        }
    }

    private void back() {
        if (mShown >= 0) {
            showList(mShown);
        } else {
            finish();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            back();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int key = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean inPage = mShown >= 0;

        // Handled on both down and up, so their fallback keys (B is Back, A is center)
        // never get generated.
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_X:
                // Try the buttons hands-on again
                if (down && event.getRepeatCount() == 0) {
                    startActivity(new Intent(this, TutorialActivity.class));
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (down && inPage) stepChapter(key == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (!down && !event.isCanceled()) back();
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_START:
                if (down && event.getRepeatCount() == 0 && !inPage) {
                    final int position = mList.getSelectedItemPosition();
                    showChapter(position >= 0 ? position : 0);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (inPage) {
                    if (down) {
                        final int direction = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                        mPage.smoothScrollBy(0, direction * mPage.getHeight() / 3);
                    }
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Nothing to the side of either screen
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int themeColor(int attr) {
        final TypedArray a = obtainStyledAttributes(new int[] { attr });
        try {
            return a.getColor(0, 0);
        } finally {
            a.recycle();
        }
    }

    private int listPadding() {
        final TypedArray a = obtainStyledAttributes(
                new int[] { android.R.attr.listPreferredItemPaddingStart });
        try {
            return a.getDimensionPixelSize(0, dp(16));
        } finally {
            a.recycle();
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
