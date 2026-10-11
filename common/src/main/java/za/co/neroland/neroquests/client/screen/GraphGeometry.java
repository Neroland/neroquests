package za.co.neroland.neroquests.client.screen;

import java.util.Collection;

/**
 * Pure geometry of the quest graph (E3): where a grid cell lands on screen, which node, edge handle or
 * dependency line is under the mouse, where a dragged node snaps to, and how far the canvas may pan. No game
 * classes, so every rule is unit-tested.
 *
 * <p>Grid units are what chapters store ({@code x}/{@code y}); one unit is {@link #GRID} pixels, and a node is
 * a {@link #NODE}-pixel square whose top-left corner sits on the grid point. The edge handle is a small square
 * straddling a node's right edge, used to drag a new prerequisite link.
 */
public final class GraphGeometry {

    /** Pixels per grid unit. */
    public static final int GRID = 30;
    /** Side of a node square. */
    public static final int NODE = 22;
    /** Inset of grid (0, 0) from the canvas corner. */
    public static final int ORIGIN = 12;
    /** Side of the edge handle. */
    public static final int HANDLE = 6;
    /** How close (pixels) the mouse must be to a dependency line to hit it. */
    public static final double LINE_HIT = 3.0D;
    /** Extra pan room in edit mode, so a node can be dragged into empty space beside the graph. */
    public static final int EDIT_SLACK = 2 * GRID;
    /** Grid cells per row when looking for a free slot for a new quest. */
    public static final int FREE_SLOT_ROW = 8;

    private GraphGeometry() {
    }

    /** Screen x of a node's left edge. */
    public static int nodeX(int canvasX, int panX, int gridX) {
        return canvasX + ORIGIN + panX + gridX * GRID;
    }

    /** Screen y of a node's top edge. */
    public static int nodeY(int canvasY, int panY, int gridY) {
        return canvasY + ORIGIN + panY + gridY * GRID;
    }

    /** Whether ({@code mx}, {@code my}) is on the node whose top-left corner is ({@code nx}, {@code ny}). */
    public static boolean inNode(double mx, double my, int nx, int ny) {
        return mx >= nx && mx < nx + NODE && my >= ny && my < ny + NODE;
    }

    /** Left edge of a node's handle. */
    public static int handleX(int nx) {
        return nx + NODE - HANDLE / 2;
    }

    /** Top edge of a node's handle. */
    public static int handleY(int ny) {
        return ny + (NODE - HANDLE) / 2;
    }

    /** Whether ({@code mx}, {@code my}) is on the node's edge handle. Checked before {@link #inNode}. */
    public static boolean inHandle(double mx, double my, int nx, int ny) {
        int hx = handleX(nx);
        int hy = handleY(ny);
        return mx >= hx - 1 && mx < hx + HANDLE + 1 && my >= hy - 1 && my < hy + HANDLE + 1;
    }

    /**
     * The grid cell a node dragged so its top-left corner is at screen pixel {@code pixel} snaps to, clamped
     * to {@code [min, max]} (the server's coordinate bounds from the editor block).
     */
    public static int snap(double pixel, int canvasOrigin, int pan, int min, int max) {
        int cell = (int) Math.round((pixel - canvasOrigin - ORIGIN - pan) / GRID);
        return Math.max(min, Math.min(max, cell));
    }

    /** Shortest distance from a point to the segment a&ndash;b. */
    public static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0.0D ? 0.0D : ((px - ax) * dx + (py - ay) * dy) / lengthSquared;
        t = Math.max(0.0D, Math.min(1.0D, t));
        double cx = ax + t * dx;
        double cy = ay + t * dy;
        return Math.hypot(px - cx, py - cy);
    }

    /**
     * Whether the point is on the dependency line between two node centres — but not on either node itself
     * (clicking a node must stay a node click).
     */
    public static boolean onLine(double px, double py, int fromNx, int fromNy, int toNx, int toNy) {
        if (inNode(px, py, fromNx, fromNy) || inNode(px, py, toNx, toNy)) {
            return false;
        }
        double half = NODE / 2.0D;
        return distanceToSegment(px, py, fromNx + half, fromNy + half, toNx + half, toNy + half) <= LINE_HIT;
    }

    /**
     * The allowed pan range on one axis for a graph spanning grid {@code [minCell, maxCell]} in a view of
     * {@code view} pixels: {@code [lo, hi]}. With {@code minCell = 0} and no slack this is exactly the
     * original book's rule ({@code [min(0, view - graph), 0]}).
     */
    public static int clampPan(int pan, int minCell, int maxCell, int view, boolean edit) {
        int slack = edit ? EDIT_SLACK : 0;
        int hi = -minCell * GRID;
        int graph = maxCell * GRID + NODE + 24;
        int lo = Math.min(hi, view - graph);
        return Math.max(lo - slack, Math.min(hi + slack, pan));
    }

    /**
     * The first free grid cell for a new quest: scanning rows from the top, {@link #FREE_SLOT_ROW} cells per
     * row, skipping occupied cells. {@code occupied} holds {@link #key}s.
     */
    public static int[] freeSlot(Collection<Long> occupied) {
        for (int y = 0; y < 1024; y++) {
            for (int x = 0; x < FREE_SLOT_ROW; x++) {
                if (!occupied.contains(key(x, y))) {
                    return new int[] {x, y};
                }
            }
        }
        return new int[] {0, 0};
    }

    /** A cell as one long, for occupancy sets. */
    public static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
    }
}
