package za.co.neroland.neroquests.client.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** E3: node / handle / line hit-testing, snapping, pan limits and free slots. */
class GraphGeometryTest {

    @Test
    void nodesSitOnTheGrid() {
        assertEquals(100 + 12 + 5 + 2 * 30, GraphGeometry.nodeX(100, 5, 2));
        assertEquals(50 + 12 - 7 - 30, GraphGeometry.nodeY(50, -7, -1));
        assertTrue(GraphGeometry.inNode(10, 10, 10, 10));
        assertTrue(GraphGeometry.inNode(31, 31, 10, 10));
        assertFalse(GraphGeometry.inNode(32, 10, 10, 10));
        assertFalse(GraphGeometry.inNode(9, 10, 10, 10));
    }

    @Test
    void handleStraddlesTheRightEdge() {
        int nx = 100;
        int ny = 100;
        int hx = GraphGeometry.handleX(nx);
        int hy = GraphGeometry.handleY(ny);
        assertTrue(hx < nx + GraphGeometry.NODE && hx + GraphGeometry.HANDLE > nx + GraphGeometry.NODE);
        assertTrue(GraphGeometry.inHandle(hx + 2, hy + 2, nx, ny));
        assertTrue(GraphGeometry.inHandle(nx + GraphGeometry.NODE + 1, ny + GraphGeometry.NODE / 2, nx, ny));
        assertFalse(GraphGeometry.inHandle(nx + 2, ny + 2, nx, ny), "the node body is not the handle");
    }

    @Test
    void dropsSnapAndClamp() {
        // Canvas at x=0, no pan: grid cell 3 starts at 12 + 90 = 102.
        assertEquals(3, GraphGeometry.snap(102, 0, 0, -512, 512));
        assertEquals(3, GraphGeometry.snap(110, 0, 0, -512, 512));
        assertEquals(4, GraphGeometry.snap(118, 0, 0, -512, 512));
        assertEquals(-2, GraphGeometry.snap(12 - 60, 0, 0, -512, 512));
        assertEquals(512, GraphGeometry.snap(1_000_000, 0, 0, -512, 512));
        assertEquals(-512, GraphGeometry.snap(-1_000_000, 0, 0, -512, 512));
        assertEquals(0, GraphGeometry.snap(12 + 40, 0, 40, -512, 512), "pan is taken into account");
    }

    @Test
    void linesAreHitNearTheSegmentButNotOnNodes() {
        assertEquals(0.0D, GraphGeometry.distanceToSegment(5, 0, 0, 0, 10, 0), 1e-9);
        assertEquals(3.0D, GraphGeometry.distanceToSegment(5, 3, 0, 0, 10, 0), 1e-9);
        assertEquals(5.0D, GraphGeometry.distanceToSegment(13, 4, 0, 0, 10, 0), 1e-9);
        assertEquals(1.0D, GraphGeometry.distanceToSegment(1, 1, 1, 0, 1, 0), 1e-9, "degenerate segment");
        // Nodes at (0,0) and (90,0): the line runs along y = 11 between the centres.
        assertTrue(GraphGeometry.onLine(50, 12, 0, 0, 90, 0));
        assertFalse(GraphGeometry.onLine(50, 20, 0, 0, 90, 0));
        assertFalse(GraphGeometry.onLine(5, 11, 0, 0, 90, 0), "on the source node");
        assertFalse(GraphGeometry.onLine(95, 11, 0, 0, 90, 0), "on the target node");
    }

    @Test
    void panRangeMatchesTheOriginalBookInViewMode() {
        // Graph 0..4 (166 px) in a 282 px view: no pan at all.
        assertEquals(0, GraphGeometry.clampPan(50, 0, 4, 282, false));
        assertEquals(0, GraphGeometry.clampPan(-50, 0, 4, 282, false));
        // Graph wider than the view: [view - graph, 0].
        assertEquals(-66, GraphGeometry.clampPan(-500, 0, 4, 100, false));
        assertEquals(0, GraphGeometry.clampPan(500, 0, 4, 100, false));
        // Negative cells (custom chapters) can be scrolled to.
        assertEquals(90, GraphGeometry.clampPan(500, -3, 0, 100, false));
        // Edit mode adds slack on both sides.
        assertEquals(GraphGeometry.EDIT_SLACK, GraphGeometry.clampPan(500, 0, 4, 282, true));
        assertEquals(-GraphGeometry.EDIT_SLACK, GraphGeometry.clampPan(-500, 0, 4, 282, true));
    }

    @Test
    void freeSlotSkipsOccupiedCells() {
        assertArrayEquals(new int[] {0, 0}, GraphGeometry.freeSlot(Set.of()));
        assertArrayEquals(new int[] {1, 0}, GraphGeometry.freeSlot(Set.of(GraphGeometry.key(0, 0))));
        Set<Long> firstRow = new java.util.HashSet<>();
        for (int x = 0; x < GraphGeometry.FREE_SLOT_ROW; x++) {
            firstRow.add(GraphGeometry.key(x, 0));
        }
        assertArrayEquals(new int[] {0, 1}, GraphGeometry.freeSlot(firstRow));
        assertFalse(GraphGeometry.key(1, 2) == GraphGeometry.key(2, 1));
        assertFalse(GraphGeometry.key(-1, 0) == GraphGeometry.key(0, -1));
    }
}
