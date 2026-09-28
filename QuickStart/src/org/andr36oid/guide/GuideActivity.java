package org.andr36oid.guide;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The quick start guide. Topics on the left, the page on the right, all driven by the buttons:
 * the D-pad picks a topic, A (or right) moves into the page to scroll it, B goes back to the
 * topics and closes the guide from there. L1 and R1 step through the topics from anywhere.
 */
public class GuideActivity extends Activity {

    private final List<TextView> mChapterViews = new ArrayList<>();
    private List<Chapters.Chapter> mChapters;
    private int mShown = -1;

    private ScrollView mPage;
    private LinearLayout mPageContent;
    private TextView mPageTitle;
    private LinearLayout mHints;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Opened by hand, so it doesn't need to open by itself any more
        FirstBootReceiver.markShown(this);

        mChapters = Chapters.build(this);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(8));

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(buildChapterList(), new LinearLayout.LayoutParams(dp(190),
                ViewGroup.LayoutParams.MATCH_PARENT));
        final LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        pageParams.setMarginStart(dp(12));
        body.addView(buildPage(), pageParams);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0,
                1f));

        mHints = new LinearLayout(this);
        mHints.setOrientation(LinearLayout.HORIZONTAL);
        mHints.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        mHints.setPadding(0, dp(8), 0, 0);
        root.addView(mHints, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        final int start = savedInstanceState != null
                ? savedInstanceState.getInt("chapter", 0) : 0;
        showChapter(Math.min(start, mChapters.size() - 1));
        mChapterViews.get(mShown).requestFocus();
        updateHints(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("chapter", mShown);
    }

    private View buildHeader() {
        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(4), dp(10));

        final TextView title = text(getString(R.string.guide_title), 22, R.color.guide_text);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final String version = android.os.SystemProperties.get("ro.andr36oid.version", "");
        final TextView brand = text(version.isEmpty() ? "andr36oid" : "andr36oid " + version,
                13, R.color.guide_dim);
        header.addView(brand);
        return header;
    }

    private View buildChapterList() {
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < mChapters.size(); i++) {
            final int index = i;
            final TextView item = text(mChapters.get(i).title, 16, R.color.guide_text);
            item.setBackgroundResource(R.drawable.chapter_background);
            item.setPadding(dp(14), dp(9), dp(14), dp(9));
            item.setFocusable(true);
            // No touchscreen, but a joystick mouse click puts the window in touch mode
            item.setFocusableInTouchMode(true);
            item.setSingleLine(true);
            item.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    showChapter(index);
                    updateHints(false);
                }
            });
            item.setOnClickListener(v -> enterPage());
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(4);
            list.addView(item, params);
            mChapterViews.add(item);
        }
        final ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        return scroll;
    }

    private View buildPage() {
        mPageContent = new LinearLayout(this);
        mPageContent.setOrientation(LinearLayout.VERTICAL);
        mPageContent.setPadding(dp(18), dp(14), dp(18), dp(18));

        mPage = new ScrollView(this);
        mPage.setBackgroundResource(R.drawable.page_background);
        mPage.setFocusable(true);
        mPage.setFocusableInTouchMode(true);
        mPage.setVerticalFadingEdgeEnabled(true);
        mPage.setFadingEdgeLength(dp(24));
        mPage.setOnFocusChangeListener((v, hasFocus) -> updateHints(hasFocus));
        mPage.addView(mPageContent);
        return mPage;
    }

    private void showChapter(int index) {
        if (index == mShown) {
            return;
        }
        if (mShown >= 0) {
            mChapterViews.get(mShown).setSelected(false);
        }
        mShown = index;
        mChapterViews.get(index).setSelected(true);

        final Chapters.Chapter chapter = mChapters.get(index);
        mPageContent.removeAllViews();
        mPageTitle = text(chapter.title, 20, R.color.guide_accent);
        mPageTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mPageTitle.setPadding(0, 0, 0, dp(8));
        mPageContent.addView(mPageTitle);
        for (Chapters.Item item : chapter.items) {
            mPageContent.addView(item.heading ? buildHeading(item)
                    : item.isParagraph() ? buildParagraph(item) : buildRow(item));
        }
        mPage.scrollTo(0, 0);
    }

    private View buildHeading(Chapters.Item item) {
        final TextView view = text(item.text, 16, R.color.guide_accent);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setPadding(0, dp(12), 0, dp(0));
        return view;
    }

    private View buildParagraph(Chapters.Item item) {
        final TextView view = text(item.text, 15, R.color.guide_detail);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private View buildRow(Chapters.Item item) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));

        final LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < item.keys.size(); i++) {
            if (i > 0) {
                keys.addView(joiner(getString(R.string.key_plus)));
            }
            final String[] alternatives = item.keys.get(i).split(" / ");
            for (int j = 0; j < alternatives.length; j++) {
                if (j > 0) {
                    keys.addView(joiner(getString(R.string.key_or)));
                }
                keys.addView(badge(alternatives[j]));
            }
        }
        // Keys in a column of their own, so the descriptions line up
        row.addView(keys, new LinearLayout.LayoutParams(dp(160),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView text = text(item.text, 15, R.color.guide_text);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        return row;
    }

    private TextView badge(String label) {
        final TextView badge = text(label, 13, R.color.guide_text);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setBackgroundResource(R.drawable.key_badge);
        badge.setPadding(dp(8), dp(2), dp(8), dp(3));
        badge.setSingleLine(true);
        return badge;
    }

    private TextView joiner(String text) {
        final TextView view = text(text, 13, R.color.guide_dim);
        view.setPadding(dp(4), 0, dp(4), 0);
        return view;
    }

    /** The bottom line lists what the buttons do right now. */
    private void updateHints(boolean inPage) {
        if (mHints == null) {
            return;
        }
        mHints.removeAllViews();
        if (inPage) {
            hint(getString(R.string.key_dpad), R.string.hint_scroll);
            hint("L1 / R1", R.string.hint_page);
            hint("B", R.string.hint_back);
        } else {
            hint(getString(R.string.key_dpad), R.string.hint_choose);
            hint("A", R.string.hint_read);
            hint("X", R.string.hint_tutorial);
            hint("B", R.string.hint_close);
        }
    }

    private void hint(String keys, int label) {
        final String[] alternatives = keys.split(" / ");
        for (int i = 0; i < alternatives.length; i++) {
            if (i > 0) {
                mHints.addView(joiner(getString(R.string.key_or)));
            }
            mHints.addView(badge(alternatives[i]));
        }
        final TextView text = text(getString(label), 13, R.color.guide_dim);
        text.setPadding(dp(6), 0, dp(16), 0);
        mHints.addView(text);
    }

    private void enterPage() {
        mPage.requestFocus();
    }

    private void leavePage() {
        mChapterViews.get(mShown).requestFocus();
    }

    private void stepChapter(int direction) {
        final int next = Math.max(0, Math.min(mChapters.size() - 1, mShown + direction));
        final boolean inPage = mPage.hasFocus();
        showChapter(next);
        if (inPage) {
            updateHints(true);
        } else {
            mChapterViews.get(next).requestFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int key = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean inPage = mPage.hasFocus();

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
                if (down) stepChapter(key == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (!down && !event.isCanceled()) {
                    if (inPage) {
                        leavePage();
                    } else {
                        finish();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_START:
                if (down && event.getRepeatCount() == 0 && !inPage) enterPage();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!inPage) {
                    if (down) enterPage();
                    return true;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (inPage) {
                    if (down) leavePage();
                    return true;
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
        }
        return super.dispatchKeyEvent(event);
    }

    private TextView text(CharSequence content, float sp, int color) {
        final TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(getColor(color));
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
