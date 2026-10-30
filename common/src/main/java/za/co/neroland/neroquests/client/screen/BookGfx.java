package za.co.neroland.neroquests.client.screen;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * The few book drawing calls whose shape differs between Minecraft lines, in one place: the 2D pose stack,
 * draw strata and deferred tooltips. Since 1.21.6 the pose is a 2D matrix stack, strata order the layers and
 * tooltips are queued on the graphics object; before it the pose is a 3D {@code PoseStack}, later layers
 * are lifted on the z axis, and tooltips are queued on the screen.
 */
final class BookGfx {

    private BookGfx() {
    }

    static void push(GuiGraphicsExtractor graphics) {
        //? if >=1.21.6 {
        graphics.pose().pushMatrix();
        //?} else {
        /*graphics.pose().pushPose();
        *///?}
    }

    static void pop(GuiGraphicsExtractor graphics) {
        //? if >=1.21.6 {
        graphics.pose().popMatrix();
        //?} else {
        /*graphics.pose().popPose();
        *///?}
    }

    static void translate(GuiGraphicsExtractor graphics, float x, float y) {
        //? if >=1.21.6 {
        graphics.pose().translate(x, y);
        //?} else {
        /*graphics.pose().translate(x, y, 0.0F);
        *///?}
    }

    static void scale(GuiGraphicsExtractor graphics, float x, float y) {
        //? if >=1.21.6 {
        graphics.pose().scale(x, y);
        //?} else {
        /*graphics.pose().scale(x, y, 1.0F);
        *///?}
    }

    /** A one-pixel rectangle outline. */
    static void outline(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int colour) {
        //? if >=26.1 {
        graphics.outline(x, y, width, height, colour);
        //?} else if >=1.21.9 && <1.21.11 {
        /*graphics.submitOutline(x, y, width, height, colour);
        *///?} else {
        /*graphics.renderOutline(x, y, width, height, colour);
        *///?}
    }

    /** Everything drawn after this call lies above everything drawn before it in this frame. */
    static void nextStratum(GuiGraphicsExtractor graphics) {
        //? if >=1.21.6 {
        graphics.nextStratum();
        //?} else {
        /*// Items render at z 150+ and tooltips at z 400, so lift the rest of the frame clear of both.
        graphics.flush();
        graphics.pose().translate(0.0F, 0.0F, 500.0F);
        *///?}
    }

    /** Queues a tooltip of whole lines, drawn after the screen. */
    static void componentTooltip(Screen screen, GuiGraphicsExtractor graphics, Font font, List<Component> lines,
                                 int mouseX, int mouseY) {
        //? if >=1.21.6 {
        graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        //?} else {
        /*screen.setTooltipForNextRenderPass(lines.stream().map(Component::getVisualOrderText).toList());
        *///?}
    }

    /** Queues a tooltip of pre-wrapped lines, drawn after the screen. */
    static void tooltip(Screen screen, GuiGraphicsExtractor graphics, Font font, List<FormattedCharSequence> lines,
                        int mouseX, int mouseY) {
        //? if >=1.21.6 {
        graphics.setTooltipForNextFrame(font, lines, mouseX, mouseY);
        //?} else {
        /*screen.setTooltipForNextRenderPass(lines);
        *///?}
    }

    /** Queues an item's own tooltip, drawn after the screen. */
    static void itemTooltip(Screen screen, GuiGraphicsExtractor graphics, Font font, ItemStack stack,
                            int mouseX, int mouseY) {
        //? if >=1.21.6 {
        graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
        //?} else {
        /*screen.setTooltipForNextRenderPass(Screen.getTooltipFromItem(Minecraft.getInstance(), stack).stream()
                .map(Component::getVisualOrderText).toList());
        *///?}
    }
}
