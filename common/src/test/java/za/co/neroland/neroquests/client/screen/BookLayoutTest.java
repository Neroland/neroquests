package za.co.neroland.neroquests.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroquests.client.screen.BookLayout.Layout;
import za.co.neroland.neroquests.client.screen.BookLayout.Rect;

/** E6: the book (view and edit) fits 854&times;480 at GUI scales 1&ndash;4 and large windows, without overlaps. */
class BookLayoutTest {

    private static final int[][] WINDOWS = {{854, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};

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
        for (int[] window : WINDOWS) {
            for (int scale = 1; scale <= 6; scale++) {
                int w = BookLayout.scaled(window[0], scale);
                int h = BookLayout.scaled(window[1], scale);
                if (w < 200 || h < 110) {
                    continue; // the game itself refuses GUI scales this large for the window
                }
                for (boolean edit : new boolean[] {false, true}) {
                    check(window[0] + "x" + window[1] + " scale " + scale + (edit ? " edit" : " view"),
                            BookLayout.compute(w, h, edit), new Rect(0, 0, w, h), edit);
                }
            }
        }
    }

    private static void check(String at, Layout layout, Rect screen, boolean edit) {
        assertTrue(layout.panel().within(screen), at + ": panel on screen");
        for (Rect part : new Rect[] {layout.header(), layout.toggle(), layout.sidebar(), layout.content()}) {
            assertTrue(part.within(layout.panel()), at + ": " + part + " inside panel");
        }
        assertTrue(layout.toggle().within(layout.header()), at + ": toggle inside header");
        assertFalse(layout.header().overlaps(layout.sidebar()), at + ": header clear of sidebar");
        assertFalse(layout.header().overlaps(layout.content()), at + ": header clear of content");
        assertTrue(layout.toggle().x() - layout.panel().x() - layout.pad() >= 100,
                at + ": header room for the chapter title");
        assertFalse(layout.sidebar().overlaps(layout.content()), at + ": sidebar clear of content");
        assertTrue(layout.sidebar().w() >= BookLayout.COMPACT_TAB_WIDTH, at + ": sidebar width");
        assertTrue(layout.tabRows() >= 3, at + ": at least three chapter rows");
        assertTrue(layout.tabList().within(layout.sidebar()), at + ": rows inside the column");
        assertTrue(layout.tabRows() * layout.tabHeight() <= layout.tabList().h(), at + ": rows fit the list");
        if (layout.iconOnly()) {
            assertEquals(Rect.EMPTY, layout.hint(), at + ": no hint in the icon column");
        } else {
            assertTrue(layout.hint().within(layout.sidebar()), at + ": hint inside the column");
            assertFalse(layout.hint().overlaps(layout.tabList()), at + ": hint clear of the rows");
            assertTrue(layout.hint().h() >= 9, at + ": hint fits a line");
        }
        assertTrue(layout.content().w() >= 150, at + ": content width " + layout.content().w());
        assertTrue(layout.canvas().within(layout.content()), at + ": canvas inside content");
        assertTrue(layout.canvas().h() >= 60, at + ": canvas height " + layout.canvas().h());
        assertTrue(layout.drawer().within(layout.canvas()), at + ": drawer inside canvas");
        assertTrue(layout.drawer().w() >= Math.min(150, layout.canvas().w()), at + ": drawer width");
        assertEquals(layout.canvas().right(), layout.drawer().right(), at + ": drawer on the right");
        assertTrue(layout.zoomBar().within(layout.canvas()), at + ": zoom buttons inside canvas");
        for (int index = 0; index < 3; index++) {
            assertTrue(layout.zoomButton(index).within(layout.zoomBar()), at + ": zoom button " + index);
        }
        assertTrue(layout.tabs().within(layout.content()) && layout.form().within(layout.content()),
                at + ": editor inside content");
        assertFalse(layout.tabs().overlaps(layout.form()), at + ": tabs clear of form");
        assertTrue(layout.form().h() >= 60, at + ": form height " + layout.form().h());
        assertTrue(layout.editorTabWidth() >= BookLayout.TOOL_BUTTON, at + ": editor tab width");
        assertTrue(layout.modal().within(layout.content()), at + ": modal inside content");
        assertTrue(layout.modal().h() >= 2 * BookLayout.ROW + 20, at + ": modal fits a field and buttons");
        for (int row = 0; row < layout.tabRows(); row++) {
            assertTrue(layout.tabRow(row).within(layout.tabList()), at + ": row " + row);
        }
        if (edit) {
            assertTrue(layout.toolbar().within(layout.content()), at + ": toolbar inside content");
            assertFalse(layout.toolbar().overlaps(layout.canvas()), at + ": toolbar clear of canvas");
            assertTrue(layout.toolbar().w() >= BookLayout.TOOLBAR_MIN_WIDTH, at + ": toolbar buttons fit");
        } else {
            assertEquals(layout.content(), layout.canvas(), at + ": view canvas is the content");
        }
    }

    @Test
    void largeScreensGetTheFullBook() {
        Layout layout = BookLayout.compute(854, 480, false);
        assertEquals(new Rect((854 - BookLayout.MAX_WIDTH) / 2, (480 - BookLayout.MAX_HEIGHT) / 2,
                BookLayout.MAX_WIDTH, BookLayout.MAX_HEIGHT), layout.panel());
        assertFalse(layout.compact());
        assertFalse(layout.iconOnly());
        assertTrue(layout.drawer().w() < layout.canvas().w(), "the drawer leaves part of the graph visible");
        assertEquals(BookLayout.compute(854, 480, true).panel(), layout.panel(), "toggling edit mode never jumps");
    }

    @Test
    void smallScreensUseTheCompactBook() {
        Layout layout = BookLayout.compute(214, 120, true);
        assertTrue(layout.compact());
        assertTrue(layout.iconOnly());
        assertEquals(BookLayout.COMPACT_TAB_WIDTH, layout.sidebar().w());
        assertEquals(206, layout.panel().w());
        assertEquals(112, layout.panel().h());
        Layout view = BookLayout.compute(214, 120, false);
        assertEquals(view.canvas().w(), view.drawer().w(), "compact drawer takes the whole canvas");
        Layout mid = BookLayout.compute(427, 240, false);
        assertFalse(mid.compact(), "GUI scale 2 at 854x480 is the full arrangement");
    }
}
