package org.andr36oid.blocks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * The falling-blocks engine: a 10x20 field, a seven-bag piece sequence, Super Rotation System
 * turns with wall kicks, a hold slot, a ghost, soft and hard drop, a lock delay that movement
 * resets, a level/gravity curve and guideline scoring with combos, back-to-back and T-spins.
 * It knows nothing about Android or drawing - {@link GameView} reads its state every frame and
 * feeds it input and the elapsed time.
 *
 * <p>The grid is {@code cell[row][col]}, row 0 at the top. An empty cell is {@link #EMPTY};
 * a filled one holds the piece type that settled there (its colour index). Pieces may sit
 * partly above row 0 while they spawn or turn; those minos have a negative row and are simply
 * not drawn.
 */
final class GameModel {

    static final int COLS = 10;
    static final int ROWS = 20;
    static final int EMPTY = -1;

    /** Game modes. Marathon ends at {@link #MARATHON_GOAL} lines, sprint at {@link #SPRINT_GOAL}. */
    static final int MODE_MARATHON = 0;
    static final int MODE_SPRINT = 1;
    static final int MODE_ENDLESS = 2;

    static final int MARATHON_GOAL = 150;
    static final int SPRINT_GOAL = 40;

    /** How many upcoming pieces the preview shows. */
    static final int PREVIEW = 5;

    private static final long LOCK_DELAY_MS = 500;
    private static final int LOCK_MOVE_LIMIT = 15;
    private static final long SOFT_DROP_MS = 30;

    /** No T-spin, mini T-spin, full T-spin: the kinds scored differently. */
    static final int SPIN_NONE = 0, SPIN_MINI = 1, SPIN_FULL = 2;

    final int[][] cell = new int[ROWS][COLS];

    private int mode;
    private int startLevel;
    private final Random rng = new Random();
    private final Deque<Integer> bag = new ArrayDeque<>();

    Piece piece;
    int holdType = EMPTY;
    private boolean holdUsed;

    long score;
    int lines;
    int level;
    int combo = -1;
    boolean backToBack;

    boolean gameOver;
    boolean won;

    /** Set by the last clear so the view can flash a label ("Tetris", "T-Spin Double"...). */
    int lastClearLines;
    int lastSpin = SPIN_NONE;
    boolean lastBackToBack;
    long lastClearAt;

    long elapsedMs;
    private long gravityAccum;
    private long lockAccum;
    private boolean resting;
    private int lockMoves;
    private boolean lastMoveWasRotate;
    private int lastKickIndex;

    private final int[][] tmp = new int[4][2];

    void start(int mode, int startLevel) {
        this.mode = mode;
        this.startLevel = Math.max(1, startLevel);
        for (int[] r : cell) java.util.Arrays.fill(r, EMPTY);
        bag.clear();
        holdType = EMPTY;
        holdUsed = false;
        score = 0;
        lines = 0;
        level = this.startLevel;
        combo = -1;
        backToBack = false;
        gameOver = false;
        won = false;
        elapsedMs = 0;
        gravityAccum = 0;
        lockAccum = 0;
        resting = false;
        lockMoves = 0;
        lastClearLines = 0;
        lastSpin = SPIN_NONE;
        spawn();
    }

    int mode() { return mode; }

    /* ---- the seven-bag sequence -------------------------------------------------- */

    private void refill() {
        if (bag.size() > PREVIEW) return;
        List<Integer> seven = new ArrayList<>(Piece.COUNT);
        for (int i = 0; i < Piece.COUNT; i++) seven.add(i);
        Collections.shuffle(seven, rng);
        bag.addAll(seven);
    }

    private int nextType() {
        refill();
        return bag.pollFirst();
    }

    /** The upcoming piece types for the preview, without consuming them. */
    int[] preview() {
        refill();
        int[] out = new int[PREVIEW];
        int i = 0;
        for (Integer t : bag) {
            if (i >= PREVIEW) break;
            out[i++] = t;
        }
        return out;
    }

    private void spawn() {
        spawn(nextType());
    }

    private void spawn(int type) {
        // Shared spawn: box top-left at row -1, col 3, so the body lands on the top rows.
        piece = new Piece(type, -1, 3);
        resting = false;
        lockAccum = 0;
        gravityAccum = 0;
        lockMoves = 0;
        lastMoveWasRotate = false;
        if (collides(piece)) {
            gameOver = true;     // block out: no room for the new piece
        }
    }

    /* ---- collision --------------------------------------------------------------- */

    private boolean collides(Piece p) {
        int[][] c = p.cells(tmp);
        for (int[] rc : c) {
            int r = rc[0], col = rc[1];
            if (col < 0 || col >= COLS || r >= ROWS) return true;
            if (r >= 0 && cell[r][col] != EMPTY) return true;
        }
        return false;
    }

    /* ---- input ------------------------------------------------------------------- */

    /** Shift left/right by one. Returns true if it moved. */
    boolean move(int dCol) {
        if (gameOver) return false;
        Piece p = piece.copy();
        p.col += dCol;
        if (collides(p)) return false;
        piece.col += dCol;
        onMoved(false);
        return true;
    }

    /** Turn clockwise (dir 0) or counter-clockwise (dir 1), trying the wall kicks in order. */
    boolean rotate(int dir) {
        if (gameOver || piece.type == Piece.O) return false;
        int from = piece.rot;
        int to = dir == 0 ? (from + 1) & 3 : (from + 3) & 3;
        int[] kicks = Piece.kicks(piece.type, from, dir);
        for (int i = 0; i * 2 + 1 < kicks.length; i++) {
            Piece p = piece.copy();
            p.rot = to;
            p.col += kicks[i * 2];
            p.row += kicks[i * 2 + 1];
            if (!collides(p)) {
                piece.rot = to;
                piece.col = p.col;
                piece.row = p.row;
                lastKickIndex = i;
                onMoved(true);
                return true;
            }
        }
        return false;
    }

    /** Soft drop by one row (scores one point). Returns true if it fell. */
    boolean softStep() {
        if (gameOver) return false;
        if (stepDown()) {
            score += 1;
            return true;
        }
        return false;
    }

    /** Drop straight down and lock at once, scoring two points per cell. */
    void hardDrop() {
        if (gameOver) return;
        int dropped = 0;
        while (stepDown()) dropped++;
        score += 2L * dropped;
        lockPiece();
    }

    /** Swap the active piece with the held one (once per piece). */
    boolean hold() {
        if (gameOver || holdUsed) return false;
        int incoming = holdType;
        holdType = piece.type;
        holdUsed = true;
        if (incoming == EMPTY) {
            spawn();
        } else {
            spawn(incoming);
        }
        return true;
    }

    private boolean stepDown() {
        Piece p = piece.copy();
        p.row += 1;
        if (collides(p)) return false;
        piece.row += 1;
        onMoved(false);
        return true;
    }

    /** A successful move/turn: cancels resting if the piece can fall again, else resets lock. */
    private void onMoved(boolean rotation) {
        lastMoveWasRotate = rotation;
        if (canFall()) {
            resting = false;
            lockAccum = 0;
        } else if (resting && lockMoves < LOCK_MOVE_LIMIT) {
            lockAccum = 0;        // reset the lock timer, up to the move cap
            lockMoves++;
        }
    }

    private boolean canFall() {
        Piece p = piece.copy();
        p.row += 1;
        return !collides(p);
    }

    /* ---- the per-frame tick ------------------------------------------------------ */

    /**
     * Advance by {@code dtMs} milliseconds. {@code softDrop} true runs gravity at the fast soft
     * rate. Handles falling and the lock delay; a line clear and the next spawn happen here.
     */
    void tick(long dtMs, boolean softDrop) {
        if (gameOver) return;
        elapsedMs += dtMs;
        long interval = softDrop ? Math.min(SOFT_DROP_MS, gravityMs()) : gravityMs();
        gravityAccum += dtMs;
        while (gravityAccum >= interval) {
            gravityAccum -= interval;
            if (stepDown()) {
                if (softDrop) score += 1;
            } else {
                break;            // resting on the stack; the lock timer takes over
            }
        }
        if (!canFall()) {
            resting = true;
            lockAccum += dtMs;
            if (lockAccum >= LOCK_DELAY_MS) {
                lockPiece();
            }
        } else {
            resting = false;
            lockAccum = 0;
        }
    }

    /** Milliseconds per row at the current level - the guideline gravity curve. */
    long gravityMs() {
        double t = Math.pow(0.8 - (level - 1) * 0.007, level - 1);
        return Math.max(12, Math.round(t * 1000));
    }

    /* ---- locking and clearing ---------------------------------------------------- */

    private void lockPiece() {
        int spin = detectSpin();
        int[][] c = piece.cells(tmp);
        boolean allAbove = true;
        for (int[] rc : c) {
            int r = rc[0], col = rc[1];
            if (r >= 0) {
                cell[r][col] = piece.type;
                allAbove = false;
            }
        }
        if (allAbove) {          // locked entirely above the field: top out
            gameOver = true;
            return;
        }
        int cleared = clearLines();
        score(cleared, spin);

        if (mode == MODE_SPRINT && lines >= SPRINT_GOAL) {
            gameOver = true;
            won = true;
            return;
        }
        if (mode == MODE_MARATHON && lines >= MARATHON_GOAL) {
            gameOver = true;
            won = true;
            return;
        }
        holdUsed = false;
        spawn();
    }

    private int clearLines() {
        int cleared = 0;
        for (int r = ROWS - 1; r >= 0; r--) {
            boolean full = true;
            for (int col = 0; col < COLS; col++) {
                if (cell[r][col] == EMPTY) { full = false; break; }
            }
            if (full) {
                cleared++;
                for (int rr = r; rr > 0; rr--) {
                    System.arraycopy(cell[rr - 1], 0, cell[rr], 0, COLS);
                }
                java.util.Arrays.fill(cell[0], EMPTY);
                r++;             // re-check the row that dropped into this one
            }
        }
        return cleared;
    }

    /** The three-corner rule: a T that just turned into place with 3+ corners filled. */
    private int detectSpin() {
        if (piece.type != Piece.T || !lastMoveWasRotate) return SPIN_NONE;
        int cr = piece.row + 1, cc = piece.col + 1;     // the T's centre cell
        boolean tl = occupied(cr - 1, cc - 1);
        boolean tr = occupied(cr - 1, cc + 1);
        boolean bl = occupied(cr + 1, cc - 1);
        boolean br = occupied(cr + 1, cc + 1);
        int corners = (tl ? 1 : 0) + (tr ? 1 : 0) + (bl ? 1 : 0) + (br ? 1 : 0);
        if (corners < 3) return SPIN_NONE;
        // The two corners the T points away from are its "front"; both filled = full T-spin.
        boolean front;
        switch (piece.rot) {
            case 0:  front = tl && tr; break;    // pointing up
            case 1:  front = tr && br; break;    // pointing right
            case 2:  front = bl && br; break;    // pointing down
            default: front = tl && bl; break;    // pointing left
        }
        if (front) return SPIN_FULL;
        // A wall-kick into the slot (the far kick) still counts as a full spin.
        return lastKickIndex >= 4 ? SPIN_FULL : SPIN_MINI;
    }

    private boolean occupied(int r, int c) {
        if (c < 0 || c >= COLS || r >= ROWS) return true;   // walls/floor count as filled
        if (r < 0) return false;
        return cell[r][c] != EMPTY;
    }

    private void score(int cleared, int spin) {
        lastClearLines = cleared;
        lastSpin = spin;
        lastClearAt = elapsedMs;

        boolean difficult = cleared == 4 || (spin != SPIN_NONE && cleared > 0);
        long base;
        if (spin == SPIN_FULL) {
            base = (cleared == 0 ? 400 : cleared == 1 ? 800 : cleared == 2 ? 1200 : 1600);
        } else if (spin == SPIN_MINI) {
            base = (cleared == 0 ? 100 : cleared == 1 ? 200 : 400);
        } else {
            base = (cleared == 0 ? 0 : cleared == 1 ? 100 : cleared == 2 ? 300
                    : cleared == 3 ? 500 : 800);
        }

        if (cleared > 0) {
            if (difficult && backToBack) {
                base = base * 3 / 2;        // back-to-back bonus
            }
            lastBackToBack = difficult && backToBack;
            backToBack = difficult;
            combo++;
            if (combo > 0) base += 50L * combo;
            lines += cleared;
            level = Math.max(startLevel, startLevel + lines / 10);
        } else {
            lastBackToBack = false;
            combo = -1;                     // a drop with no clear breaks the combo
        }
        score += base * level;
    }

    /* ---- ghost ------------------------------------------------------------------- */

    /** How many rows down the active piece would land - for drawing the ghost. */
    int ghostDrop() {
        if (piece == null) return 0;
        Piece p = piece.copy();
        int d = 0;
        while (true) {
            Piece n = p.copy();
            n.row += 1;
            if (collides(n)) break;
            p.row += 1;
            d++;
        }
        return d;
    }
}
