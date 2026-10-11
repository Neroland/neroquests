package za.co.neroland.neroquests.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Tiny badges drawn from filled rectangles (E2), so the book stays texture-free: a padlock for chapters
 * that cannot be edited, a pencil for custom chapters and an exclamation mark for "mod not installed".
 * Each fits a 6&times;7 box whose top-left corner is ({@code x}, {@code y}).
 */
final class Glyphs {

    private Glyphs() {
    }

    /** A padlock: a 3-wide shackle over a 5&times;4 body. */
    static void lock(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        graphics.fill(x + 1, y, x + 4, y + 1, colour);
        graphics.fill(x + 1, y, x + 2, y + 3, colour);
        graphics.fill(x + 3, y, x + 4, y + 3, colour);
        graphics.fill(x, y + 3, x + 5, y + 7, colour);
        graphics.fill(x + 2, y + 4, x + 3, y + 6, 0xFF05080D);
    }

    /** A pencil: a diagonal shaft with a darker tip at the lower left. */
    static void pencil(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        for (int step = 0; step < 5; step++) {
            graphics.fill(x + 4 - step, y + step, x + 6 - step, y + step + 2, colour);
        }
        graphics.fill(x, y + 5, x + 1, y + 7, 0xFFD6ECFF);
    }

    /** An exclamation mark. */
    static void warning(GuiGraphicsExtractor graphics, int x, int y, int colour) {
        graphics.fill(x + 1, y, x + 3, y + 4, colour);
        graphics.fill(x + 1, y + 5, x + 3, y + 7, colour);
    }
}
