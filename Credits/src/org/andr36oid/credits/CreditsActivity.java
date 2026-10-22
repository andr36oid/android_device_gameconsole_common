package org.andr36oid.credits;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.andr36oid.hints.HintBar;

import java.io.File;

/**
 * The credits, an easter egg: tapping the andr36oid version in Settings > About console
 * opens them. They roll by themselves like film credits and can't be scrolled.
 */
public class CreditsActivity extends Activity {

    private static final long START_DELAY_MS = 2500;
    private static final long END_PAUSE_MS = 3000;
    private static final long FADE_MS = 500;
    private static final float ROLL_DP_PER_SECOND = 24;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ScrollView mScroll;
    private LinearLayout mContent;
    private ValueAnimator mShimmer;
    // Lucid by HoliznaCC0 plays while the credits roll, if it is still in Music (one of the
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
    private float mRollSpeed;
    private float mOffset;
    private long mLastFrameNanos;

    private final Choreographer.FrameCallback mRollFrame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            final int end = mContent.getHeight() - mScroll.getHeight();
            if (mLastFrameNanos != 0) {
                mOffset += (frameTimeNanos - mLastFrameNanos) / 1e9f * mRollSpeed;
            }
            mLastFrameNanos = frameTimeNanos;
            if (mOffset >= end) {
                mScroll.scrollTo(0, Math.max(end, 0));
                mHandler.postDelayed(mStartOver, END_PAUSE_MS);
                return;
            }
            mScroll.scrollTo(0, (int) mOffset);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private final Runnable mStartRolling = () -> {
        mOffset = mScroll.getScrollY();
        mLastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(mRollFrame);
    };

    // Fade out at the end, back to the top, fade in and roll again.
    private final Runnable mStartOver = () -> mContent.animate().alpha(0f).setDuration(FADE_MS)
            .withEndAction(() -> {
                mScroll.scrollTo(0, 0);
                mContent.animate().alpha(1f).setDuration(FADE_MS)
                        .withEndAction(() -> rollAfter(START_DELAY_MS));
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tintBackground();
        mRollSpeed = dp(ROLL_DP_PER_SECOND);
        mPlayer = openSong();

        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setGravity(Gravity.CENTER_HORIZONTAL);
        mContent.setPadding(dp(24), dp(32), dp(24), dp(32));
        buildCredits();

        mScroll = new ScrollView(this);
        mScroll.setVerticalScrollBarEnabled(false);
        mScroll.setVerticalFadingEdgeEnabled(true);
        mScroll.setFadingEdgeLength(dp(48));
        // Nothing on this screen takes input: without this the scroll view gets the focus on
        // start and Android draws its focus highlight, a white veil, over all of it.
        mScroll.setFocusable(false);
        mScroll.setDefaultFocusHighlightEnabled(false);
        mScroll.addView(mContent);
        setContentView(mScroll);
        HintBar.overlay(this, "B", getString(org.andr36oid.hints.R.string.andr36oid_hint_back));
    }

    @Override
    protected void onResume() {
        super.onResume();
        rollAfter(START_DELAY_MS);
        if (mShimmer != null) mShimmer.start();
        if (mPlayer != null) {
            getSystemService(AudioManager.class).requestAudioFocus(mFocus);
            mPlayer.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRolling();
        if (mShimmer != null) mShimmer.cancel();
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

    // The credits roll by themselves; only back (B) does anything, and leaves.

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        // Volume and the like still work.
        return KeyEvent.isGamepadButton(key) || isDpad(key) || super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return true;
    }

    private static boolean isDpad(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_SPACE:
                return true;
            default:
                return false;
        }
    }

    private void rollAfter(long delayMs) {
        stopRolling();
        mHandler.postDelayed(mStartRolling, delayMs);
    }

    private void stopRolling() {
        mHandler.removeCallbacksAndMessages(null);
        Choreographer.getInstance().removeFrameCallback(mRollFrame);
    }

    private void buildCredits() {
        final ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_andr36oid);
        clipTo(logo, R.drawable.logo_frame);
        mContent.addView(logo, new LinearLayout.LayoutParams(dp(128), dp(128)));

        final TextView title = text("andr36oid", 40, Color.WHITE, Typeface.BOLD);
        title.setLetterSpacing(0.04f);
        mContent.addView(title, margins(0, 12, 0, 0));
        shimmer(title);

        final String version = SystemProperties.get("ro.andr36oid.version");
        final String tagline = getString(R.string.credits_tagline);
        mContent.addView(text(TextUtils.isEmpty(version) ? tagline : tagline + "  ·  " + version,
                14, getColor(R.color.credits_detail), Typeface.NORMAL));

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
                    margins(0, 48, 0, 0));
        }
    }

    /** An artist's name, and the license when it asks for credit. */
    private void artist(String name, String license) {
        mContent.addView(text(name, 18, getColor(R.color.credits_name), Typeface.BOLD),
                margins(0, 16, 0, 0));
        if (license != null) {
            mContent.addView(text(license, 13, getColor(R.color.credits_detail),
                    Typeface.NORMAL));
        }
    }

    private void section(int titleRes) {
        final TextView header = text(getString(titleRes), 12, getColor(R.color.credits_heading),
                Typeface.BOLD);
        header.setAllCaps(true);
        header.setLetterSpacing(0.25f);
        mContent.addView(header, margins(0, 48, 0, 0));

        final View bar = new View(this);
        final GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, gradientColors());
        gradient.setCornerRadius(dp(1));
        bar.setBackground(gradient);
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(2));
        params.setMargins(0, dp(8), 0, dp(4));
        mContent.addView(bar, params);
    }

    private void role(int roleRes) {
        mContent.addView(text(getString(roleRes), 13, getColor(R.color.credits_detail),
                Typeface.NORMAL), margins(0, 20, 0, 10));
    }

    private void people(View... people) {
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        for (View person : people) {
            row.addView(person, margins(16, 0, 16, 0));
        }
        mContent.addView(row);
    }

    /**
     * A round profile picture, the name and where to find them (GitHub, YouTube).
     * No picture: a person icon.
     */
    private View person(int avatarRes, String name, int accountIconRes, String account) {
        final LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        final ImageView avatar = new ImageView(this);
        if (avatarRes != 0) {
            avatar.setImageResource(avatarRes);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            avatar.setImageResource(R.drawable.ic_person);
            avatar.setPadding(dp(12), dp(12), dp(12), dp(12));
        }
        clipTo(avatar, R.drawable.avatar_frame);
        column.addView(avatar, new LinearLayout.LayoutParams(dp(64), dp(64)));

        column.addView(text(name, 20, getColor(R.color.credits_name), Typeface.BOLD),
                margins(0, 8, 0, 0));

        if (account != null) {
            final LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            final ImageView mark = new ImageView(this);
            mark.setImageResource(accountIconRes);
            if (accountIconRes == R.drawable.ic_github) mark.setAlpha(0.7f);
            row.addView(mark, new LinearLayout.LayoutParams(dp(14), dp(14)));
            row.addView(text(account, 13, getColor(R.color.credits_detail), Typeface.NORMAL),
                    margins(6, 0, 0, 0));
            column.addView(row, margins(0, 2, 0, 0));
        }
        return column;
    }

    /** A project logo with the name and what it is next to it. */
    private void project(int logoRes, String name, int detailRes) {
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final ImageView logo = new ImageView(this);
        logo.setImageResource(logoRes);
        logo.setPadding(dp(6), dp(6), dp(6), dp(6));
        clipTo(logo, R.drawable.logo_frame);
        row.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));

        final LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        final TextView nameView = text(name, 18, getColor(R.color.credits_name), Typeface.BOLD);
        nameView.setGravity(Gravity.START);
        lines.addView(nameView);
        final TextView detail = text(getString(detailRes), 13, getColor(R.color.credits_detail),
                Typeface.NORMAL);
        detail.setGravity(Gravity.START);
        lines.addView(detail);
        row.addView(lines, margins(14, 0, 0, 0));

        // Fixed width, so the logos line up under each other.
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(16), 0, 0);
        mContent.addView(row, params);
    }

    /** Lets a gradient run through the title, over and over. */
    private void shimmer(TextView title) {
        final Matrix matrix = new Matrix();
        title.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight,
                oldBottom) -> {
            if (mShimmer != null) {
                return;
            }
            final float width = title.getPaint().measureText(title.getText().toString());
            final LinearGradient gradient = new LinearGradient(0, 0, width, 0,
                    gradientColors(), null, Shader.TileMode.MIRROR);
            title.getPaint().setShader(gradient);
            mShimmer = ValueAnimator.ofFloat(0f, 2f * width);
            mShimmer.setDuration(6000);
            mShimmer.setRepeatCount(ValueAnimator.INFINITE);
            mShimmer.setInterpolator(new LinearInterpolator());
            mShimmer.addUpdateListener(animator -> {
                matrix.setTranslate((float) animator.getAnimatedValue(), 0);
                gradient.setLocalMatrix(matrix);
                title.invalidate();
            });
            if (!isFinishing() && hasWindowFocus()) mShimmer.start();
        });
    }

    /** The console's accent (orange until one is picked), light to full to light. */
    private int[] gradientColors() {
        final int accent = getColor(R.color.credits_accent);
        return new int[] { towardWhite(accent, 0.55f), accent, towardWhite(accent, 0.3f) };
    }

    /**
     * The glow at the top in a dark shade of the accent (wooden brown for the orange),
     * fading into black.
     */
    private void tintBackground() {
        final int accent = getColor(R.color.credits_accent);
        final GradientDrawable background =
                (GradientDrawable) getDrawable(R.drawable.credits_background).mutate();
        background.setColors(new int[] { dim(accent, 0.26f), dim(accent, 0.1f), Color.BLACK });
        getWindow().setBackgroundDrawable(background);
    }

    /** The colour at the given brightness, 0 = black. */
    private static int dim(int color, float amount) {
        return Color.rgb(Math.round(Color.red(color) * amount),
                Math.round(Color.green(color) * amount), Math.round(Color.blue(color) * amount));
    }

    private static int towardWhite(int color, float amount) {
        final int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return Color.rgb(r + Math.round((255 - r) * amount), g + Math.round((255 - g) * amount),
                b + Math.round((255 - b) * amount));
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
        view.setGravity(Gravity.CENTER_HORIZONTAL);
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
