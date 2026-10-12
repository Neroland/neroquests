package za.co.neroland.neroquests.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * A vanilla {@link Button} (same focus, narration, keyboard and click behaviour) drawn in the book's frame
 * language instead of the stone sprite: a bevelled navy box whose rim lights up cyan on hover/focus and dims
 * when inactive, with a centred, ellipsised label.
 */
final class BookButton extends Button {

    private BookButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    /** A themed button with a tooltip that runs {@code action} when pressed. */
    static BookButton create(Component label, Component tooltip, int x, int y, int width, int height, Runnable action) {
        BookButton button = new BookButton(x, y, width, height, label, ignored -> action.run());
        if (tooltip != null) {
            button.setTooltip(Tooltip.create(tooltip));
        }
        return button;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean hot = this.active && isHoveredOrFocused();
        int edge = !this.active ? BookPaint.PANEL_EDGE : hot ? BookPaint.CYAN : BookPaint.EDGE_HI;
        int top = !this.active ? BookPaint.PANEL : hot ? 0xFF1B2B45 : BookPaint.PANEL_HI;
        int bottom = !this.active ? BookPaint.TROUGH : hot ? 0xFF13203A : BookPaint.PANEL;
        BookPaint.bevel(graphics, x, y, w, h, edge, top, bottom);
        if (hot) {
            graphics.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, BookPaint.alpha(BookPaint.CYAN, 0x80));
        }
        Font font = Minecraft.getInstance().font;
        Component label = getMessage();
        int room = Math.max(0, w - 6);
        Component shown = label;
        if (font.width(label) > room) {
            String cut = font.plainSubstrByWidth(label.getString(), Math.max(0, room - font.width("...")));
            shown = Component.literal(cut + "...");
        }
        int colour = !this.active ? BookPaint.MUTED : hot ? BookPaint.TITLE : 0xFFC9D8EE;
        graphics.text(font, shown, x + (w - font.width(shown) + 1) / 2, y + (h - 8) / 2, colour, false);
    }
}
