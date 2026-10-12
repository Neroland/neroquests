package za.co.neroland.neroquests.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The graph camera: fit, clamp, zoom about the cursor, easing and overflow flags. */
class GraphViewTest {

    private static GraphView view(int x, int y, int w, int h) {
        GraphView view = new GraphView();
        view.setViewport(x, y, w, h);
        return view;
    }

    @Test
    void fitCentresTheBounds() {
        GraphView view = view(100, 50, 400, 300);
        view.setBounds(0, 0, 200, 100, 0);
        view.fit(true);
        assertEquals(1.0D, view.zoom(), 1e-9, "fits at 1x, never magnified past FIT_MAX_ZOOM");
        // The bounds' centre lands on the viewport's centre.
        assertEquals(300.0D, view.screenX(100), 1e-9);
        assertEquals(200.0D, view.screenY(50), 1e-9);
    }

    @Test
    void fitPicksTheLargestStepThatFits() {
        // 446 x 98 world px (the Groundwork chapter) in a 282 px wide canvas: needs <= 0.56 -> 0.5.
        assertEquals(0.5D, GraphView.fitZoom(446, 98, 282, 190, GraphView.FIT_MARGIN, 1.0D), 1e-9);
        assertEquals(0.75D, GraphView.fitZoom(446, 98, 400, 190, GraphView.FIT_MARGIN, 1.0D), 1e-9);
        assertEquals(1.0D, GraphView.fitZoom(100, 50, 400, 300, GraphView.FIT_MARGIN, 1.0D), 1e-9);
        assertEquals(1.5D, GraphView.fitZoom(100, 50, 400, 300, GraphView.FIT_MARGIN, 1.5D), 1e-9);
        assertEquals(0.5D, GraphView.fitZoom(5000, 5000, 100, 100, GraphView.FIT_MARGIN, 1.0D), 1e-9,
                "never below the smallest step");
    }

    @Test
    void fittedContentIsFullyVisible() {
        GraphView view = view(0, 0, 282, 190);
        view.setBounds(0, 0, 446, 98, 0);
        view.fit(true);
        assertEquals(0, view.overflow(), "nothing hangs off any edge after a fit");
        assertTrue(view.screenX(0) >= GraphView.FIT_MARGIN - 1e-9);
        assertTrue(view.screenX(446) <= 282 - GraphView.FIT_MARGIN + 1e-9);
    }

    @Test
    void readableFitStartsAWideChapterAtItsBeginning() {
        GraphView view = view(0, 0, 300, 200);
        view.setBounds(0, 0, 900, 60, 0);
        view.fit(true, 0.75D);
        assertEquals(0.75D, view.zoom(), 1e-9, "not below the readable minimum");
        assertEquals(GraphView.FIT_MARGIN, view.screenX(0), 1e-6, "the first quest sits at the left margin");
        assertEquals(100.0D, view.screenY(30), 1e-6, "vertically centred");
        assertEquals(GraphView.RIGHT, view.overflow());
        // The fit button really fits.
        view.fit(true);
        assertEquals(0.5D, view.zoom(), 1e-9);
        // Content that fits at the readable zoom is centred as usual.
        view.setBounds(0, 0, 200, 60, 0);
        view.fit(true, 0.75D);
        assertEquals(1.0D, view.zoom(), 1e-9);
        assertEquals(150.0D, view.screenX(100), 1e-6);
    }

    @Test
    void panClampsToTheContent() {
        GraphView view = view(0, 0, 200, 200);
        view.setBounds(0, 0, 1000, 100, 0);
        view.fit(true);
        view.zoomBy(1, 100, 100);
        view.zoomBy(1, 100, 100);
        view.snap();
        double zoom = view.zoom();
        // Panning far right stops with the content's right edge (plus margin) at the viewport edge.
        view.panBy(-100_000, 0, true);
        assertEquals(200.0D - GraphView.FIT_MARGIN, view.screenX(1000), 1e-6);
        // Panning far left stops with the left edge at the margin.
        view.panBy(100_000, 0, true);
        assertEquals(GraphView.FIT_MARGIN, view.screenX(0), 1e-6);
        // Content shorter than the view is pinned to the centre vertically.
        double y = view.screenY(50);
        view.panBy(0, 5_000, true);
        assertEquals(y, view.screenY(50), 1e-6);
        assertEquals(zoom, view.zoom(), 1e-9);
    }

    @Test
    void editSlackAllowsExtraRoom() {
        GraphView view = view(0, 0, 400, 300);
        view.setBounds(0, 0, 100, 100, 60);
        view.fit(true);
        double centre = view.cameraX();
        view.panBy(-100_000, 0, true);
        assertEquals(centre + 60, view.cameraX(), 1e-6);
        view.panBy(100_000, 0, true);
        assertEquals(centre - 60, view.cameraX(), 1e-6);
    }

    @Test
    void zoomKeepsThePointUnderTheCursorFixed() {
        GraphView view = view(10, 20, 600, 400);
        view.setBounds(-5000, -5000, 5000, 5000, 0);
        view.snap();
        double mx = 450;
        double my = 120;
        double wx = view.worldX(mx);
        double wy = view.worldY(my);
        assertTrue(view.zoomBy(1, mx, my));
        // Mid-animation the point stays put too.
        view.tick(0.016D);
        assertEquals(wx, view.worldX(mx), 1e-6);
        assertEquals(wy, view.worldY(my), 1e-6);
        for (int frame = 0; frame < 200; frame++) {
            view.tick(0.016D);
        }
        assertEquals(1.25D, view.zoom(), 1e-9);
        assertEquals(wx, view.worldX(mx), 1e-6);
        assertEquals(wy, view.worldY(my), 1e-6);
        assertTrue(view.zoomBy(-1, mx, my));
        assertTrue(view.zoomBy(-1, mx, my));
        view.snap();
        assertEquals(0.75D, view.zoom(), 1e-9);
        assertEquals(wx, view.worldX(mx), 1e-6);
    }

    @Test
    void zoomStopsAtBothEnds() {
        GraphView view = view(0, 0, 100, 100);
        for (int step = 0; step < 10; step++) {
            view.zoomBy(1, 50, 50);
        }
        assertEquals(1.5D, view.targetZoom(), 1e-9);
        assertFalse(view.zoomBy(1, 50, 50));
        for (int step = 0; step < 10; step++) {
            view.zoomBy(-1, 50, 50);
        }
        assertEquals(0.5D, view.targetZoom(), 1e-9);
        assertFalse(view.zoomBy(-1, 50, 50));
    }

    @Test
    void easingConvergesOnTheTarget() {
        GraphView view = view(0, 0, 300, 300);
        view.setBounds(0, 0, 3000, 3000, 0);
        view.fit(true);
        view.panBy(-500, -300, false);
        assertTrue(view.tick(0.016D), "still moving after one frame");
        for (int frame = 0; frame < 300 && view.tick(0.016D); frame++) {
            // ease
        }
        assertEquals(view.targetX(), view.cameraX(), 1e-9);
        assertEquals(view.targetY(), view.cameraY(), 1e-9);
    }

    @Test
    void overflowFlagsEachSide() {
        assertEquals(0, GraphView.overflow(10, 10, 90, 90, 0, 0, 100, 100));
        assertEquals(GraphView.LEFT, GraphView.overflow(-5, 10, 90, 90, 0, 0, 100, 100));
        assertEquals(GraphView.RIGHT | GraphView.DOWN, GraphView.overflow(10, 10, 140, 120, 0, 0, 100, 100));
        assertEquals(GraphView.UP, GraphView.overflow(10, -40, 90, 90, 0, 0, 100, 100));
        assertEquals(0, GraphView.overflow(-0.5, 0, 100.5, 100, 0, 0, 100, 100), "sub-pixel tolerance");
        GraphView view = view(0, 0, 200, 200);
        view.setBounds(0, 0, 1000, 50, 0);
        view.fit(true);
        view.zoomBy(1, 0, 100);
        view.zoomBy(1, 0, 100);
        view.snap();
        view.panBy(100_000, 0, true);
        assertEquals(GraphView.RIGHT, view.overflow(), "at the left end only the right side overflows");
    }

    @Test
    void theDrawerInsetKeepsNodesReachable() {
        GraphView view = view(0, 0, 400, 200);
        view.setBounds(0, 0, 360, 40, 0);
        view.fit(true);
        view.setInsetRight(200);
        // The node at the far right can be brought into the uncovered left half.
        view.ensureVisible(334, 0, 26, 26, 8);
        view.snap();
        assertTrue(view.screenX(360) <= 200 - 8 + 1e-6, "right edge clear of the drawer: " + view.screenX(360));
    }
}
