package org.andr36oid.keyboard.core;

/**
 * Moves the highlight around a Page with the d-pad or left stick. Left/right wrap within the
 * row, up/down wrap between the top and bottom rows. Up and down keep to the column the user
 * started in, so going down through a wide key (space) and back up lands where it began.
 */
public final class GridNav {
    public static final int UP = 0;
    public static final int RIGHT = 1;
    public static final int DOWN = 2;
    public static final int LEFT = 3;

    private Page page;
    private int row;
    private int col;
    private float anchorX;

    public GridNav(Page page) {
        this.page = page;
        home();
    }

    /** Start position: the "g"/"h" area of the middle letter row, where most letters are near. */
    public void home() {
        row = Math.min(2, page.rowCount() - 1);
        col = page.columnAt(row, Layouts.COLUMNS / 2f - 0.5f);
        anchorX = page.key(row, col).center();
    }

    public Page page() {
        return page;
    }

    public int row() {
        return row;
    }

    public int col() {
        return col;
    }

    public Key current() {
        return page.key(row, col);
    }

    /** Swap in another page and stay at the same spot on screen. */
    public void setPage(Page p) {
        float x = page.key(row, col).center();
        page = p;
        row = Math.min(row, p.rowCount() - 1);
        col = p.columnAt(row, x);
        anchorX = x;
    }

    public boolean focus(int code) {
        int[] rc = page.find(code);
        if (rc == null) return false;
        row = rc[0];
        col = rc[1];
        anchorX = current().center();
        return true;
    }

    public void move(int dir) {
        int n = page.row(row).size();
        switch (dir) {
            case LEFT:
                col = (col - 1 + n) % n;
                anchorX = current().center();
                break;
            case RIGHT:
                col = (col + 1) % n;
                anchorX = current().center();
                break;
            case UP:
                row = (row - 1 + page.rowCount()) % page.rowCount();
                col = page.columnAt(row, anchorX);
                break;
            case DOWN:
                row = (row + 1) % page.rowCount();
                col = page.columnAt(row, anchorX);
                break;
            default:
                break;
        }
    }
}
