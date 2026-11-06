package org.andr36oid.blocks;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;

import org.andr36oid.hints.HintBar;

/**
 * The whole game on one view: a mode menu, the playing field with its hold slot, next-queue
 * and stats, a pause menu and a game-over screen, all drawn by hand so it fits the launcher's
 * look. Colours come from the DayNight theme attributes (never pinned) and every size from
 * {@code sp()}, so it reads the same on a 640x480 landscape panel and a 720x720 square one.
 * A 60 Hz handler loop drives gravity through {@link GameModel} and repaints.
 *
 * <p>Input arrives from the activity's key dispatch through {@link #key}. The board is driven
 * entirely by the D-pad and gamepad face buttons; there is no touch control.
 */
class GameView extends View {

    /* Screens. */
    private static final int MENU = 0, PLAYING = 1, PAUSED = 2, OVER = 3;

    /* Horizontal auto-shift: the delay before a held direction repeats, then the repeat rate. */
    private static final long DAS_MS = 150;
    private static final long ARR_MS = 35;
    private static final long FRAME_MS = 16;

    /* The classic tetromino colours, indexed by piece type (I,O,T,S,Z,J,L). */
    private static final int[] COLORS = {
            0xFF2BD6E3, 0xFFF2D840, 0xFFB260E0, 0xFF46C65A,
            0xFFE5554E, 0xFF4A74E8, 0xFFE8903A};

    private final GameModel game = new GameModel();
    private final SharedPreferences prefs;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int[][] tmp = new int[4][2];

    private final int cFg;
    private final int cSecondary;
    private final int cAccent;
    private final int cBackground;
    private final int cPanel;
    private final int cGrid;

    private int screen = MENU;
    private int menuSel;
    private int startLevel = 1;
    private int pauseSel;

    /* Playfield geometry, recomputed on resize. */
    private float cells;        // one mino, in px
    private float boardX, boardY, boardW, boardH;
    private float leftX, rightX;

    private boolean leftHeld, rightHeld, downHeld;
    private long dasTimer;
    private boolean dasCharged;
    private long arrTimer;
    private long lastFrame;

    private HintBar hints;
    private Runnable onExit;

    private final String[] modeNames;
    private final String[] pauseItems;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable loop = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            long dt = lastFrame == 0 ? FRAME_MS : Math.min(100, now - lastFrame);
            lastFrame = now;
            step(dt);
            invalidate();
            handler.postDelayed(this, FRAME_MS);
        }
    };

    GameView(Context context) {
        super(context);
        prefs = context.getSharedPreferences("blocks", Context.MODE_PRIVATE);

        TypedArray a = context.obtainStyledAttributes(new int[]{
                android.R.attr.textColorPrimary, android.R.attr.textColorSecondary,
                android.R.attr.colorAccent, android.R.attr.colorBackground});
        cFg = a.getColor(0, 0xFFFFFFFF);
        cSecondary = a.getColor(1, 0xFF9E9E9E);
        cAccent = a.getColor(2, 0xFF80CBC4);
        cBackground = a.getColor(3, 0xFF0A0A0A);
        a.recycle();
        cPanel = blend(cBackground, cFg, 0.08f);
        cGrid = blend(cBackground, cFg, 0.16f);

        modeNames = new String[]{
                context.getString(R.string.blocks_mode_marathon),
                context.getString(R.string.blocks_mode_sprint),
                context.getString(R.string.blocks_mode_endless)};
        pauseItems = new String[]{
                context.getString(R.string.blocks_resume),
                context.getString(R.string.blocks_restart),
                context.getString(R.string.blocks_quit)};

        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setHintBar(HintBar bar) {
        hints = bar;
        updateHints();
    }

    /** Run when the player backs out of the mode menu, to leave the app. */
    void setOnExit(Runnable r) {
        onExit = r;
    }

    /* ---- lifecycle ---- */

    void start() {
        lastFrame = 0;
        handler.removeCallbacks(loop);
        handler.post(loop);
    }

    void stop() {
        handler.removeCallbacks(loop);
        if (screen == PLAYING) {
            screen = PAUSED;
            pauseSel = 0;
            clearHeld();
            updateHints();
        }
    }

    /* ---- the per-frame step ---- */

    private void step(long dt) {
        if (screen != PLAYING) return;
        // Horizontal auto-shift.
        int dir = leftHeld ? -1 : rightHeld ? 1 : 0;
        if (dir != 0) {
            dasTimer += dt;
            if (!dasCharged && dasTimer >= DAS_MS) {
                dasCharged = true;
                arrTimer = 0;
                game.move(dir);
            } else if (dasCharged) {
                arrTimer += dt;
                while (arrTimer >= ARR_MS) {
                    arrTimer -= ARR_MS;
                    game.move(dir);
                }
            }
        }
        game.tick(dt, downHeld);
        if (game.gameOver) {
            finishGame();
        }
    }

    private void finishGame() {
        screen = OVER;
        clearHeld();
        saveScore();
        updateHints();
    }

    /* ---- input ---- */

    /** Handle a key from the activity. Returns true if the game consumed it. */
    boolean key(int code, boolean down, int repeat) {
        switch (screen) {
            case MENU:   return keyMenu(code, down, repeat);
            case PLAYING: return keyPlay(code, down, repeat);
            case PAUSED: return keyPause(code, down, repeat);
            case OVER:   return keyOver(code, down);
            default:     return false;
        }
    }

    private boolean keyMenu(int code, boolean down, int repeat) {
        if (!down) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                menuSel = (menuSel + 2) % 3; invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                menuSel = (menuSel + 1) % 3; invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                startLevel = Math.max(1, startLevel - 1); invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                startLevel = Math.min(15, startLevel + 1); invalidate(); return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                beginMode(menuSel); return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (onExit != null) onExit.run();
                return true;
            default:
                return isGameKey(code);
        }
    }

    private void beginMode(int mode) {
        game.start(mode, mode == GameModel.MODE_SPRINT ? 1 : startLevel);
        screen = PLAYING;
        clearHeld();
        updateHints();
    }

    private boolean keyPlay(int code, boolean down, int repeat) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && repeat == 0) { leftHeld = true; rightHeld = false; dasReset(); game.move(-1); }
                else if (!down) leftHeld = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && repeat == 0) { rightHeld = true; leftHeld = false; dasReset(); game.move(1); }
                else if (!down) rightHeld = false;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                downHeld = down;
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && repeat == 0) game.hardDrop();
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (down && repeat == 0) game.rotate(0);
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_L1:
                if (down && repeat == 0) game.rotate(1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                if (down && repeat == 0) game.hold();
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (down && repeat == 0) { screen = PAUSED; pauseSel = 0; clearHeld(); updateHints(); }
                return true;
            default:
                return isGameKey(code);
        }
    }

    private boolean keyPause(int code, boolean down, int repeat) {
        if (!down) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                pauseSel = (pauseSel + 2) % 3; invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                pauseSel = (pauseSel + 1) % 3; invalidate(); return true;
            case KeyEvent.KEYCODE_BUTTON_START:
                screen = PLAYING; updateHints(); return true;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (pauseSel == 0) { screen = PLAYING; }
                else if (pauseSel == 1) { beginMode(game.mode()); }
                else { toMenu(); }
                updateHints();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                screen = PLAYING; updateHints(); return true;
            default:
                return isGameKey(code);
        }
    }

    private boolean keyOver(int code, boolean down) {
        if (!down) return true;
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_START:
                beginMode(game.mode()); return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                toMenu(); return true;
            default:
                return isGameKey(code);
        }
    }

    private void toMenu() {
        screen = MENU;
        clearHeld();
        updateHints();
        invalidate();
    }

    /** Keys the game owns even when idle, so they never leak back to the system. */
    private static boolean isGameKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y: case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return false;
        }
    }

    private void dasReset() { dasTimer = 0; dasCharged = false; arrTimer = 0; }

    private void clearHeld() { leftHeld = rightHeld = downHeld = false; dasReset(); }

    /** True while a game is live, so the activity keeps Back from leaving mid-play. */
    boolean isPlaying() { return screen == PLAYING || screen == PAUSED; }

    /* ---- high scores ---- */

    private String bestKey() {
        return game.mode() == GameModel.MODE_MARATHON ? "best_marathon"
                : game.mode() == GameModel.MODE_SPRINT ? "best_sprint" : "best_endless";
    }

    private void saveScore() {
        String k = bestKey();
        if (game.mode() == GameModel.MODE_SPRINT) {
            if (!game.won) return;          // only a finished 40-line run has a time
            long prev = prefs.getLong(k, Long.MAX_VALUE);
            if (game.elapsedMs < prev) prefs.edit().putLong(k, game.elapsedMs).apply();
        } else {
            long prev = prefs.getLong(k, 0);
            if (game.score > prev) prefs.edit().putLong(k, game.score).apply();
        }
    }

    /* ---- layout ---- */

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float pad = sp(6);
        // Fit the 10x20 field plus a side panel on each side (about 5 minos wide).
        float byCols = (w - 2 * pad) / (GameModel.COLS + 10f);
        float byRows = (h - 2 * pad) / (GameModel.ROWS + 1f);
        cells = Math.max(sp(8), Math.min(byCols, byRows));
        boardW = cells * GameModel.COLS;
        boardH = cells * GameModel.ROWS;
        boardX = (w - boardW) / 2f;
        boardY = (h - boardH) / 2f;
        leftX = pad;
        rightX = boardX + boardW + cells * 0.6f;
    }

    /* ---- drawing ---- */

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(cBackground);
        switch (screen) {
            case MENU: drawMenu(c); break;
            default:
                drawBoard(c);
                drawPanels(c);
                if (screen == PAUSED) drawScrim(c, getResources().getString(R.string.blocks_paused), pauseItems, pauseSel);
                if (screen == OVER) drawOver(c);
                break;
        }
    }

    private void drawMenu(Canvas c) {
        float w = getWidth(), h = getHeight();
        text.setColor(cFg);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(sp(26));
        c.drawText(getResources().getString(R.string.blocks_title), w / 2f, h * 0.22f, text);

        text.setTextSize(sp(13));
        text.setColor(cSecondary);
        c.drawText(getResources().getString(R.string.blocks_pick_mode), w / 2f, h * 0.30f, text);

        float y = h * 0.42f;
        float rowH = sp(34);
        for (int i = 0; i < modeNames.length; i++) {
            boolean sel = i == menuSel;
            float bw = Math.min(w * 0.7f, sp(260));
            rect.set((w - bw) / 2f, y - rowH * 0.42f, (w + bw) / 2f, y + rowH * 0.42f);
            fill.setColor(sel ? cAccent : cPanel);
            c.drawRoundRect(rect, sp(6), sp(6), fill);
            text.setColor(sel ? onAccent() : cFg);
            text.setTextSize(sp(16));
            c.drawText(modeNames[i], w / 2f, y + sp(5.5f), text);

            String best = bestLine(i);
            if (best != null) {
                text.setColor(sel ? onAccent() : cSecondary);
                text.setTextSize(sp(10));
                c.drawText(best, w / 2f, y + rowH * 0.42f - sp(2), text);
            }
            y += rowH + sp(8);
        }

        text.setColor(cSecondary);
        text.setTextSize(sp(12));
        c.drawText(getResources().getString(R.string.blocks_start_level, startLevel),
                w / 2f, y + sp(6), text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private String bestLine(int mode) {
        if (mode == GameModel.MODE_SPRINT) {
            long t = prefs.getLong("best_sprint", 0);
            return t > 0 ? getResources().getString(R.string.blocks_best_time, fmtTime(t)) : null;
        }
        long s = prefs.getLong(mode == GameModel.MODE_MARATHON ? "best_marathon" : "best_endless", 0);
        return s > 0 ? getResources().getString(R.string.blocks_best_score, s) : null;
    }

    private void drawBoard(Canvas c) {
        // Well background and grid.
        fill.setColor(blend(cBackground, Color.BLACK, 0.35f));
        rect.set(boardX, boardY, boardX + boardW, boardY + boardH);
        c.drawRect(rect, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, sp(0.5f)));
        stroke.setColor(cGrid);
        for (int r = 1; r < GameModel.ROWS; r++) {
            float y = boardY + r * cells;
            c.drawLine(boardX, y, boardX + boardW, y, stroke);
        }
        for (int col = 1; col < GameModel.COLS; col++) {
            float x = boardX + col * cells;
            c.drawLine(x, boardY, x, boardY + boardH, stroke);
        }

        // Settled blocks.
        for (int r = 0; r < GameModel.ROWS; r++) {
            for (int col = 0; col < GameModel.COLS; col++) {
                int t = game.cell[r][col];
                if (t != GameModel.EMPTY) mino(c, boardX + col * cells, boardY + r * cells, cells, COLORS[t], 1f);
            }
        }

        // Ghost and active piece.
        if (game.piece != null && screen != OVER) {
            int drop = game.ghostDrop();
            int[][] cur = game.piece.cells(tmp);
            for (int[] rc : cur) {
                int gr = rc[0] + drop;
                if (gr >= 0) ghost(c, boardX + rc[1] * cells, boardY + gr * cells, cells, COLORS[game.piece.type]);
            }
            for (int[] rc : cur) {
                if (rc[0] >= 0) mino(c, boardX + rc[1] * cells, boardY + rc[0] * cells, cells, COLORS[game.piece.type], 1f);
            }
        }

        // Border.
        stroke.setColor(blend(cBackground, cFg, 0.3f));
        stroke.setStrokeWidth(Math.max(1.5f, sp(1f)));
        rect.set(boardX, boardY, boardX + boardW, boardY + boardH);
        c.drawRect(rect, stroke);

        drawClearFlash(c);
    }

    private void drawClearFlash(Canvas c) {
        long age = game.elapsedMs - game.lastClearAt;
        if (game.lastClearLines <= 0 && game.lastSpin == GameModel.SPIN_NONE) return;
        if (age > 1200 || game.lastClearAt == 0) return;
        String label = clearLabel();
        if (label == null) return;
        float alpha = 1f - age / 1200f;
        text.setColor(withAlpha(cAccent, alpha));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(sp(15));
        c.drawText(label, boardX + boardW / 2f, boardY + boardH * 0.4f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private String clearLabel() {
        String spin = game.lastSpin == GameModel.SPIN_FULL ? getResources().getString(R.string.blocks_tspin)
                : game.lastSpin == GameModel.SPIN_MINI ? getResources().getString(R.string.blocks_tspin_mini) : null;
        String lineWord;
        switch (game.lastClearLines) {
            case 1: lineWord = getResources().getString(R.string.blocks_single); break;
            case 2: lineWord = getResources().getString(R.string.blocks_double); break;
            case 3: lineWord = getResources().getString(R.string.blocks_triple); break;
            case 4: lineWord = getResources().getString(R.string.blocks_tetris); break;
            default: lineWord = null;
        }
        String base;
        if (spin != null && lineWord != null) base = spin + " " + lineWord;
        else if (spin != null) base = spin;
        else base = lineWord;
        if (base == null) return null;
        if (game.lastBackToBack) base = getResources().getString(R.string.blocks_b2b) + " " + base;
        return base;
    }

    private void drawPanels(Canvas c) {
        float panelW = cells * 4.4f;
        // Hold (left).
        float y = boardY;
        label(c, leftX, y, getResources().getString(R.string.blocks_hold));
        y += sp(16);
        pieceBox(c, leftX, y, panelW, cells * 2.6f, game.holdType);
        y += cells * 2.6f + sp(16);

        // Stats under the hold box.
        stat(c, leftX, y, getResources().getString(R.string.blocks_score), Long.toString(game.score)); y += sp(30);
        stat(c, leftX, y, getResources().getString(R.string.blocks_lines),
                game.mode() == GameModel.MODE_SPRINT
                        ? game.lines + " / " + GameModel.SPRINT_GOAL
                        : game.mode() == GameModel.MODE_MARATHON
                        ? game.lines + " / " + GameModel.MARATHON_GOAL
                        : Integer.toString(game.lines)); y += sp(30);
        stat(c, leftX, y, getResources().getString(R.string.blocks_level), Integer.toString(game.level)); y += sp(30);
        stat(c, leftX, y, getResources().getString(R.string.blocks_time), fmtTime(game.elapsedMs)); y += sp(30);
        if (game.combo > 0) {
            text.setColor(cAccent); text.setTextSize(sp(12)); text.setTextAlign(Paint.Align.LEFT);
            c.drawText(getResources().getString(R.string.blocks_combo, game.combo), leftX, y, text);
        }

        // Next queue (right).
        float ry = boardY;
        label(c, rightX, ry, getResources().getString(R.string.blocks_next));
        ry += sp(16);
        int[] prev = game.preview();
        float boxH = cells * 2.2f;
        for (int i = 0; i < prev.length; i++) {
            if (ry + boxH > getHeight()) break;
            pieceBox(c, rightX, ry, panelW, boxH, prev[i]);
            ry += boxH + sp(4);
        }
    }

    private void label(Canvas c, float x, float y, String s) {
        text.setColor(cSecondary);
        text.setTextSize(sp(11));
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(s, x, y + sp(10), text);
    }

    private void stat(Canvas c, float x, float y, String name, String value) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(cSecondary);
        text.setTextSize(sp(10));
        c.drawText(name, x, y, text);
        text.setColor(cFg);
        text.setTextSize(sp(15));
        c.drawText(value, x, y + sp(15), text);
    }

    /** Draw a piece type centred inside a panel box; type EMPTY draws just the frame. */
    private void pieceBox(Canvas c, float x, float y, float w, float h, int type) {
        fill.setColor(cPanel);
        rect.set(x, y, x + w, y + h);
        c.drawRoundRect(rect, sp(4), sp(4), fill);
        if (type == GameModel.EMPTY) return;
        int[] box = Piece.box(type, 0);
        int minC = 9, maxC = 0, minR = 9, maxR = 0;
        for (int i = 0; i < 4; i++) {
            int rr = box[i * 2], cc = box[i * 2 + 1];
            minR = Math.min(minR, rr); maxR = Math.max(maxR, rr);
            minC = Math.min(minC, cc); maxC = Math.max(maxC, cc);
        }
        float pc = cells * 0.7f;
        float pw = (maxC - minC + 1) * pc, ph = (maxR - minR + 1) * pc;
        float ox = x + (w - pw) / 2f, oy = y + (h - ph) / 2f;
        for (int i = 0; i < 4; i++) {
            float bx = ox + (box[i * 2 + 1] - minC) * pc;
            float by = oy + (box[i * 2] - minR) * pc;
            mino(c, bx, by, pc, COLORS[type], 1f);
        }
    }

    private void mino(Canvas c, float x, float y, float s, int color, float alpha) {
        float g = Math.max(1f, s * 0.06f);
        rect.set(x + g, y + g, x + s - g, y + s - g);
        fill.setColor(withAlpha(color, alpha));
        c.drawRoundRect(rect, s * 0.14f, s * 0.14f, fill);
        fill.setColor(withAlpha(0xFFFFFFFF, alpha * 0.18f));
        rect.set(x + g, y + g, x + s - g, y + s * 0.42f);
        c.drawRoundRect(rect, s * 0.14f, s * 0.14f, fill);
    }

    private void ghost(Canvas c, float x, float y, float s, int color) {
        float g = Math.max(1f, s * 0.08f);
        rect.set(x + g, y + g, x + s - g, y + s - g);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, s * 0.08f));
        stroke.setColor(withAlpha(color, 0.5f));
        c.drawRoundRect(rect, s * 0.14f, s * 0.14f, stroke);
    }

    private void drawScrim(Canvas c, String title, String[] items, int sel) {
        float w = getWidth(), h = getHeight();
        fill.setColor(withAlpha(cBackground, 0.86f));
        c.drawRect(0, 0, w, h, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(cFg);
        text.setTextSize(sp(22));
        c.drawText(title, w / 2f, h * 0.3f, text);
        float y = h * 0.44f;
        float rowH = sp(34);
        for (int i = 0; i < items.length; i++) {
            boolean s = i == sel;
            float bw = Math.min(w * 0.6f, sp(220));
            rect.set((w - bw) / 2f, y - rowH * 0.42f, (w + bw) / 2f, y + rowH * 0.42f);
            fill.setColor(s ? cAccent : cPanel);
            c.drawRoundRect(rect, sp(6), sp(6), fill);
            text.setColor(s ? onAccent() : cFg);
            text.setTextSize(sp(15));
            c.drawText(items[i], w / 2f, y + sp(5.5f), text);
            y += rowH + sp(8);
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawOver(Canvas c) {
        float w = getWidth(), h = getHeight();
        fill.setColor(withAlpha(cBackground, 0.88f));
        c.drawRect(0, 0, w, h, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(game.won ? cAccent : cFg);
        text.setTextSize(sp(24));
        c.drawText(getResources().getString(game.won ? R.string.blocks_clear : R.string.blocks_over),
                w / 2f, h * 0.26f, text);

        float y = h * 0.4f;
        text.setColor(cFg);
        text.setTextSize(sp(15));
        if (game.mode() == GameModel.MODE_SPRINT && game.won) {
            c.drawText(getResources().getString(R.string.blocks_your_time, fmtTime(game.elapsedMs)), w / 2f, y, text);
        } else {
            c.drawText(getResources().getString(R.string.blocks_your_score, game.score), w / 2f, y, text);
        }
        y += sp(24);
        text.setColor(cSecondary);
        text.setTextSize(sp(12));
        c.drawText(getResources().getString(R.string.blocks_lines) + ": " + game.lines
                + "    " + getResources().getString(R.string.blocks_level) + " " + game.level, w / 2f, y, text);
        y += sp(22);
        String best = bestLine(game.mode());
        if (best != null) c.drawText(best, w / 2f, y, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    /* ---- hints ---- */

    private void updateHints() {
        if (hints == null) return;
        switch (screen) {
            case MENU:
                hints.set("up/dn", getResources().getString(R.string.blocks_hint_choose),
                        "< >", getResources().getString(R.string.blocks_hint_level),
                        "A", getResources().getString(R.string.blocks_hint_play));
                break;
            case PLAYING:
                hints.set("A", getResources().getString(R.string.blocks_hint_rotate),
                        "Y", getResources().getString(R.string.blocks_hold),
                        "up", getResources().getString(R.string.blocks_hint_drop),
                        "Start", getResources().getString(R.string.blocks_hint_pause));
                break;
            case PAUSED:
                hints.set("up/dn", getResources().getString(R.string.blocks_hint_choose),
                        "A", getResources().getString(R.string.blocks_hint_select),
                        "B", getResources().getString(R.string.blocks_hint_resume));
                break;
            case OVER:
                hints.set("A", getResources().getString(R.string.blocks_hint_again),
                        "B", getResources().getString(R.string.blocks_hint_menu));
                break;
            default:
                break;
        }
    }

    /* ---- small helpers ---- */

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    private static int withAlpha(int color, float factor) {
        int a = Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, factor)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static int blend(int base, int over, float f) {
        return Color.rgb(
                Math.round(Color.red(base) * (1 - f) + Color.red(over) * f),
                Math.round(Color.green(base) * (1 - f) + Color.green(over) * f),
                Math.round(Color.blue(base) * (1 - f) + Color.blue(over) * f));
    }

    /** Dark text on a light accent, light text on a dark one, so the label always reads. */
    private int onAccent() {
        return Color.luminance(cAccent | 0xFF000000) > 0.5f ? 0xFF101010 : 0xFFFFFFFF;
    }

    private static String fmtTime(long ms) {
        long s = ms / 1000;
        long cs = (ms % 1000) / 10;
        return String.format(java.util.Locale.US, "%d:%02d.%02d", s / 60, s % 60, cs);
    }
}
