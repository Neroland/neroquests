package za.co.neroland.neroquests.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The quest book's visual language, drawn from filled rectangles only (no GUI textures): the Neroland palette
 * (deep navy, cyan and violet accents) and the shared shapes &mdash; bevelled frames, corner ticks, progress
 * bars, chips and soft edge glows. Every screen part (graph, sidebar, drawer, editor panels, buttons) uses
 * these so the book reads as one piece.
 *
 * <p>Pixel rules: every shape lands on whole GUI pixels, alpha is only used for glows and dimmers, and colours
 * stay in the navy / cyan / violet / green / amber family (no magenta).
 */
final class BookPaint {

    // --- palette -------------------------------------------------------------------------------------------
    static final int INK = 0xFF04070D;
    static final int VOID_TOP = 0xFF0A1122;
    static final int VOID_BOTTOM = 0xFF050913;
    static final int PANEL = 0xFF0D1322;
    static final int PANEL_HI = 0xFF141D31;
    static final int PANEL_EDGE = 0xFF26344F;
    static final int EDGE_HI = 0xFF3B5072;
    static final int TROUGH = 0xFF090E19;
    static final int TITLE = 0xFFE6F2FF;
    static final int SUBTLE = 0xFF93A4BF;
    static final int MUTED = 0xFF5B6982;
    static final int CYAN = 0xFF52D9F2;
    static final int VIOLET = 0xFFA083FF;
    static final int GREEN = 0xFF5BD69A;
    static final int AMBER = 0xFFFFB74D;
    static final int LOCKED = 0xFF6E7B93;
    static final int LINE_IDLE = 0xFF2F3B52;
    static final int WARN = 0xFFFF8A5C;
    static final int DANGER = 0xFFFF5A5A;

    private BookPaint() {
    }

    /** {@code colour} with its alpha replaced by {@code alpha} (0..255). */
    static int alpha(int colour, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (colour & 0x00FFFFFF);
    }

    /** Linear mix of two opaque colours ({@code t} = 0 gives {@code a}). */
    static int mix(int a, int b, float t) {
        float k = Mth.clamp(t, 0.0F, 1.0F);
        int r = Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * A bevelled box: a 1 px {@code edge} outline, a 1 px lighter rim along the top and left, a darker rim along
     * the bottom and right, and a vertical gradient body.
     */
    static void bevel(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int edge, int top, int bottom) {
        if (w <= 2 || h <= 2) {
            graphics.fill(x, y, x + w, y + h, edge);
            return;
        }
        graphics.fill(x, y, x + w, y + h, edge);
        graphics.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, top, bottom);
        graphics.fill(x + 1, y + 1, x + w - 1, y + 2, alpha(0xFFFFFF, 0x1C));
        graphics.fill(x + 1, y + 2, x + 2, y + h - 1, alpha(0xFFFFFF, 0x10));
        graphics.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, alpha(0x000000, 0x40));
    }

    /** The standard panel frame (editor panels, modals, the old {@code frame}). */
    static void frame(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, PANEL_EDGE);
        graphics.fillGradient(x, y, x + w, y + h, 0xFF0B1120, TROUGH);
        graphics.fill(x, y, x + w, y + 1, alpha(0xFFFFFF, 0x12));
    }

    /** Sci-fi corner ticks: an L of {@code len} px in each corner of the box. */
    static void corners(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int len, int colour) {
        int l = Math.min(len, Math.min(w, h) / 2);
        graphics.fill(x, y, x + l, y + 1, colour);
        graphics.fill(x, y, x + 1, y + l, colour);
        graphics.fill(x + w - l, y, x + w, y + 1, colour);
        graphics.fill(x + w - 1, y, x + w, y + l, colour);
        graphics.fill(x, y + h - 1, x + l, y + h, colour);
        graphics.fill(x, y + h - l, x + 1, y + h, colour);
        graphics.fill(x + w - l, y + h - 1, x + w, y + h, colour);
        graphics.fill(x + w - 1, y + h - l, x + w, y + h, colour);
    }

    /** A thin progress bar: dark trough, coloured fill with a 1 px highlight on top. */
    static void bar(GuiGraphicsExtractor graphics, int x, int y, int w, int h, float fraction, int colour) {
        if (w <= 0 || h <= 0) {
            return;
        }
        graphics.fill(x, y, x + w, y + h, alpha(0x000000, 0x90));
        graphics.fill(x, y, x + w, y + h, alpha(colour, 0x22));
        int filled = Mth.clamp(Math.round(w * fraction), 0, w);
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + h, colour);
            if (h >= 3) {
                graphics.fill(x, y, x + filled, y + 1, mix(colour, 0xFFFFFFFF, 0.35F));
            }
        }
    }

    /** A chip: tinted fill, 1 px border and label. Returns its width. */
    static int chip(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int colour, int maxWidth) {
        int textWidth = Math.min(font.width(text), Math.max(0, maxWidth - 8));
        int w = textWidth + 8;
        int h = 11;
        graphics.fill(x, y, x + w, y + h, alpha(colour, 0x24));
        graphics.fill(x, y, x + w, y + 1, alpha(colour, 0x90));
        graphics.fill(x, y + h - 1, x + w, y + h, alpha(colour, 0x90));
        graphics.fill(x, y + 1, x + 1, y + h - 1, alpha(colour, 0x90));
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, alpha(colour, 0x90));
        Component shown = font.width(text) <= textWidth ? text
                : Component.literal(font.plainSubstrByWidth(text.getString(), Math.max(0, textWidth - font.width("...")))
                        + "...");
        graphics.text(font, shown, x + 4, y + 2, colour, false);
        return w;
    }

    /**
     * A soft glow along one inside edge of a box, fading inwards over {@code depth} px:
     * side 0 = left, 1 = right, 2 = top, 3 = bottom.
     */
    static void edgeGlow(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int side, int depth, int colour,
                         int maxAlpha) {
        int steps = Math.max(1, depth / 2);
        for (int step = 0; step < steps; step++) {
            int a = maxAlpha * (steps - step) / steps;
            int c = alpha(colour, a / 2);
            int o = step * 2;
            switch (side) {
                case 0 -> graphics.fill(x + o, y, x + o + 2, y + h, c);
                case 1 -> graphics.fill(x + w - o - 2, y, x + w - o, y + h, c);
                case 2 -> graphics.fill(x, y + o, x + w, y + o + 2, c);
                default -> graphics.fill(x, y + h - o - 2, x + w, y + h - o, c);
            }
        }
    }

    /** A soft drop shadow just outside a box's left edge (the drawer). */
    static void shadowLeft(GuiGraphicsExtractor graphics, int x, int y, int h, int depth) {
        for (int step = 0; step < depth; step++) {
            graphics.fill(x - step - 1, y, x - step, y + h, alpha(0x000000, 0x70 * (depth - step) / depth));
        }
    }
}
