package za.co.neroland.neroquests.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroquests.client.screen.BookLayout.Layout;
import za.co.neroland.neroquests.client.screen.BookLayout.Rect;

/** E6: the book (view and edit) fits an 854&times;480 window at GUI scales 1&ndash;4, and view mode is unchanged. */
class BookLayoutTest {

    private static final int WINDOW_W = 854;
    private static final int WINDOW_H = 480;

    @Test
    void scaledSizeRoundsUpLikeTheGame() {
        assertEquals(854, BookLayout.scaled(854, 1));
        assertEquals(427, BookLayout.scaled(854, 2));
        assertEquals(285, BookLayout.scaled(854, 3));
        assertEquals(214, BookLayout.scaled(854, 4));
        assertEquals(120, BookLayout.scaled(480, 4));
    }

    @Test
    void everythingFitsAtEveryScale() {
        for (int scale = 1; scale <= 4; scale++) {
            int w = BookLayout.scaled(WINDOW_W, scale);
            int h = BookLayout.scaled(WINDOW_H, scale);
            Rect screen = new Rect(0, 0, w, h);
            for (boolean edit : new boolean[] {false, true}) {
                String at = "scale " + scale + (edit ? " edit" : " view");
                Layout layout = BookLayout.compute(w, h, edit);
                assertTrue(layout.panel().within(screen), at + ": panel on screen");
                for (Rect part : new Rect[] {layout.header(), layout.toggle(), layout.sidebar(), layout.content()}) {
                    assertTrue(part.within(layout.panel()), at + ": " + part + " inside panel");
                }
                assertFalse(layout.sidebar().overlaps(layout.content()), at + ": sidebar clear of content");
                assertFalse(layout.toggle().overlaps(layout.content()), at + ": toggle clear of content");
                assertFalse(layout.toggle().overlaps(layout.sidebar()), at + ": toggle clear of sidebar");
                assertTrue(layout.sidebar().w() >= BookLayout.COMPACT_TAB_WIDTH, at + ": sidebar width");
                assertTrue(layout.tabRows() >= 3, at + ": at least three chapter rows");
                assertTrue(layout.content().w() >= 150, at + ": content width " + layout.content().w());
                assertTrue(layout.canvas().within(layout.content()), at + ": canvas inside content");
                assertTrue(layout.canvas().h() >= 60, at + ": canvas height " + layout.canvas().h());
                assertTrue(layout.tabs().within(layout.content()) && layout.form().within(layout.content()),
                        at + ": editor inside content");
                assertFalse(layout.tabs().overlaps(layout.form()), at + ": tabs clear of form");
                assertTrue(layout.form().h() >= 60, at + ": form height " + layout.form().h());
                assertTrue(layout.editorTabWidth() >= BookLayout.TOOL_BUTTON, at + ": editor tab width");
                assertTrue(layout.modal().within(layout.content()), at + ": modal inside content");
                assertTrue(layout.modal().h() >= 2 * BookLayout.ROW + 20, at + ": modal fits a field and buttons");
                if (edit) {
                    assertTrue(layout.toolbar().within(layout.content()), at + ": toolbar inside content");
                    assertFalse(layout.toolbar().overlaps(layout.canvas()), at + ": toolbar clear of canvas");
                    assertTrue(layout.toolbar().w() >= BookLayout.TOOLBAR_MIN_WIDTH, at + ": toolbar buttons fit");
                }
            }
        }
    }

    @Test
    void viewModeKeepsTheOriginalBook() {
        Layout layout = BookLayout.compute(854, 480, false);
        int left = (854 - 400) / 2;
        int top = (480 - 220) / 2;
        assertEquals(new Rect(left, top, 400, 220), layout.panel());
        assertEquals(new Rect(left + 8, top + 22, 96, 190), layout.sidebar());
        // The old screen: content from left + 96 + 14 to left + 400 - 8, top + 22 to top + 220 - 8.
        assertEquals(new Rect(left + 110, top + 22, 282, 190), layout.content());
        assertEquals(layout.content(), layout.canvas());
        assertEquals(9, layout.tabRows());
        assertFalse(layout.compact());
    }

    @Test
    void smallScreensUseTheCompactBook() {
        Layout layout = BookLayout.compute(214, 120, true);
        assertTrue(layout.compact());
        assertEquals(BookLayout.COMPACT_TAB_WIDTH, layout.sidebar().w());
        assertEquals(206, layout.panel().w());
        assertEquals(112, layout.panel().h());
    }
}
