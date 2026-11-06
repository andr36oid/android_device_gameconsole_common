package org.andr36oid.blocks;

/**
 * The seven tetrominoes and the Super Rotation System (SRS) data used by {@link GameModel}:
 * the four minos of each piece in each of its four rotation states, and the wall-kick offsets
 * tried when a rotation is blocked. This is pure data and a few small helpers, no Android.
 *
 * <p>Coordinates are {@code (row, col)} inside the piece's own box with row increasing
 * downwards, the same way the board grid is stored. A piece on the board is this box's
 * top-left corner at {@link #row}/{@link #col} plus a rotation {@link #rot}; the occupied
 * board cells are {@link #cells(int[][])}.
 *
 * <p>Types are ordered so the index is also the colour index in {@link GameView}:
 * 0 I, 1 O, 2 T, 3 S, 4 Z, 5 J, 6 L.
 */
final class Piece {

    static final int I = 0, O = 1, T = 2, S = 3, Z = 4, J = 5, L = 6;
    static final int COUNT = 7;

    /**
     * CELLS[type][rot] is four {row, col} pairs flattened to eight ints, in the piece's box.
     * JLSTZ and T use a 3x3 box, I a 4x4 box, O a 4-wide box (cols 1..2) so every piece shares
     * the same spawn column offset.
     */
    private static final int[][][] CELLS = new int[COUNT][4][];

    static {
        // I - 4x4 box
        CELLS[I][0] = new int[]{1,0, 1,1, 1,2, 1,3};
        CELLS[I][1] = new int[]{0,2, 1,2, 2,2, 3,2};
        CELLS[I][2] = new int[]{2,0, 2,1, 2,2, 2,3};
        CELLS[I][3] = new int[]{0,1, 1,1, 2,1, 3,1};
        // O - never turns; cols 1..2 so col offset matches the others
        for (int r = 0; r < 4; r++) CELLS[O][r] = new int[]{0,1, 0,2, 1,1, 1,2};
        // T - 3x3
        CELLS[T][0] = new int[]{0,1, 1,0, 1,1, 1,2};
        CELLS[T][1] = new int[]{0,1, 1,1, 1,2, 2,1};
        CELLS[T][2] = new int[]{1,0, 1,1, 1,2, 2,1};
        CELLS[T][3] = new int[]{0,1, 1,0, 1,1, 2,1};
        // S - 3x3
        CELLS[S][0] = new int[]{0,1, 0,2, 1,0, 1,1};
        CELLS[S][1] = new int[]{0,1, 1,1, 1,2, 2,2};
        CELLS[S][2] = new int[]{1,1, 1,2, 2,0, 2,1};
        CELLS[S][3] = new int[]{0,0, 1,0, 1,1, 2,1};
        // Z - 3x3
        CELLS[Z][0] = new int[]{0,0, 0,1, 1,1, 1,2};
        CELLS[Z][1] = new int[]{0,2, 1,1, 1,2, 2,1};
        CELLS[Z][2] = new int[]{1,0, 1,1, 2,1, 2,2};
        CELLS[Z][3] = new int[]{0,1, 1,0, 1,1, 2,0};
        // J - 3x3
        CELLS[J][0] = new int[]{0,0, 1,0, 1,1, 1,2};
        CELLS[J][1] = new int[]{0,1, 0,2, 1,1, 2,1};
        CELLS[J][2] = new int[]{1,0, 1,1, 1,2, 2,2};
        CELLS[J][3] = new int[]{0,1, 1,1, 2,0, 2,1};
        // L - 3x3
        CELLS[L][0] = new int[]{0,2, 1,0, 1,1, 1,2};
        CELLS[L][1] = new int[]{0,1, 1,1, 2,1, 2,2};
        CELLS[L][2] = new int[]{1,0, 1,1, 1,2, 2,0};
        CELLS[L][3] = new int[]{0,0, 0,1, 1,1, 2,1};
    }

    /* ---- wall kicks -----------------------------------------------------------------
     * Standard SRS tables. Each entry is five {dCol, dRow} offsets tried in order; the first
     * that fits wins. The published tables use y-up, so dRow here is the negated y. Indexed by
     * [fromState][direction] where direction 0 is clockwise, 1 is counter-clockwise. */

    private static final int[][][] KICK_JLSTZ = new int[4][2][];
    private static final int[][][] KICK_I = new int[4][2][];
    /** O never kicks. */
    private static final int[][] KICK_O = {{0,0}};

    static {
        // JLSTZ clockwise (from -> from+1)
        KICK_JLSTZ[0][0] = new int[]{0,0, -1,0, -1,-1, 0,2, -1,2};
        KICK_JLSTZ[1][0] = new int[]{0,0, 1,0, 1,1, 0,-2, 1,-2};
        KICK_JLSTZ[2][0] = new int[]{0,0, 1,0, 1,-1, 0,2, 1,2};
        KICK_JLSTZ[3][0] = new int[]{0,0, -1,0, -1,1, 0,-2, -1,-2};
        // JLSTZ counter-clockwise (from -> from+3)
        KICK_JLSTZ[0][1] = new int[]{0,0, 1,0, 1,-1, 0,2, 1,2};
        KICK_JLSTZ[1][1] = new int[]{0,0, 1,0, 1,1, 0,-2, 1,-2};
        KICK_JLSTZ[2][1] = new int[]{0,0, -1,0, -1,-1, 0,2, -1,2};
        KICK_JLSTZ[3][1] = new int[]{0,0, -1,0, -1,1, 0,-2, -1,-2};
        // I clockwise
        KICK_I[0][0] = new int[]{0,0, -2,0, 1,0, -2,1, 1,-2};
        KICK_I[1][0] = new int[]{0,0, -1,0, 2,0, -1,-2, 2,1};
        KICK_I[2][0] = new int[]{0,0, 2,0, -1,0, 2,-1, -1,2};
        KICK_I[3][0] = new int[]{0,0, 1,0, -2,0, 1,2, -2,-1};
        // I counter-clockwise
        KICK_I[0][1] = new int[]{0,0, -1,0, 2,0, -1,-2, 2,1};
        KICK_I[1][1] = new int[]{0,0, 2,0, -1,0, 2,-1, -1,2};
        KICK_I[2][1] = new int[]{0,0, 1,0, -2,0, 1,2, -2,-1};
        KICK_I[3][1] = new int[]{0,0, -2,0, 1,0, -2,1, 1,-2};
    }

    /** The kick offsets (pairs of {dCol, dRow}) for turning a piece of this type. */
    static int[] kicks(int type, int fromRot, int dir) {
        if (type == O) return KICK_O[0];
        return type == I ? KICK_I[fromRot][dir] : KICK_JLSTZ[fromRot][dir];
    }

    /* ---- instance ---- */

    int type;
    int rot;
    int row;
    int col;

    Piece(int type, int row, int col) {
        this.type = type;
        this.rot = 0;
        this.row = row;
        this.col = col;
    }

    Piece copy() {
        Piece p = new Piece(type, row, col);
        p.rot = rot;
        return p;
    }

    /** The four {row, col} board cells this piece occupies, filled into {@code out} (4x2). */
    int[][] cells(int[][] out) {
        int[] c = CELLS[type][rot];
        for (int i = 0; i < 4; i++) {
            out[i][0] = row + c[i * 2];
            out[i][1] = col + c[i * 2 + 1];
        }
        return out;
    }

    /** The box cells for a given type and rotation (row, col pairs, flattened), for previews. */
    static int[] box(int type, int rot) {
        return CELLS[type][rot];
    }
}
