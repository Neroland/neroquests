package za.co.neroland.neroquests.client.screen;

import java.util.Collection;

/**
 * Pure geometry of the quest graph (E3): node and edge-handle hit-testing, dependency-line hit-testing, where a
 * dragged node snaps to and where a new quest finds a free cell. No game classes, so every rule is unit-tested.
 *
 * <p>The graph lives in <em>world</em> pixels: grid cell ({@code x}, {@code y}) — what chapters store — has its
 * node's top-left corner at world ({@code x * GRID}, {@code y * GRID}) and the node is a {@link #NODE}-pixel
 * square. {@link GraphView} maps world pixels to the screen (pan and zoom); everything here that takes screen
 * coordinates also takes the node's on-screen size, so hit-testing matches what is drawn at any zoom.
 */
public final class GraphGeometry {

    /** World pixels per grid unit. */
    public static final int GRID = 30;
    /** Side of a node square, in world pixels. */
    public static final int NODE = 26;
    /** World height reserved under a node for its title label. */
    public static final int LABEL_HEIGHT = 12;
    /** Widest a node label may get, in world pixels. */
    public static final int LABEL_MAX = 96;
    /** Side of the edge handle (screen pixels). */
    public static final int HANDLE = 6;
    /** How close (screen pixels) the mouse must be to a dependency line to hit it. */
    public static final double LINE_HIT = 3.0D;
    /** Extra pan room in edit mode (world pixels), so a node can be dragged into empty space beside the graph. */
    public static final int EDIT_SLACK = 2 * GRID;
    /** Grid cells per row when looking for a free slot for a new quest. */
    public static final int FREE_SLOT_ROW = 8;

    private GraphGeometry() {
    }

    /** World x (or y) of grid cell {@code cell}'s node corner. */
    public static int world(int cell) {
        return cell * GRID;
    }

    /** Whether ({@code mx}, {@code my}) is on the {@code size}-pixel node whose top-left corner is ({@code nx}, {@code ny}). */
    public static boolean inNode(double mx, double my, int nx, int ny, int size) {
        return mx >= nx && mx < nx + size && my >= ny && my < ny + size;
    }

    /** Left edge of a node's handle. */
    public static int handleX(int nx, int size) {
        return nx + size - HANDLE / 2;
    }

    /** Top edge of a node's handle. */
    public static int handleY(int ny, int size) {
        return ny + (size - HANDLE) / 2;
    }

    /** Whether ({@code mx}, {@code my}) is on the node's edge handle. Checked before {@link #inNode}. */
    public static boolean inHandle(double mx, double my, int nx, int ny, int size) {
        int hx = handleX(nx, size);
        int hy = handleY(ny, size);
        return mx >= hx - 1 && mx < hx + HANDLE + 1 && my >= hy - 1 && my < hy + HANDLE + 1;
    }

    /**
     * The grid cell a node whose corner is at world pixel {@code world} snaps to, clamped to {@code [min, max]}
     * (the server's coordinate bounds from the editor block).
     */
    public static int snap(double world, int min, int max) {
        int cell = (int) Math.round(world / GRID);
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
     * Whether the point is within {@link #LINE_HIT} of the polyline {@code points} ({@code x0, y0, x1, y1, ...},
     * screen pixels).
     */
    public static boolean onPolyline(double px, double py, int[] points) {
        for (int index = 0; index + 3 < points.length; index += 2) {
            if (distanceToSegment(px, py, points[index], points[index + 1], points[index + 2], points[index + 3])
                    <= LINE_HIT) {
                return true;
            }
        }
        return false;
    }

    /**
     * The widest label (world pixels) node {@code index} can carry without running into a neighbour: the
     * horizontal distance to the nearest node whose label row or body shares the label's band, less a small gap,
     * capped at {@link #LABEL_MAX}. {@code xs}/{@code ys} are world corners of {@code count} nodes.
     */
    public static int labelRoom(int index, int[] xs, int[] ys, int count) {
        int room = LABEL_MAX + 4;
        for (int other = 0; other < count; other++) {
            if (other == index) {
                continue;
            }
            int dy = ys[other] - ys[index];
            // The other node's body or label overlaps this label's band (just under this node).
            if (dy > -LABEL_HEIGHT && dy < NODE + LABEL_HEIGHT) {
                room = Math.min(room, Math.abs(xs[other] - xs[index]));
            }
        }
        return Math.max(0, Math.min(LABEL_MAX, room - 4));
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
