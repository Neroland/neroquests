package za.co.neroland.neroquests.client.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** E3: node / handle / line hit-testing, snapping, label room and free slots. */
class GraphGeometryTest {

    @Test
    void nodesSitOnTheGrid() {
        assertEquals(60, GraphGeometry.world(2));
        assertEquals(-30, GraphGeometry.world(-1));
        int size = GraphGeometry.NODE;
        assertTrue(GraphGeometry.inNode(10, 10, 10, 10, size));
        assertTrue(GraphGeometry.inNode(10 + size - 1, 10 + size - 1, 10, 10, size));
        assertFalse(GraphGeometry.inNode(10 + size, 10, 10, 10, size));
        assertFalse(GraphGeometry.inNode(9, 10, 10, 10, size));
        assertTrue(GraphGeometry.inNode(22, 22, 10, 10, 13), "a zoomed-out node is smaller");
        assertFalse(GraphGeometry.inNode(23, 10, 10, 10, 13));
    }

    @Test
    void handleStraddlesTheRightEdge() {
        for (int size : new int[] {13, 20, 26, 39}) {
            int nx = 100;
            int ny = 100;
            int hx = GraphGeometry.handleX(nx, size);
            int hy = GraphGeometry.handleY(ny, size);
            assertTrue(hx < nx + size && hx + GraphGeometry.HANDLE > nx + size);
            assertTrue(GraphGeometry.inHandle(hx + 2, hy + 2, nx, ny, size));
            assertTrue(GraphGeometry.inHandle(nx + size + 1, ny + size / 2, nx, ny, size));
            assertFalse(GraphGeometry.inHandle(nx + 1, ny + 1, nx, ny, size), "the node body is not the handle");
        }
    }

    @Test
    void dropsSnapAndClamp() {
        assertEquals(3, GraphGeometry.snap(90, -512, 512));
        assertEquals(3, GraphGeometry.snap(98, -512, 512));
        assertEquals(4, GraphGeometry.snap(106, -512, 512));
        assertEquals(-2, GraphGeometry.snap(-60, -512, 512));
        assertEquals(512, GraphGeometry.snap(1_000_000, -512, 512));
        assertEquals(-512, GraphGeometry.snap(-1_000_000, -512, 512));
    }

    @Test
    void linesAreHitNearTheSegments() {
        assertEquals(0.0D, GraphGeometry.distanceToSegment(5, 0, 0, 0, 10, 0), 1e-9);
        assertEquals(3.0D, GraphGeometry.distanceToSegment(5, 3, 0, 0, 10, 0), 1e-9);
        assertEquals(5.0D, GraphGeometry.distanceToSegment(13, 4, 0, 0, 10, 0), 1e-9);
        assertEquals(1.0D, GraphGeometry.distanceToSegment(1, 1, 1, 0, 1, 0), 1e-9, "degenerate segment");
        int[] elbow = {0, 0, 20, 0, 20, 40, 50, 40};
        assertTrue(GraphGeometry.onPolyline(10, 2, elbow));
        assertTrue(GraphGeometry.onPolyline(21, 20, elbow));
        assertTrue(GraphGeometry.onPolyline(45, 42, elbow));
        assertFalse(GraphGeometry.onPolyline(10, 20, elbow), "inside the elbow, away from every segment");
        assertFalse(GraphGeometry.onPolyline(60, 40, elbow), "past the end");
    }

    @Test
    void labelsGetTheRoomBetweenNeighbours() {
        // Nodes two cells apart on one row: 60 px apart, label room 56.
        int[] xs = {0, 60, 120};
        int[] ys = {0, 0, 0};
        assertEquals(56, GraphGeometry.labelRoom(1, xs, ys, 3));
        // A lone node gets the cap.
        assertEquals(GraphGeometry.LABEL_MAX, GraphGeometry.labelRoom(0, new int[] {0}, new int[] {0}, 1));
        // A node two rows down (60 px) does not limit the label; a node directly under the label does.
        assertEquals(GraphGeometry.LABEL_MAX, GraphGeometry.labelRoom(0, new int[] {0, 0}, new int[] {0, 60}, 2));
        assertEquals(0, GraphGeometry.labelRoom(0, new int[] {0, 0}, new int[] {0, 30}, 2));
        // Adjacent cells leave no useful room.
        assertEquals(26, GraphGeometry.labelRoom(0, new int[] {0, 30}, new int[] {0, 0}, 2));
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
