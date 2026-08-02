package za.co.neroland.neroquests.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import za.co.neroland.nerolandcore.progression.ClientGates;

import za.co.neroland.neroquests.client.ClientQuestDefinitions;
import za.co.neroland.neroquests.client.ClientQuestProgress;
import za.co.neroland.neroquests.client.QuestBookKeys;
import za.co.neroland.neroquests.data.QuestProgress;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * The quest book screen: chapter tabs down the left, a pannable quest graph on the right, and a
 * detail page for whichever quest you click.
 *
 * <p><b>It is a pure view.</b> Every value it shows comes from the two client mirror caches the
 * server already pushed ({@link ClientQuestDefinitions}, {@link ClientQuestProgress}) plus Core's
 * {@link ClientGates} mirror. It sends nothing to the server, completes nothing and claims nothing;
 * clicking a node only changes what this screen draws.
 *
 * <p>Everything is drawn with {@code GuiGraphicsExtractor} primitives — rectangles, text, item
 * icons — so the book needs no GUI artwork at all and cannot break against a resource pack.
 *
 * <p>26.x rendering note: screens no longer have {@code render(GuiGraphics, ...)}. Drawing happens
 * in the extract pass, {@code extractRenderState(GuiGraphicsExtractor, ...)}; tooltips are queued
 * with {@code setComponentTooltipForNextFrame} and drawn afterwards by the (final) outer
 * {@code extractRenderStateWithTooltipAndSubtitles}. Input arrives as {@code MouseButtonEvent} /
 * {@code KeyEvent} records rather than loose ints.
 */
public class QuestBookScreen extends Screen {

    private static final int PANEL_WIDTH = 400;
    private static final int PANEL_HEIGHT = 220;
    private static final int TAB_WIDTH = 96;
    private static final int TAB_HEIGHT = 20;

    /** Pixels per {@code Chapter.Entry} grid unit — chapter layouts are abstract, not pixel, coords. */
    private static final int GRID = 30;
    /** Side length of a quest node's frame. */
    private static final int NODE = 22;

    private static final int PANEL = 0xFF12161F;
    private static final int PANEL_EDGE = 0xFF2E4A5A;
    private static final int TROUGH = 0xFF0B1119;
    private static final int INK = 0xFF05080D;
    private static final int TITLE = 0xFFD6ECFF;
    private static final int SUBTLE = 0xFF8DA0B4;

    private static final int COLOUR_AVAILABLE = 0xFFB388FF;
    private static final int COLOUR_COMPLETED = 0xFF58D08A;
    private static final int COLOUR_IN_PROGRESS = 0xFFFFB74D;
    private static final int COLOUR_LOCKED = 0xFF4A5058;

    /** Render-only node states, derived client-side. Hidden quests are simply not drawn. */
    private enum NodeState {
        COMPLETED,
        IN_PROGRESS,
        AVAILABLE,
        LOCKED
    }

    private final List<Chapter> chapters = new ArrayList<>();

    private int selectedChapter;
    private int tabScroll;
    private Identifier selectedQuest;

    private int panX;
    private int panY;
    private int detailScroll;
    private int detailHeight;

    private int left;
    private int top;

    private double pressX;
    private double pressY;
    private boolean panning;
    private boolean dragged;

    public QuestBookScreen() {
        super(Component.translatable("gui.neroquests.quest_book.title"));
    }

    /**
     * Puts a fresh quest book on screen. The installed opener for {@code QuestBookOpener}.
     *
     * <p>26.x renamed {@code Minecraft#setScreen} to {@code setScreenAndShow}.
     */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.setScreenAndShow(new QuestBookScreen());
        }
    }

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - PANEL_WIDTH) / 2;
        this.top = (this.height - PANEL_HEIGHT) / 2;
        this.chapters.clear();
        this.chapters.addAll(ClientQuestDefinitions.allChapters());
        this.chapters.sort(Comparator.comparing(chapter -> chapter.id().toString()));
        if (this.selectedChapter >= this.chapters.size()) {
            this.selectedChapter = 0;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // --- rendering ----------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        // Own dimmer: the book must stay readable regardless of what the vanilla screen background
        // does behind it.
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);
        drawPanel(graphics);
        drawTabs(graphics, mouseX, mouseY);

        if (this.chapters.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("gui.neroquests.quest_book.empty"),
                    (contentLeft() + contentRight()) / 2, contentTop() + 40, SUBTLE);
            return;
        }
        if (this.selectedQuest != null) {
            drawDetail(graphics);
        } else {
            drawGraph(graphics, mouseX, mouseY);
        }
    }

    private void drawPanel(GuiGraphicsExtractor graphics) {
        graphics.fill(this.left - 1, this.top - 1,
                this.left + PANEL_WIDTH + 1, this.top + PANEL_HEIGHT + 1, PANEL_EDGE);
        graphics.fill(this.left, this.top, this.left + PANEL_WIDTH, this.top + PANEL_HEIGHT, PANEL);
        graphics.fill(this.left, this.top, this.left + PANEL_WIDTH, this.top + 1, 0x22FFFFFF);
        graphics.text(this.font, this.title, this.left + 10, this.top + 8, TITLE, false);
    }

    private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = this.left + 8;
        int y = this.top + 22;
        int rows = visibleTabRows();
        graphics.enableScissor(x, y, x + TAB_WIDTH, y + rows * TAB_HEIGHT);
        for (int row = 0; row < rows; row++) {
            int index = this.tabScroll + row;
            if (index >= this.chapters.size()) {
                break;
            }
            Chapter chapter = this.chapters.get(index);
            int tabY = y + row * TAB_HEIGHT;
            boolean selected = index == this.selectedChapter;
            boolean hovered = mouseX >= x && mouseX < x + TAB_WIDTH
                    && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT - 2;
            graphics.fill(x, tabY, x + TAB_WIDTH, tabY + TAB_HEIGHT - 2,
                    selected ? COLOUR_AVAILABLE : (hovered ? PANEL_EDGE : INK));
            graphics.fill(x + 1, tabY + 1, x + TAB_WIDTH - 1, tabY + TAB_HEIGHT - 3,
                    selected ? 0xFF20143A : TROUGH);
            graphics.item(QuestBookText.icon(chapter.icon()), x + 2, tabY + 1);
            graphics.text(this.font, Component.translatable(chapter.title()),
                    x + 21, tabY + 5, selected ? TITLE : SUBTLE, false);
        }
        graphics.disableScissor();
    }

    private void drawGraph(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Chapter chapter = currentChapter();
        if (chapter == null) {
            return;
        }
        int contentLeft = contentLeft();
        int contentTop = contentTop();
        int contentRight = contentRight();
        int contentBottom = contentBottom();
        graphics.fill(contentLeft - 1, contentTop - 1, contentRight + 1, contentBottom + 1, PANEL_EDGE);
        graphics.fill(contentLeft, contentTop, contentRight, contentBottom, TROUGH);
        graphics.enableScissor(contentLeft, contentTop, contentRight, contentBottom);

        Set<Identifier> completed = ClientQuestProgress.completedQuests();
        List<Chapter.Entry> entries = chapter.quests();

        // Dependency lines first, so nodes always sit on top of them. A line is drawn only when both
        // ends are laid out in THIS chapter and both are visible; a prerequisite in another chapter
        // has no position here to draw from.
        for (Chapter.Entry entry : entries) {
            Quest quest = visibleQuest(entry.quest());
            if (quest == null) {
                continue;
            }
            int toX = nodeX(entry) + NODE / 2;
            int toY = nodeY(entry) + NODE / 2;
            for (Identifier prerequisite : quest.prerequisites()) {
                Optional<Chapter.Entry> from = chapter.entry(prerequisite);
                if (from.isEmpty() || visibleQuest(prerequisite) == null) {
                    continue;
                }
                int fromX = nodeX(from.get()) + NODE / 2;
                int fromY = nodeY(from.get()) + NODE / 2;
                dottedLine(graphics, fromX, fromY, toX, toY,
                        completed.contains(prerequisite) ? COLOUR_COMPLETED : COLOUR_LOCKED);
            }
        }

        Quest hoveredQuest = null;
        NodeState hoveredState = null;
        for (Chapter.Entry entry : entries) {
            Quest quest = visibleQuest(entry.quest());
            if (quest == null) {
                continue;
            }
            int x = nodeX(entry);
            int y = nodeY(entry);
            NodeState state = stateOf(quest, completed);
            drawNode(graphics, quest, x, y, state);
            if (mouseX >= x && mouseX < x + NODE && mouseY >= y && mouseY < y + NODE
                    && mouseX >= contentLeft && mouseX < contentRight
                    && mouseY >= contentTop && mouseY < contentBottom) {
                hoveredQuest = quest;
                hoveredState = state;
            }
        }
        graphics.disableScissor();

        if (hoveredQuest != null && hoveredState != null) {
            graphics.setComponentTooltipForNextFrame(this.font,
                    tooltipFor(hoveredQuest, hoveredState), mouseX, mouseY);
        }
    }

    private void drawNode(GuiGraphicsExtractor graphics, Quest quest, int x, int y, NodeState state) {
        int border = colourOf(state);
        graphics.fill(x, y, x + NODE, y + NODE, border);
        graphics.fill(x + 1, y + 1, x + NODE - 1, y + NODE - 1,
                state == NodeState.LOCKED ? 0xFF0A0C11 : 0xFF0C1E2B);
        graphics.item(QuestBookText.icon(quest.icon()), x + 3, y + 3);
        if (state == NodeState.COMPLETED) {
            // A small corner pip reads as "done" without needing a check-mark glyph.
            graphics.fill(x + NODE - 6, y + 2, x + NODE - 2, y + 6, COLOUR_COMPLETED);
        }
    }

    private void drawDetail(GuiGraphicsExtractor graphics) {
        Quest quest = ClientQuestDefinitions.quest(this.selectedQuest).orElse(null);
        if (quest == null) {
            this.selectedQuest = null;
            return;
        }
        int contentLeft = contentLeft();
        int contentTop = contentTop();
        int contentRight = contentRight();
        int contentBottom = contentBottom();
        graphics.fill(contentLeft - 1, contentTop - 1, contentRight + 1, contentBottom + 1, PANEL_EDGE);
        graphics.fill(contentLeft, contentTop, contentRight, contentBottom, TROUGH);
        graphics.enableScissor(contentLeft, contentTop, contentRight, contentBottom);

        int wrapWidth = contentRight - contentLeft - 24;
        int startY = contentTop + 6 - this.detailScroll;
        int y = startY;

        graphics.item(QuestBookText.icon(quest.icon()), contentLeft + 6, y);
        graphics.text(this.font, Component.translatable(quest.title()),
                contentLeft + 28, y + 4, TITLE, false);
        y += 22;

        NodeState state = stateOf(quest, ClientQuestProgress.completedQuests());
        graphics.text(this.font, Component.translatable(stateKey(state)),
                contentLeft + 8, y, colourOf(state), false);
        y += 13;

        if (!quest.description().isEmpty()) {
            for (FormattedCharSequence line
                    : this.font.split(Component.translatable(quest.description()), wrapWidth)) {
                graphics.text(this.font, line, contentLeft + 8, y, SUBTLE, false);
                y += this.font.lineHeight;
            }
            y += 5;
        }

        QuestProgress progress = ClientQuestProgress.progress(quest);
        List<ObjectiveSpec> objectives = quest.objectives();
        if (!objectives.isEmpty()) {
            graphics.text(this.font, Component.translatable("gui.neroquests.quest_book.objectives"),
                    contentLeft + 8, y, COLOUR_AVAILABLE, false);
            y += 12;
            for (int index = 0; index < objectives.size(); index++) {
                ObjectiveSpec spec = objectives.get(index);
                int target = Math.max(1, spec.target());
                int current = Mth.clamp(progress.counter(index), 0, target);
                Component count = Component.literal(current + " / " + target);
                graphics.text(this.font, QuestBookText.objective(spec), contentLeft + 12, y, TITLE, false);
                graphics.text(this.font, count,
                        contentRight - 10 - this.font.width(count), y, SUBTLE, false);
                y += 11;
                bar(graphics, contentLeft + 12, y, wrapWidth - 8, 4, current / (float) target,
                        current >= target ? COLOUR_COMPLETED : COLOUR_IN_PROGRESS);
                y += 11;
            }
        }

        List<RewardSpec> rewards = quest.rewards();
        if (!rewards.isEmpty()) {
            y += 3;
            graphics.text(this.font, Component.translatable("gui.neroquests.quest_book.rewards"),
                    contentLeft + 8, y, COLOUR_AVAILABLE, false);
            y += 12;
            for (RewardSpec reward : rewards) {
                graphics.text(this.font,
                        Component.translatable("gui.neroquests.quest_book.line", QuestBookText.reward(reward)),
                        contentLeft + 12, y, SUBTLE, false);
                y += 11;
            }
        }

        y += 4;
        graphics.text(this.font, Component.translatable("gui.neroquests.quest_book.back"),
                contentLeft + 8, y, COLOUR_LOCKED, false);
        y += 11;

        graphics.disableScissor();
        this.detailHeight = y - startY;
    }

    /** A dotted 2px line — cheaper and clearer than a solid diagonal at GUI scale. */
    private static void dottedLine(GuiGraphicsExtractor graphics, int fromX, int fromY,
                                   int toX, int toY, int colour) {
        int steps = Math.max(1, (int) (Math.hypot(toX - fromX, toY - fromY) / 5.0D));
        for (int step = 0; step <= steps; step++) {
            float progress = step / (float) steps;
            int x = Math.round(Mth.lerp(progress, fromX, toX));
            int y = Math.round(Mth.lerp(progress, fromY, toY));
            graphics.fill(x, y, x + 2, y + 2, colour);
        }
    }

    private static void bar(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                            float fraction, int colour) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, INK);
        graphics.fill(x, y, x + width, y + height, TROUGH);
        int filled = Mth.clamp(Math.round(width * fraction), 0, width);
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + height, colour);
        }
    }

    private List<Component> tooltipFor(Quest quest, NodeState state) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(quest.title()));
        lines.add(Component.translatable(stateKey(state)));
        QuestProgress progress = ClientQuestProgress.progress(quest);
        List<ObjectiveSpec> objectives = quest.objectives();
        for (int index = 0; index < objectives.size(); index++) {
            ObjectiveSpec spec = objectives.get(index);
            int target = Math.max(1, spec.target());
            int current = Mth.clamp(progress.counter(index), 0, target);
            lines.add(Component.translatable("gui.neroquests.quest_book.objective_line",
                    QuestBookText.objective(spec), Component.literal(current + " / " + target)));
        }
        List<RewardSpec> rewards = quest.rewards();
        if (!rewards.isEmpty()) {
            lines.add(Component.translatable("gui.neroquests.quest_book.rewards"));
            for (RewardSpec reward : rewards) {
                lines.add(Component.translatable("gui.neroquests.quest_book.line",
                        QuestBookText.reward(reward)));
            }
        }
        return lines;
    }

    // --- state derivation (render only) -------------------------------------

    /**
     * The quest behind a chapter entry, or {@code null} when it should not be drawn — either it is
     * missing from the synced snapshot, or its {@code visible_gate} is still closed. A hidden quest
     * is not dimmed, it is absent: nothing about it may leak into the book.
     */
    private static Quest visibleQuest(Identifier id) {
        Quest quest = ClientQuestDefinitions.quest(id).orElse(null);
        if (quest == null) {
            return null;
        }
        Optional<Identifier> gate = quest.visibleGate();
        if (gate.isPresent() && !ClientGates.isOpen(gate.get())) {
            return null;
        }
        return quest;
    }

    /**
     * Completed beats in-progress beats available beats locked. Server-scope quests read from the
     * server-scope progress section automatically — {@link ClientQuestProgress#progress(Quest)}
     * picks the section from the quest's own scope.
     */
    private static NodeState stateOf(Quest quest, Set<Identifier> completed) {
        QuestProgress progress = ClientQuestProgress.progress(quest);
        if (progress.isComplete()) {
            return NodeState.COMPLETED;
        }
        for (int index = 0; index < quest.objectives().size(); index++) {
            if (progress.counter(index) > 0) {
                return NodeState.IN_PROGRESS;
            }
        }
        return quest.prerequisitesMet(completed) ? NodeState.AVAILABLE : NodeState.LOCKED;
    }

    private static String stateKey(NodeState state) {
        return switch (state) {
            case COMPLETED -> "gui.neroquests.state.completed";
            case IN_PROGRESS -> "gui.neroquests.state.in_progress";
            case AVAILABLE -> "gui.neroquests.state.available";
            case LOCKED -> "gui.neroquests.state.locked";
        };
    }

    private static int colourOf(NodeState state) {
        return switch (state) {
            case COMPLETED -> COLOUR_COMPLETED;
            case IN_PROGRESS -> COLOUR_IN_PROGRESS;
            case AVAILABLE -> COLOUR_AVAILABLE;
            case LOCKED -> COLOUR_LOCKED;
        };
    }

    // --- layout -------------------------------------------------------------

    private Chapter currentChapter() {
        if (this.selectedChapter < 0 || this.selectedChapter >= this.chapters.size()) {
            return null;
        }
        return this.chapters.get(this.selectedChapter);
    }

    private int visibleTabRows() {
        return (PANEL_HEIGHT - 30) / TAB_HEIGHT;
    }

    private int contentLeft() {
        return this.left + TAB_WIDTH + 14;
    }

    private int contentTop() {
        return this.top + 22;
    }

    private int contentRight() {
        return this.left + PANEL_WIDTH - 8;
    }

    private int contentBottom() {
        return this.top + PANEL_HEIGHT - 8;
    }

    private int nodeX(Chapter.Entry entry) {
        return contentLeft() + 12 + this.panX + entry.x() * GRID;
    }

    private int nodeY(Chapter.Entry entry) {
        return contentTop() + 12 + this.panY + entry.y() * GRID;
    }

    /** Keeps the graph from being dragged entirely out of the viewport. */
    private void clampPan() {
        Chapter chapter = currentChapter();
        if (chapter == null) {
            return;
        }
        int maxX = 0;
        int maxY = 0;
        for (Chapter.Entry entry : chapter.quests()) {
            maxX = Math.max(maxX, entry.x());
            maxY = Math.max(maxY, entry.y());
        }
        int graphWidth = maxX * GRID + NODE + 24;
        int graphHeight = maxY * GRID + NODE + 24;
        int viewWidth = contentRight() - contentLeft();
        int viewHeight = contentBottom() - contentTop();
        this.panX = Mth.clamp(this.panX, Math.min(0, viewWidth - graphWidth), 0);
        this.panY = Mth.clamp(this.panY, Math.min(0, viewHeight - graphHeight), 0);
    }

    private boolean inContent(double x, double y) {
        return x >= contentLeft() && x < contentRight() && y >= contentTop() && y < contentBottom();
    }

    // --- input --------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        this.pressX = event.x();
        this.pressY = event.y();
        this.dragged = false;
        // Panning only makes sense over the graph; the detail page scrolls with the wheel instead.
        this.panning = this.selectedQuest == null && inContent(event.x(), event.y());
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.panning) {
            if (Math.abs(event.x() - this.pressX) > 3.0D || Math.abs(event.y() - this.pressY) > 3.0D) {
                this.dragged = true;
            }
            this.panX += (int) Math.round(dragX);
            this.panY += (int) Math.round(dragY);
            clampPan();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        boolean wasDragged = this.dragged;
        this.panning = false;
        this.dragged = false;
        // Select on release, and only when the press was not a pan — otherwise every drag that
        // started on a node would also open it.
        if (!wasDragged) {
            handleClick(event.x(), event.y());
        }
        return super.mouseReleased(event);
    }

    private void handleClick(double mouseX, double mouseY) {
        int tabX = this.left + 8;
        int tabY = this.top + 22;
        for (int row = 0; row < visibleTabRows(); row++) {
            int index = this.tabScroll + row;
            if (index >= this.chapters.size()) {
                break;
            }
            int rowY = tabY + row * TAB_HEIGHT;
            if (mouseX >= tabX && mouseX < tabX + TAB_WIDTH
                    && mouseY >= rowY && mouseY < rowY + TAB_HEIGHT - 2) {
                this.selectedChapter = index;
                this.selectedQuest = null;
                this.panX = 0;
                this.panY = 0;
                this.detailScroll = 0;
                return;
            }
        }
        if (this.selectedQuest != null || !inContent(mouseX, mouseY)) {
            return;
        }
        Chapter chapter = currentChapter();
        if (chapter == null) {
            return;
        }
        for (Chapter.Entry entry : chapter.quests()) {
            if (visibleQuest(entry.quest()) == null) {
                continue;
            }
            int x = nodeX(entry);
            int y = nodeY(entry);
            if (mouseX >= x && mouseX < x + NODE && mouseY >= y && mouseY < y + NODE) {
                this.selectedQuest = entry.quest();
                this.detailScroll = 0;
                return;
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) Math.signum(scrollY);
        if (step != 0 && mouseX >= this.left + 8 && mouseX < this.left + 8 + TAB_WIDTH) {
            int max = Math.max(0, this.chapters.size() - visibleTabRows());
            this.tabScroll = Mth.clamp(this.tabScroll - step, 0, max);
            return true;
        }
        if (step != 0 && this.selectedQuest != null) {
            int max = Math.max(0, this.detailHeight - (contentBottom() - contentTop()) + 12);
            this.detailScroll = Mth.clamp(this.detailScroll - step * 12, 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Escape steps back out of a quest's detail page before it closes the book.
        if (event.key() == InputConstants.KEY_ESCAPE && this.selectedQuest != null) {
            this.selectedQuest = null;
            this.detailScroll = 0;
            return true;
        }
        // The same key that opened the book closes it, like the vanilla inventory.
        if (QuestBookKeys.OPEN_QUEST_BOOK.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
