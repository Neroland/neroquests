package za.co.neroland.neroquests.client.screen;

/**
 * Pure layout math for the quest book (E6): every rectangle the screen draws or hit-tests, computed from the
 * GUI-scaled screen size alone, so it can be unit-tested for every GUI scale without a game client.
 *
 * <p>View mode keeps the original 400&times;220 book whenever it fits (a screen of at least 408&times;228
 * scaled pixels gets exactly the old geometry, so the book is unchanged for everyone who does not edit); edit
 * mode grows to 520&times;300 when there is room. On a smaller screen both shrink to the screen minus a 4 px
 * margin and switch to a <em>compact</em> arrangement: a thinner header, narrower padding and an icon-only
 * chapter column (titles move to tooltips).
 *
 * <p>Edit mode splits the content area into a one-row toolbar plus the canvas, and the quest editor into a
 * one-row tab strip plus the form. Overlays (prompts, the item picker, the first-run flow) use the content
 * area or a box centred in it.
 */
public final class BookLayout {

    /** The view-mode book (unchanged since 0.1). */
    public static final int VIEW_WIDTH = 400;
    public static final int VIEW_HEIGHT = 220;
    /** The editor's preferred size. */
    public static final int EDIT_WIDTH = 520;
    public static final int EDIT_HEIGHT = 300;
    /** Space kept free around the panel on a small screen. */
    public static final int MARGIN = 4;

    /** Chapter column widths: with titles, and icon-only. */
    public static final int TAB_WIDTH = 96;
    public static final int COMPACT_TAB_WIDTH = 22;
    /** One chapter row. */
    public static final int TAB_HEIGHT = 20;
    /** Toolbar / tab-strip / text-field height. */
    public static final int ROW = 14;
    /** Gap between a strip and what is under it. */
    public static final int STRIP_GAP = 2;
    /** Width of the edit-mode toggle in the header. */
    public static final int TOGGLE_WIDTH = 44;
    public static final int COMPACT_TOGGLE_WIDTH = 36;

    /** The edit toolbar: five square chapter buttons and the "+ Quest" button. */
    public static final int TOOL_BUTTON = 16;
    public static final int TOOL_GAP = 2;
    public static final int ADD_QUEST_WIDTH = 52;
    public static final int TOOLBAR_MIN_WIDTH = 5 * (TOOL_BUTTON + TOOL_GAP) + ADD_QUEST_WIDTH;

    /** The quest-editor strip: four tabs and a back button. */
    public static final int EDITOR_TABS = 4;
    public static final int BACK_WIDTH = 20;

    /** A screen-space rectangle (x, y, width, height). */
    public record Rect(int x, int y, int w, int h) {

        public static final Rect EMPTY = new Rect(0, 0, 0, 0);

        public int right() {
            return x + w;
        }

        public int bottom() {
            return y + h;
        }

        public boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }

        /** Whether this rectangle lies entirely within {@code outer}. */
        public boolean within(Rect outer) {
            return x >= outer.x && y >= outer.y && right() <= outer.right() && bottom() <= outer.bottom();
        }

        /** Whether the two rectangles share any pixel. */
        public boolean overlaps(Rect other) {
            return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
        }

        /** A box of at most {@code width}&times;{@code height}, centred in this one. */
        public Rect centred(int width, int height) {
            int cw = Math.min(width, w);
            int ch = Math.min(height, h);
            return new Rect(x + (w - cw) / 2, y + (h - ch) / 2, cw, ch);
        }
    }

    /**
     * Every region of the book.
     *
     * @param panel    the whole book
     * @param header   the title strip
     * @param toggle   the edit-mode toggle (only shown to authors)
     * @param sidebar  the chapter column
     * @param content  everything right of the chapter column
     * @param toolbar  edit mode: the toolbar row (empty in view mode)
     * @param canvas   the quest graph (= content in view mode)
     * @param tabs     the quest editor's tab strip
     * @param form     the quest editor's form area
     * @param compact  the small-screen arrangement is in use
     * @param tabRows  how many chapter rows fit
     */
    public record Layout(Rect panel, Rect header, Rect toggle, Rect sidebar, Rect content, Rect toolbar, Rect canvas,
                         Rect tabs, Rect form, boolean compact, int tabRows) {

        /** Text left edge inside the panel. */
        public int pad() {
            return compact ? 4 : 8;
        }

        /** Width of one quest-editor tab. */
        public int editorTabWidth() {
            return Math.max(0, (tabs.w() - BACK_WIDTH - EDITOR_TABS * TOOL_GAP) / EDITOR_TABS);
        }

        /** A modal box (prompts, confirmations) centred on the content area. */
        public Rect modal() {
            return content.centred(220, 70);
        }
    }

    private BookLayout() {
    }

    /** The GUI-scaled size of a window dimension at {@code scale}, rounded up the way the game does. */
    public static int scaled(int windowPixels, int scale) {
        int whole = windowPixels / scale;
        return windowPixels % scale == 0 ? whole : whole + 1;
    }

    /** The book for a GUI-scaled screen of {@code screenW}&times;{@code screenH}. */
    public static Layout compute(int screenW, int screenH, boolean edit) {
        int panelW = Math.max(0, Math.min(edit ? EDIT_WIDTH : VIEW_WIDTH, screenW - 2 * MARGIN));
        int panelH = Math.max(0, Math.min(edit ? EDIT_HEIGHT : VIEW_HEIGHT, screenH - 2 * MARGIN));
        int left = (screenW - panelW) / 2;
        int top = (screenH - panelH) / 2;
        boolean compact = panelW < 360 || panelH < 200;
        int header = compact ? 16 : 22;
        int pad = compact ? 4 : 8;
        int gap = compact ? 4 : 6;
        int tabWidth = compact && panelW < 300 ? COMPACT_TAB_WIDTH : TAB_WIDTH;

        Rect panel = new Rect(left, top, panelW, panelH);
        Rect headerRect = new Rect(left, top, panelW, header);
        int toggleW = compact ? COMPACT_TOGGLE_WIDTH : TOGGLE_WIDTH;
        Rect toggle = new Rect(left + panelW - pad - toggleW, top + (header - ROW) / 2, toggleW, ROW);

        int bodyH = Math.max(0, panelH - header - pad);
        Rect sidebar = new Rect(left + pad, top + header, tabWidth, bodyH);
        int contentX = sidebar.right() + gap;
        Rect content = new Rect(contentX, top + header, Math.max(0, left + panelW - pad - contentX), bodyH);

        Rect toolbar = Rect.EMPTY;
        Rect canvas = content;
        if (edit) {
            toolbar = new Rect(content.x(), content.y(), content.w(), ROW);
            canvas = new Rect(content.x(), content.y() + ROW + STRIP_GAP, content.w(),
                    Math.max(0, content.h() - ROW - STRIP_GAP));
        }
        Rect tabs = new Rect(content.x(), content.y(), content.w(), ROW);
        Rect form = new Rect(content.x(), content.y() + ROW + STRIP_GAP, content.w(),
                Math.max(0, content.h() - ROW - STRIP_GAP));
        int tabRows = Math.max(1, sidebar.h() / TAB_HEIGHT);
        return new Layout(panel, headerRect, toggle, sidebar, content, toolbar, canvas, tabs, form, compact, tabRows);
    }
}
