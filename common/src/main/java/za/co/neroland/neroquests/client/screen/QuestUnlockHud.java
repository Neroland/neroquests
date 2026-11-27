package za.co.neroland.neroquests.client.screen;

import java.util.List;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroquests.client.ClientQuestDefinitions;
import za.co.neroland.neroquests.client.ClientQuestUnlocks;
import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestText;

/**
 * The top-left "Quest Unlocked" notification: quest icon, the label and the quest title on a small book-styled
 * card that slides in from the left edge, holds for a few seconds and slides out, then the next queued card
 * follows ({@link ClientQuestUnlocks} decides the queue and the "+N more" collapsing).
 *
 * <p>The top-left corner is empty in vanilla (status effects sit top-right, the boss bar top-centre), so the
 * card is narrowed on small screens to stay clear of the boss bar. Nothing is drawn, and the timer is
 * paused, while the debug screen (F3) is open or the HUD is hidden (F1). Off entirely with the client config
 * key {@code questUnlockToasts}.
 *
 * <p>Registered as a HUD layer by each loader's client wiring. Render thread only.
 */
public final class QuestUnlockHud {

    private static final long SLIDE_MS = 250L;
    private static final long HOLD_MS = 3_500L;
    /** Shorter hold while more cards wait, so a queue drains quickly. */
    private static final long HOLD_QUEUED_MS = 2_200L;

    private static final int MARGIN = 4;
    private static final int HEIGHT = 26;
    private static final int TEXT_X = 26;
    private static final int MIN_WIDTH = 110;
    private static final int MAX_WIDTH = 190;
    /** Width of the vanilla boss bar, kept clear in the top centre. */
    private static final int BOSS_BAR_WIDTH = 182;

    private static ClientQuestUnlocks.Card shown;
    private static ItemStack shownIcon = ItemStack.EMPTY;
    private static long startedAt;
    private static long lastFrame;

    private QuestUnlockHud() {
    }

    /** HUD layer body. */
    public static void render(GuiGraphicsExtractor graphics) {
        long now = System.currentTimeMillis();
        long sinceLast = lastFrame == 0L ? 0L : Math.max(0L, now - lastFrame);
        lastFrame = now;
        if (!NeroQuestsConfig.QUEST_UNLOCK_TOASTS.get()) {
            ClientQuestUnlocks.clear();
            shown = null;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (hidden(mc)) {
            startedAt += sinceLast; // paused, not skipped
            return;
        }
        ClientQuestUnlocks.Card card = ClientQuestUnlocks.peek();
        if (card == null) {
            shown = null;
            return;
        }
        if (card != shown) {
            shown = card;
            startedAt = now;
            shownIcon = ItemStack.EMPTY;
        }
        Optional<Quest> quest = ClientQuestDefinitions.quest(card.quest());
        if (quest.isEmpty()) {
            ClientQuestUnlocks.pop(); // not sent to this client (or gone after a reload): nothing to show
            shown = null;
            return;
        }
        if (shownIcon.isEmpty()) {
            shownIcon = QuestBookText.icon(quest.get().icon());
        }
        long hold = ClientQuestUnlocks.size() > 1 ? HOLD_QUEUED_MS : HOLD_MS;
        long age = now - startedAt;
        long total = SLIDE_MS + hold + SLIDE_MS;
        if (age >= total) {
            ClientQuestUnlocks.pop();
            shown = null;
            return;
        }
        float slide;
        if (age < SLIDE_MS) {
            slide = age / (float) SLIDE_MS;
        } else if (age > SLIDE_MS + hold) {
            slide = (total - age) / (float) SLIDE_MS;
        } else {
            slide = 1.0F;
        }
        draw(graphics, mc.font, quest.get(), card.more(), easeOut(slide));
    }

    private static void draw(GuiGraphicsExtractor graphics, Font font, Quest quest, int more, float slide) {
        int maxWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, (graphics.guiWidth() - BOSS_BAR_WIDTH) / 2 - 2 * MARGIN));
        int textRoom = maxWidth - TEXT_X - 6;
        Component label = Component.translatable("hud.neroquests.quest_unlocked");
        Component extra = more > 0 ? Component.translatable("hud.neroquests.quest_unlocked.more", Integer.valueOf(more)) : null;
        List<FormattedCharSequence> titleLines = font.split(QuestText.title(quest), textRoom);
        FormattedCharSequence title = titleLines.isEmpty() ? FormattedCharSequence.EMPTY : titleLines.get(0);
        int line1 = font.width(label) + (extra == null ? 0 : 6 + font.width(extra));
        int width = Math.min(maxWidth, Math.max(MIN_WIDTH, TEXT_X + Math.max(line1, font.width(title)) + 6));

        int x = MARGIN - Math.round((width + MARGIN) * (1.0F - slide));
        int y = MARGIN;
        graphics.fill(x, y, x + width, y + HEIGHT, BookPaint.PANEL);
        BookGfx.outline(graphics, x, y, width, HEIGHT, BookPaint.PANEL_EDGE);
        graphics.fill(x, y, x + 2, y + HEIGHT, BookPaint.CYAN);
        graphics.item(shownIcon, x + 6, y + 5);
        graphics.text(font, label.getVisualOrderText(), x + TEXT_X, y + 4, BookPaint.CYAN, false);
        if (extra != null) {
            graphics.text(font, extra.getVisualOrderText(), x + TEXT_X + font.width(label) + 6, y + 4,
                    BookPaint.SUBTLE, false);
        }
        graphics.text(font, title, x + TEXT_X, y + 15, BookPaint.TITLE, false);
    }

    private static float easeOut(float t) {
        float clamped = Math.max(0.0F, Math.min(1.0F, t));
        float inv = 1.0F - clamped;
        return 1.0F - inv * inv * inv;
    }

    /** F3 open or the HUD hidden (F1). */
    private static boolean hidden(Minecraft mc) {
        //? if >=26.2 {
        boolean hudHidden = mc.gui.hud.isHidden();
        //?} else {
        /*boolean hudHidden = mc.options.hideGui;
        *///?}
        //? if >=1.20.2 {
        return hudHidden || mc.getDebugOverlay().showDebugScreen();
        //?} else {
        /*return hudHidden || mc.options.renderDebug;
        *///?}
    }
}
