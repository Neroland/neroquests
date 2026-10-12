package za.co.neroland.neroquests.client.screen;

/**
 * Orthogonal ("elbow") routing of dependency lines between two quest nodes. Pure, so the shape rules are
 * unit-tested.
 *
 * <p>A route is a polyline {@code x0, y0, x1, y1, ...} in world pixels whose every segment is horizontal or
 * vertical. It leaves the prerequisite from the side facing the dependent and ends on the dependent's facing edge
 * (where the arrowhead goes):
 * <ul>
 *   <li>dependent clearly to the right (or left): out of the side, across to the midpoint, down/up, across in
 *       &mdash; horizontal&rarr;vertical&rarr;horizontal, or one straight segment when both centres line up;</li>
 *   <li>otherwise (stacked in the same column band): out of the bottom (or top), down to the midpoint,
 *       across, down in &mdash; vertical&rarr;horizontal&rarr;vertical;</li>
 *   <li>overlapping nodes (only while an author drags one onto another): a plain L between the centres.</li>
 * </ul>
 */
public final class GraphRouting {

    /** Minimum free space between two nodes for a side-to-side route. */
    public static final int SIDE_GAP = 4;

    /** Direction the route arrives in, for the arrowhead. */
    public static final int ARRIVE_RIGHT = 0;
    public static final int ARRIVE_LEFT = 1;
    public static final int ARRIVE_DOWN = 2;
    public static final int ARRIVE_UP = 3;

    private GraphRouting() {
    }

    /**
     * The route from the {@code size}-pixel node at ({@code fromX}, {@code fromY}) to the one at
     * ({@code toX}, {@code toY}) (top-left corners).
     */
    public static int[] route(int fromX, int fromY, int toX, int toY, int size) {
        int half = size / 2;
        int fromCy = fromY + half;
        int toCy = toY + half;
        int fromCx = fromX + half;
        int toCx = toX + half;
        if (toX >= fromX + size + SIDE_GAP) {
            return horizontal(fromX + size, fromCy, toX, toCy);
        }
        if (toX + size + SIDE_GAP <= fromX) {
            return horizontal(fromX, fromCy, toX + size, toCy);
        }
        if (toY >= fromY + size) {
            return vertical(fromCx, fromY + size, toCx, toY);
        }
        if (toY + size <= fromY) {
            return vertical(fromCx, fromY, toCx, toY + size);
        }
        if (fromCx == toCx || fromCy == toCy) {
            return new int[] {fromCx, fromCy, toCx, toCy};
        }
        return new int[] {fromCx, fromCy, toCx, fromCy, toCx, toCy};
    }

    private static int[] horizontal(int startX, int startY, int endX, int endY) {
        if (startY == endY) {
            return new int[] {startX, startY, endX, endY};
        }
        int midX = startX + (endX - startX) / 2;
        return new int[] {startX, startY, midX, startY, midX, endY, endX, endY};
    }

    private static int[] vertical(int startX, int startY, int endX, int endY) {
        if (startX == endX) {
            return new int[] {startX, startY, endX, endY};
        }
        int midY = startY + (endY - startY) / 2;
        return new int[] {startX, startY, startX, midY, endX, midY, endX, endY};
    }

    /** The direction of a route's last segment ({@code ARRIVE_*}). */
    public static int arrival(int[] points) {
        int n = points.length;
        int dx = points[n - 2] - points[n - 4];
        int dy = points[n - 1] - points[n - 3];
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0 ? ARRIVE_RIGHT : ARRIVE_LEFT;
        }
        return dy >= 0 ? ARRIVE_DOWN : ARRIVE_UP;
    }

    /** Whether every segment of the polyline is horizontal or vertical. */
    public static boolean axisAligned(int[] points) {
        for (int index = 0; index + 3 < points.length; index += 2) {
            if (points[index] != points[index + 2] && points[index + 1] != points[index + 3]) {
                return false;
            }
        }
        return true;
    }
}
