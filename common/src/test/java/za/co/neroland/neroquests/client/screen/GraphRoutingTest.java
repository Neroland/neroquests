package za.co.neroland.neroquests.client.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Elbow routing: axis-aligned segments that leave and enter the facing node edges. */
class GraphRoutingTest {

    private static final int S = GraphGeometry.NODE;

    @Test
    void sameRowIsOneStraightSegment() {
        int[] route = GraphRouting.route(0, 0, 60, 0, S);
        assertArrayEquals(new int[] {S, S / 2, 60, S / 2}, route);
        assertEquals(GraphRouting.ARRIVE_RIGHT, GraphRouting.arrival(route));
    }

    @Test
    void diagonalNeighboursGetHorizontalVerticalHorizontal() {
        int[] route = GraphRouting.route(0, 0, 120, 60, S);
        assertEquals(8, route.length);
        assertTrue(GraphRouting.axisAligned(route));
        assertEquals(S, route[0], "leaves the right edge");
        assertEquals(S / 2, route[1]);
        assertEquals(route[1], route[3], "first leg horizontal");
        assertEquals(route[2], route[4], "middle leg vertical");
        assertEquals(route[5], route[7], "last leg horizontal");
        assertEquals(120, route[6], "ends on the left edge");
        assertEquals(60 + S / 2, route[7]);
        assertEquals(GraphRouting.ARRIVE_RIGHT, GraphRouting.arrival(route));
    }

    @Test
    void backwardsRoutesEnterFromTheRight() {
        int[] route = GraphRouting.route(120, 0, 0, 60, S);
        assertTrue(GraphRouting.axisAligned(route));
        assertEquals(120, route[0]);
        assertEquals(S, route[route.length - 2]);
        assertEquals(GraphRouting.ARRIVE_LEFT, GraphRouting.arrival(route));
    }

    @Test
    void stackedNodesRouteVertically() {
        int[] down = GraphRouting.route(0, 0, 0, 60, S);
        assertArrayEquals(new int[] {S / 2, S, S / 2, 60}, down);
        assertEquals(GraphRouting.ARRIVE_DOWN, GraphRouting.arrival(down));
        int[] up = GraphRouting.route(10, 90, 0, 0, S);
        assertTrue(GraphRouting.axisAligned(up));
        assertEquals(90, up[1], "leaves the top edge");
        assertEquals(S, up[up.length - 1], "enters the bottom edge");
        assertEquals(GraphRouting.ARRIVE_UP, GraphRouting.arrival(up));
    }

    @Test
    void everyRouteIsAxisAligned() {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int[] route = GraphRouting.route(0, 0, dx * 30, dy * 30, S);
                assertTrue(GraphRouting.axisAligned(route), "route to " + dx + "," + dy);
                assertTrue(route.length >= 4 && route.length % 2 == 0);
            }
        }
        // Overlapping (an author dragging a node onto another) still yields axis-aligned legs.
        assertTrue(GraphRouting.axisAligned(GraphRouting.route(0, 0, 7, 9, S)));
    }
}
