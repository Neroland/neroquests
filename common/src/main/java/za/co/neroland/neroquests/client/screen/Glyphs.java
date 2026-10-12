package za.co.neroland.neroquests.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Tiny pixel glyphs drawn from filled rectangles (E2), so the book stays texture-free: padlock, pencil,
 * warning mark, check mark, close cross, chevrons, zoom symbols and a small "pack" cube. Each is drawn with its
 * top-left corner at ({@code x}, {@code y}); sizes are noted per glyph.
 */
final class Glyphs {

    private Glyphs() {
    }

    /** A padlock (5&times;7): a 3-wide shackle over a 5&times;4 body. */
    static void lock(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        graphics.fill(x + 1, y, x + 4, y + 1, colour);
        graphics.fill(x + 1, y, x + 2, y + 3, colour);
        graphics.fill(x + 3, y, x + 4, y + 3, colour);
        graphics.fill(x, y + 3, x + 5, y + 7, colour);
        graphics.fill(x + 2, y + 4, x + 3, y + 6, BookPaint.INK);
    }

    /** A pencil (6&times;7): a diagonal shaft with a light tip at the lower left. */
    static void pencil(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        for (int step = 0; step < 5; step++) {
            graphics.fill(x + 4 - step, y + step, x + 6 - step, y + step + 2, colour);
        }
        graphics.fill(x, y + 5, x + 1, y + 7, BookPaint.TITLE);
    }

    /** An exclamation mark (4&times;7). */
    static void warning(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        graphics.fill(x + 1, y, x + 3, y + 4, colour);
        graphics.fill(x + 1, y + 5, x + 3, y + 7, colour);
    }

    /** A check mark (7&times;5). */
    static void check(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        graphics.fill(x, y + 2, x + 1, y + 3, colour);
        graphics.fill(x + 1, y + 3, x + 2, y + 4, colour);
        graphics.fill(x + 2, y + 4, x + 3, y + 5, colour);
        graphics.fill(x + 3, y + 3, x + 4, y + 4, colour);
        graphics.fill(x + 4, y + 2, x + 5, y + 3, colour);
        graphics.fill(x + 5, y + 1, x + 6, y + 2, colour);
        graphics.fill(x + 6, y, x + 7, y + 1, colour);
    }

    /** A close cross (7&times;7). */
    static void cross(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        for (int step = 0; step < 7; step++) {
            graphics.fill(x + step, y + step, x + step + 1, y + step + 1, colour);
            graphics.fill(x + 6 - step, y + step, x + 7 - step, y + step + 1, colour);
        }
    }

    /** A chevron (4&times;7 pointing left/right, 7&times;4 pointing up/down); side as in BookPaint.edgeGlow. */
    static void chevron(GuiGraphicsExtractor graphics, int x, int y, int side, int colour) {
        for (int step = 0; step < 4; step++) {
            switch (side) {
                case 0 -> {
                    graphics.fill(x + step, y + 3 - step, x + step + 1, y + 4 - step, colour);
                    graphics.fill(x + step, y + 3 + step, x + step + 1, y + 4 + step, colour);
                }
                case 1 -> {
                    graphics.fill(x + 3 - step, y + 3 - step, x + 4 - step, y + 4 - step, colour);
                    graphics.fill(x + 3 - step, y + 3 + step, x + 4 - step, y + 4 + step, colour);
                }
                case 2 -> {
                    graphics.fill(x + 3 - step, y + step, x + 4 - step, y + step + 1, colour);
                    graphics.fill(x + 3 + step, y + step, x + 4 + step, y + step + 1, colour);
                }
                default -> {
                    graphics.fill(x + 3 - step, y + 3 - step, x + 4 - step, y + 4 - step, colour);
                    graphics.fill(x + 3 + step, y + 3 - step, x + 4 + step, y + 4 - step, colour);
                }
            }
        }
    }

    /** A minus (5&times;5). */
    static void minus(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        graphics.fill(x, y + 2, x + 5, y + 3, colour);
    }

    /** A plus (5&times;5). */
    static void plus(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        graphics.fill(x, y + 2, x + 5, y + 3, colour);
        graphics.fill(x + 2, y, x + 3, y + 5, colour);
    }

    /** "Fit": four corner brackets (7&times;7). */
    static void fit(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        BookPaint.corners(graphics, x, y, 7, 7, 3, colour);
    }

    /** A small cube for "provided by a mod pack" (5&times;5). */
    static void cube(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        graphics.fill(x + 1, y, x + 5, y + 1, colour);
        graphics.fill(x, y + 1, x + 1, y + 5, colour);
        graphics.fill(x, y + 1, x + 4, y + 2, colour);
        graphics.fill(x + 4, y + 1, x + 5, y + 4, colour);
        graphics.fill(x + 3, y + 1, x + 4, y + 5, colour);
        graphics.fill(x, y + 4, x + 4, y + 5, colour);
    }

    /** A 3&times;3 diamond bullet. */
    static void diamond(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        graphics.fill(x + 1, y, x + 2, y + 3, colour);
        graphics.fill(x, y + 1, x + 3, y + 2, colour);
    }

    /** A question mark (5&times;7), for undiscovered prerequisites. */
    static void question(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        graphics.fill(x + 1, y, x + 4, y + 1, colour);
        graphics.fill(x, y + 1, x + 1, y + 2, colour);
        graphics.fill(x + 4, y + 1, x + 5, y + 3, colour);
        graphics.fill(x + 2, y + 3, x + 4, y + 4, colour);
        graphics.fill(x + 2, y + 4, x + 3, y + 5, colour);
        graphics.fill(x + 2, y + 6, x + 3, y + 7, colour);
    }
}
