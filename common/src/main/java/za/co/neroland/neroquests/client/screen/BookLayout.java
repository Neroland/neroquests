package za.co.neroland.neroquests.client.screen;

/**
 * Pure layout math for the quest book (E6): every rectangle the screen draws or hit-tests, computed from the
 * GUI-scaled screen size alone, so it can be unit-tested for every GUI scale without a game client.
 *
 * <p>The book grows with the screen up to {@link #MAX_WIDTH}&times;{@link #MAX_HEIGHT} and keeps a
 * {@link #MARGIN} free around it; view and edit mode share the same panel so toggling never jumps. Below
 * 360&times;200 scaled pixels it switches to a <em>compact</em> arrangement: a thinner header, narrower padding,
 * a full-width detail drawer and, below 300 px wide, an icon-only chapter column (titles move to tooltips).
 *
 * <p>Regions: the header (chapter title, progress, the edit toggle), the chapter column (rows plus a hint line
 * at the bottom), and the content area. In view mode the content area is the graph canvas, with the zoom
 * buttons in its bottom-right corner and the quest drawer sliding over its right side. Edit mode splits the
 * content into a one-row toolbar plus the canvas, and the quest editor into a one-row tab strip plus the form.
 * Overlays (prompts, the item picker, the first-run flow) use the content area or a box centred in it.
 */
public final class BookLayout {

    /** The largest book. */
    public static final int MAX_WIDTH = 720;
    public static final int MAX_HEIGHT = 420;
    /** Space kept free around the panel. */
    public static final int MARGIN = 4;

    /** Chapter column widths: with titles (range), compact with titles, and icon-only. */
    public static final int TAB_WIDTH_MIN = 108;
    public static final int TAB_WIDTH_MAX = 132;
    public static final int COMPACT_TAB_WIDTH_TITLED = 96;
    public static final int COMPACT_TAB_WIDTH = 24;
    /** Chapter row heights: full (title + progress line) and compact/icon-only. */
    public static final int TAB_HEIGHT = 26;
    public static final int COMPACT_TAB_HEIGHT = 22;
    /** Toolbar / tab-strip / text-field height. */
    public static final int ROW = 14;
    /** Gap between a strip and what is under it. */
    public static final int STRIP_GAP = 2;
    /** The edit-mode toggle in the header. */
    public static final int TOGGLE_WIDTH = 52;
    public static final int TOGGLE_HEIGHT = 16;
    public static final int COMPACT_TOGGLE_WIDTH = 36;
    /** Header heights. */
    public static final int HEADER = 30;
    public static final int COMPACT_HEADER = 18;
    /** The zoom buttons (three squares). */
    public static final int ZOOM_BUTTON = 14;
    public static final int COMPACT_ZOOM_BUTTON = 12;
    /** The detail drawer's width range (non-compact). */
    public static final int DRAWER_MIN = 170;
    public static final int DRAWER_MAX = 260;

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

        /** This rectangle shrunk by {@code by} on every side (never negative). */
        public Rect inset(int by) {
            return new Rect(x + by, y + by, Math.max(0, w - 2 * by), Math.max(0, h - 2 * by));
        }
    }

    /**
     * Every region of the book.
     *
     * @param panel     the whole book
     * @param header    the title strip
     * @param toggle    the edit-mode toggle (only shown to authors)
     * @param sidebar   the chapter column (rows + hint)
     * @param tabList   the part of the chapter column that holds rows
     * @param hint      the muted hint under the rows (empty when icon-only)
     * @param content   everything right of the chapter column
     * @param toolbar   edit mode: the toolbar row (empty in view mode)
     * @param canvas    the quest graph (= content in view mode)
     * @param drawer    the quest detail drawer, at rest (slides in over the canvas's right side)
     * @param zoomBar   the zoom-out / zoom-in / fit buttons, bottom-right of the canvas
     * @param tabs      the quest editor's tab strip
     * @param form      the quest editor's form area
     * @param compact   the small-screen arrangement is in use
     * @param iconOnly  the chapter column shows icons only
     * @param tabRows   how many chapter rows fit
     * @param tabHeight one chapter row's height
     */
    public record Layout(Rect panel, Rect header, Rect toggle, Rect sidebar, Rect tabList, Rect hint, Rect content,
                         Rect toolbar, Rect canvas, Rect drawer, Rect zoomBar, Rect tabs, Rect form, boolean compact,
                         boolean iconOnly, int tabRows, int tabHeight) {

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

        /** One zoom button's side. */
        public int zoomButton() {
            return zoomBar.h();
        }

        /** Zoom button {@code index} (0 = out, 1 = in, 2 = fit). */
        public Rect zoomButton(int index) {
            int side = zoomBar.h();
            return new Rect(zoomBar.x() + index * (side + 2), zoomBar.y(), side, side);
        }

        /** Chapter row {@code row} (0 = first visible row). */
        public Rect tabRow(int row) {
            return new Rect(tabList.x(), tabList.y() + row * tabHeight, tabList.w(), tabHeight - 2);
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
        int panelW = Math.max(0, Math.min(MAX_WIDTH, screenW - 2 * MARGIN));
        int panelH = Math.max(0, Math.min(MAX_HEIGHT, screenH - 2 * MARGIN));
        int left = (screenW - panelW) / 2;
        int top = (screenH - panelH) / 2;
        boolean compact = panelW < 360 || panelH < 200;
        boolean iconOnly = compact && panelW < 300;
        int header = compact ? COMPACT_HEADER : HEADER;
        int pad = compact ? 4 : 8;
        int gap = compact ? 4 : 6;
        int tabWidth = iconOnly ? COMPACT_TAB_WIDTH
                : compact ? COMPACT_TAB_WIDTH_TITLED
                : Math.max(TAB_WIDTH_MIN, Math.min(TAB_WIDTH_MAX, panelW / 5));
        int tabHeight = compact ? COMPACT_TAB_HEIGHT : TAB_HEIGHT;

        Rect panel = new Rect(left, top, panelW, panelH);
        Rect headerRect = new Rect(left, top, panelW, header);
        int toggleW = compact ? COMPACT_TOGGLE_WIDTH : TOGGLE_WIDTH;
        int toggleH = compact ? ROW : TOGGLE_HEIGHT;
        Rect toggle = new Rect(left + panelW - pad - toggleW, top + (header - toggleH) / 2, toggleW, toggleH);

        int bodyTop = top + header + (compact ? 2 : 4);
        int bodyH = Math.max(0, top + panelH - pad - bodyTop);
        Rect sidebar = new Rect(left + pad, bodyTop, tabWidth, bodyH);
        int hintH = iconOnly ? 0 : compact ? 10 : 20;
        Rect hint = hintH == 0 ? Rect.EMPTY
                : new Rect(sidebar.x(), sidebar.bottom() - hintH, sidebar.w(), hintH);
        Rect tabList = new Rect(sidebar.x(), sidebar.y(), sidebar.w(),
                Math.max(0, sidebar.h() - (hintH == 0 ? 0 : hintH + 4)));
        int contentX = sidebar.right() + gap;
        Rect content = new Rect(contentX, bodyTop, Math.max(0, left + panelW - pad - contentX), bodyH);

        Rect toolbar = Rect.EMPTY;
        Rect canvas = content;
        if (edit) {
            toolbar = new Rect(content.x(), content.y(), content.w(), ROW);
            canvas = new Rect(content.x(), content.y() + ROW + STRIP_GAP, content.w(),
                    Math.max(0, content.h() - ROW - STRIP_GAP));
        }
        int drawerW = compact ? canvas.w()
                : Math.min(canvas.w(), Math.max(DRAWER_MIN, Math.min(DRAWER_MAX, canvas.w() * 9 / 20)));
        Rect drawer = new Rect(canvas.right() - drawerW, canvas.y(), drawerW, canvas.h());
        int zoom = compact ? COMPACT_ZOOM_BUTTON : ZOOM_BUTTON;
        int zoomW = 3 * zoom + 4;
        Rect zoomBar = new Rect(canvas.right() - 4 - zoomW, canvas.bottom() - 4 - zoom, zoomW, zoom);

        Rect tabs = new Rect(content.x(), content.y(), content.w(), ROW);
        Rect form = new Rect(content.x(), content.y() + ROW + STRIP_GAP, content.w(),
                Math.max(0, content.h() - ROW - STRIP_GAP));
        int tabRows = Math.max(1, tabList.h() / tabHeight);
        return new Layout(panel, headerRect, toggle, sidebar, tabList, hint, content, toolbar, canvas, drawer, zoomBar,
                tabs, form, compact, iconOnly, tabRows, tabHeight);
    }
}
