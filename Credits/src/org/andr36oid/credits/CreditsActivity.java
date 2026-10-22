package org.andr36oid.credits;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.andr36oid.hints.HintBar;

import java.io.File;

/**
 * The credits, an easter egg: tapping the andr36oid version in Settings > About console
 * opens them. Browse with the D-pad or mouse; A opens a profile, B goes back.
 */
public class CreditsActivity extends Activity {

    private LinearLayout mContent;
    private View mFirstLink;
    private View mLastLink;
    // Lucid by HoliznaCC0 plays while the credits are open, if it is still in Music (one of the
    // example tracks the first start copies there). Deleted: the credits stay silent.
    private static final String SONG_FILE = "holiznacc0-lucid.mp3";
    // During the first setup the track isn't in Music yet: it is still on AUTOAPK, which the
    // first start mounts read-only for everyone until the setup is done with it.
    private static final File SONG_ON_AUTOAPK =
            new File("/mnt/andr36oid-apps/examplemusic", SONG_FILE);
    private static final String SONG_TITLE = "Lucid";
    private static final String SONG_ARTIST = "HoliznaCC0";

    private MediaPlayer mPlayer;
    private AudioFocusRequest mFocus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPlayer = openSong();

        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setPadding(dp(24), dp(20), dp(24), dp(16));
        buildCredits();

        final ScrollView scroll = new ScrollView(this);
        scroll.setSmoothScrollingEnabled(false);
        scroll.setDefaultFocusHighlightEnabled(false);
        // Below the profile links, the D-pad scrolls the page itself.
        scroll.setForeground(getDrawable(R.drawable.scroll_focus));
        scroll.addView(mContent);
        setContentView(scroll);
        HintBar.attachDefault(this);
        mFirstLink.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPlayer != null) {
            getSystemService(AudioManager.class).requestAudioFocus(mFocus);
            mPlayer.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mPlayer != null) {
            mPlayer.pause();
            getSystemService(AudioManager.class).abandonAudioFocusRequest(mFocus);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }

    /** The song, looping, or null when it is gone from Music or can't be read. */
    private MediaPlayer openSong() {
        File song = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), SONG_FILE);
        if (!song.isFile()) {
            song = SONG_ON_AUTOAPK;
            if (!song.isFile()) return null;
        }
        final AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        final MediaPlayer player = new MediaPlayer();
        try {
            player.setAudioAttributes(attributes);
            player.setDataSource(song.getPath());
            player.setLooping(true);
            player.prepare();
        } catch (Exception e) {
            player.release();
            return null;
        }
        mFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS && mPlayer != null) {
                        mPlayer.pause();
                    }
                }).build();
        return player;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) finish();
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_A || key == KeyEvent.KEYCODE_DPAD_CENTER
                || key == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                final View focused = getCurrentFocus();
                if (focused != null && focused.isClickable()) focused.performClick();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void buildCredits() {
        final LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_andr36oid);
        clipTo(logo, R.drawable.logo_frame);
        header.addView(logo, new LinearLayout.LayoutParams(dp(72), dp(72)));

        final LinearLayout intro = new LinearLayout(this);
        intro.setOrientation(LinearLayout.VERTICAL);
        intro.addView(text("andr36oid", 30, getColor(R.color.credits_name), Typeface.BOLD));
        final String version = SystemProperties.get("ro.andr36oid.version");
        intro.addView(text(getString(R.string.credits_tagline), 14,
                getColor(R.color.credits_detail), Typeface.NORMAL));
        if (!TextUtils.isEmpty(version)) {
            intro.addView(text(version, 12, getColor(R.color.credits_detail), Typeface.NORMAL));
        }
        final LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        introParams.setMarginStart(dp(16));
        header.addView(intro, introParams);
        mContent.addView(header);

        section(R.string.credits_team);
        role(R.string.credits_role_port);
        people(person(R.drawable.avatar_snaccy, "snaccy", R.drawable.ic_github, "sonic011gamer"));
        role(R.string.credits_role_ux);
        people(person(R.drawable.avatar_kenny, "kenny", R.drawable.ic_github, "itskenny0"),
                person(R.drawable.avatar_snaccy, "snaccy", R.drawable.ic_github, "sonic011gamer"));
        role(R.string.credits_role_clones);
        people(person(R.drawable.avatar_kenny, "kenny", R.drawable.ic_github, "itskenny0"),
                person(R.drawable.avatar_kauan, "Kauan", R.drawable.ic_github, "K4uanGP"));
        role(R.string.credits_role_legend);
        people(person(R.drawable.avatar_sjsltech, "SjslTech", R.drawable.ic_youtube,
                getString(R.string.credits_on_youtube)));
        role(R.string.credits_role_beta);
        people(person(R.drawable.avatar_s1eepy, "S1eepy", R.drawable.ic_youtube,
                getString(R.string.credits_on_youtube)));

        section(R.string.credits_based_on);
        project(R.drawable.logo_351droid, "351droid", R.string.credits_351droid);
        project(R.drawable.logo_lineageos, "LineageOS", R.string.credits_lineageos);
        project(R.drawable.logo_android, "Android", R.string.credits_android);
        project(R.drawable.logo_sprout, "Sprout Launcher", R.string.credits_sprout);
        project(R.drawable.logo_bfi, "BFI", R.string.credits_bfi);
        project(R.drawable.logo_r36tuner, "R36 Tuner Next", R.string.credits_r36tuner);
        project(R.drawable.logo_dpadplayer, "D-PAD Player", R.string.credits_dpadplayer);
        project(R.drawable.logo_yabasanshiro, "Yaba Sanshiro", R.string.credits_yabasanshiro);
        project(R.drawable.logo_gammaos, "GammaOS", R.string.credits_gammaos);
        project(R.drawable.logo_fpsmeter, "FPS Meter", R.string.credits_fpsmeter);

        // The free tracks the first start puts into Music (examplemusic/CREDITS.txt)
        section(R.string.credits_example_music);
        artist("HoliznaCC0", null);
        artist("Ketsa", "CC BY 4.0");
        artist("Loyalty Freak Music", null);

        if (mPlayer != null) {
            mContent.addView(text(getString(R.string.credits_now_playing, SONG_TITLE,
                    SONG_ARTIST), 13, getColor(R.color.credits_detail), Typeface.ITALIC),
                    margins(8, 24, 8, 0));
        }
        watching();
    }

    /** An artist's name, and the license when it asks for credit. */
    private void artist(String name, String license) {
        mContent.addView(text(name, 18, getColor(R.color.credits_name), Typeface.BOLD),
                margins(8, 12, 8, 0));
        if (license != null) {
            mContent.addView(text(license, 13, getColor(R.color.credits_detail),
                    Typeface.NORMAL), margins(8, 0, 8, 0));
        }
    }

    private void section(int titleRes) {
        final TextView header = text(getString(titleRes), 18, getColor(R.color.credits_accent),
                Typeface.BOLD);
        header.setAccessibilityHeading(true);
        mContent.addView(header, margins(8, 24, 8, 8));

        final View bar = new View(this);
        bar.setBackgroundColor(getColor(R.color.credits_detail));
        bar.setAlpha(0.2f);
        mContent.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void role(int roleRes) {
        mContent.addView(text(getString(roleRes), 14, getColor(R.color.credits_detail),
                Typeface.NORMAL), margins(8, 16, 8, 4));
    }

    private void people(View... people) {
        final boolean wide = getResources().getConfiguration().screenWidthDp >= 560
                && getResources().getConfiguration().fontScale <= 1.2f;
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int i = 0; i < people.length; i++) {
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    wide ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, wide ? 1f : 0f);
            if (i > 0) {
                if (wide) params.setMarginStart(dp(16));
                else params.topMargin = dp(4);
            }
            row.addView(people[i], params);
        }
        mContent.addView(row);
    }

    /** A round profile picture, the name and where to find them (GitHub, YouTube). */
    private View person(int avatarRes, String name, int accountIconRes, String account) {
        final LinearLayout person = new LinearLayout(this);
        person.setGravity(Gravity.CENTER_VERTICAL);
        person.setPadding(dp(8), dp(8), dp(8), dp(8));

        final ImageView avatar = new ImageView(this);
        avatar.setImageResource(avatarRes);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        clipTo(avatar, R.drawable.avatar_frame);
        person.addView(avatar, new LinearLayout.LayoutParams(dp(64), dp(64)));

        final LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.addView(text(name, 20, getColor(R.color.credits_name), Typeface.BOLD));
        final LinearLayout accountRow = new LinearLayout(this);
        accountRow.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView mark = new ImageView(this);
        mark.setImageResource(accountIconRes);
        if (accountIconRes == R.drawable.ic_github) {
            mark.setImageTintList(getColorStateList(R.color.credits_detail));
        }
        accountRow.addView(mark, new LinearLayout.LayoutParams(dp(14), dp(14)));
        final LinearLayout.LayoutParams accountParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        accountParams.setMarginStart(dp(6));
        accountRow.addView(text(account, 13, getColor(R.color.credits_accent), Typeface.NORMAL),
                accountParams);
        final LinearLayout.LayoutParams accountRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        accountRowParams.topMargin = dp(2);
        lines.addView(accountRow, accountRowParams);
        final LinearLayout.LayoutParams linesParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        linesParams.setMarginStart(dp(16));
        person.addView(lines, linesParams);

        final String url = accountIconRes == R.drawable.ic_github
                ? "https://github.com/" + account : "https://www.youtube.com/@" + name;
        link(person, url);
        return person;
    }

    private void link(View view, String url) {
        view.setId(View.generateViewId());
        view.setBackgroundResource(R.drawable.link_focus);
        view.setFocusable(true);
        // A joystick mouse click puts the window in touch mode.
        view.setFocusableInTouchMode(true);
        view.setOnClickListener(v -> {
            v.requestFocus();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException | SecurityException e) {
                // A browser may have been removed.
            }
        });
        // Up and down follow the printed order, including both people in a shared row.
        if (mLastLink != null) {
            mLastLink.setNextFocusDownId(view.getId());
            mLastLink.setNextFocusForwardId(view.getId());
            view.setNextFocusUpId(mLastLink.getId());
        } else {
            mFirstLink = view;
        }
        mLastLink = view;
    }

    /** A project logo with the name and what it is next to it. */
    private void project(int logoRes, String name, int detailRes) {
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));

        final ImageView logo = new ImageView(this);
        logo.setImageResource(logoRes);
        logo.setPadding(dp(6), dp(6), dp(6), dp(6));
        clipTo(logo, R.drawable.logo_frame);
        row.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));

        final LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.addView(text(name, 18, getColor(R.color.credits_name), Typeface.BOLD));
        lines.addView(text(getString(detailRes), 14, getColor(R.color.credits_detail),
                Typeface.NORMAL));
        final LinearLayout.LayoutParams linesParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        linesParams.setMarginStart(dp(16));
        row.addView(lines, linesParams);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        mContent.addView(row, params);
    }

    private void watching() {
        final LinearLayout corner = new LinearLayout(this);
        corner.setOrientation(LinearLayout.VERTICAL);
        corner.setGravity(Gravity.END);
        corner.setPadding(0, dp(24), 0, 0);
        final View bubble = getLayoutInflater().inflate(R.layout.andr36oid_bubble, corner, false);
        ((TextView) bubble.findViewById(R.id.bubble_text)).setText(R.string.credits_watching);
        corner.addView(bubble, margins(0, 0, 8, 0));

        final ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_andr36oid_version);
        icon.setFocusable(false);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.topMargin = dp(2);
        corner.addView(icon, params);
        mContent.addView(corner);
    }

    private void clipTo(ImageView view, int shapeRes) {
        view.setBackgroundResource(shapeRes);
        view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        view.setClipToOutline(true);
    }

    private TextView text(String text, int sp, int color, int style) {
        final TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setGravity(Gravity.START);
        return view;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
