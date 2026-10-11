package za.co.neroland.neroquests.client.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import za.co.neroland.nerolandcore.progression.ClientGates;

import za.co.neroland.neroquests.client.ClientEditor;
import za.co.neroland.neroquests.client.ClientQuestDefinitions;
import za.co.neroland.neroquests.client.ClientQuestProgress;
import za.co.neroland.neroquests.client.QuestBookKeys;
import za.co.neroland.neroquests.client.screen.BookLayout.Layout;
import za.co.neroland.neroquests.client.screen.BookLayout.Rect;
import za.co.neroland.neroquests.custom.EditGate;
import za.co.neroland.neroquests.data.QuestProgress;
import za.co.neroland.neroquests.platform.Services;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestSource;
import za.co.neroland.neroquests.quest.QuestStates;
import za.co.neroland.neroquests.quest.QuestText;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * The quest book screen: chapter tabs down the left, a pannable quest graph on the right, and a
 * detail page for whichever quest you click — plus, for players the server says may author, an
 * <b>edit mode</b> (E1–E6) handled by {@link BookEditor}.
 *
 * <p><b>View mode is a pure view.</b> Every value it shows comes from the client mirror caches the
 * server already pushed ({@link ClientQuestDefinitions}, {@link ClientQuestProgress}) plus Core's
 * {@link ClientGates} mirror. It sends nothing to the server, completes nothing and claims nothing.
 *
 * <p><b>Edit mode decides nothing either.</b> The toggle only appears when the last definitions sync
 * said {@code can_author}; every change is a serverbound request the server re-authorises and
 * validates, and the screen learns the outcome from the reply toast and the next sync. When a sync
 * says {@code can_author} is gone, the book drops back to view mode with a toast.
 *
 * <p>Everything is drawn with {@code GuiGraphicsExtractor} primitives — rectangles, text, item
 * icons — plus vanilla buttons and text fields, so the book needs no GUI artwork. All geometry
 * comes from {@link BookLayout} (fits GUI scale 1–4 at 854&times;480) and {@link GraphGeometry}.
 *
 * <p>26.x rendering note: screens no longer have {@code render(GuiGraphics, ...)}. Drawing happens
 * in the extract pass, {@code extractRenderState(GuiGraphicsExtractor, ...)}; tooltips are queued
 * with {@code setComponentTooltipForNextFrame} and drawn afterwards by the (final) outer
 * {@code extractRenderStateWithTooltipAndSubtitles}. Input arrives as {@code MouseButtonEvent} /
 * {@code KeyEvent} records rather than loose ints. Widgets are drawn by {@code super.extractRenderState},
 * which therefore runs after the book's own fills.
 */
public class QuestBookScreen extends Screen {

    static final int PANEL = 0xFF12161F;
    static final int PANEL_EDGE = 0xFF2E4A5A;
    static final int TROUGH = 0xFF0B1119;
    static final int INK = 0xFF05080D;
    static final int TITLE = 0xFFD6ECFF;
    static final int SUBTLE = 0xFF8DA0B4;
    static final int WARN = 0xFFFF8A65;

    static final int COLOUR_AVAILABLE = 0xFFB388FF;
    static final int COLOUR_COMPLETED = 0xFF58D08A;
    static final int COLOUR_IN_PROGRESS = 0xFFFFB74D;
    static final int COLOUR_LOCKED = 0xFF4A5058;

    private static final int NODE = GraphGeometry.NODE;

    /** How long a toast stays up. */
    private static final long TOAST_MS = 4_000L;

    /** Render-only node states, derived client-side. Hidden quests are simply not drawn. */
    enum NodeState {
        COMPLETED,
        IN_PROGRESS,
        AVAILABLE,
        LOCKED
    }

    /** What a mouse press on the canvas turned into. */
    private enum Drag {
        NONE,
        PAN,
        NODE,
        LINK,
        LOCKED
    }

    private final List<Chapter> chapters = new ArrayList<>();
    private final BookEditor editor = new BookEditor(this);

    Layout layout = BookLayout.compute(0, 0, false);

    private int selectedChapter;
    private Identifier selectedChapterId;
    private int tabScroll;
    Identifier selectedQuest;

    private int panX;
    private int panY;
    private int detailScroll;
    private int detailHeight;

    private double pressX;
    private double pressY;
    private Drag drag = Drag.NONE;
    private boolean dragged;
    private Identifier dragQuest;
    private int dragGrabX;
    private int dragGrabY;

    /** Node moves sent but not yet confirmed by a sync, so a dropped node does not snap back meanwhile. */
    final Map<Identifier, int[]> pendingMoves = new HashMap<>();

    boolean editMode;
    private boolean open;
    private int seenGeneration = -1;
    private Object structure;

    private Component toast;
    private long toastUntil;

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
        this.open = true;
        this.layout = BookLayout.compute(this.width, this.height, this.editMode);
        refreshChapters();
        this.seenGeneration = ClientQuestDefinitions.generation();
        this.structure = structureSignature();
        if (ClientQuestDefinitions.canAuthor()) {
            Rect toggle = this.layout.toggle();
            Button button = addRenderableWidget(Button.builder(
                            Component.translatable(this.editMode ? "gui.neroquests.editor.toggle.done"
                                    : "gui.neroquests.editor.toggle.edit"), ignored -> toggleEditMode())
                    .bounds(toggle.x(), toggle.y(), toggle.w(), toggle.h())
                    .tooltip(Tooltip.create(Component.translatable(this.editMode
                            ? "gui.neroquests.editor.toggle.done.tooltip"
                            : "gui.neroquests.editor.toggle.edit.tooltip")))
                    .build());
            button.active = !this.editor.overlayOpen();
        }
        if (this.editMode) {
            this.editor.init();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // --- shared with BookEditor -------------------------------------------------------------------

    Font font() {
        return this.font;
    }

    Minecraft minecraft() {
        return this.minecraft;
    }

    <T extends AbstractWidget> T add(T widget) {
        return addRenderableWidget(widget);
    }

    void rebuild() {
        rebuildWidgets();
    }

    /** Rebuild only while this book is the open screen (for replies that arrive later). */
    void rebuildIfOpen() {
        if (this.open) {
            rebuildWidgets();
        }
    }

    @Override
    public void removed() {
        super.removed();
        this.open = false;
    }

    void focus(AbstractWidget widget) {
        setInitialFocus(widget);
    }

    Chapter currentChapter() {
        if (this.selectedChapter < 0 || this.selectedChapter >= this.chapters.size()) {
            return null;
        }
        return this.chapters.get(this.selectedChapter);
    }

    List<Chapter> chapters() {
        return this.chapters;
    }

    void selectChapter(Identifier id) {
        this.selectedChapterId = id;
        refreshChapters();
        this.selectedQuest = null;
        this.panX = 0;
        this.panY = 0;
        this.detailScroll = 0;
    }

    void selectQuest(Identifier id) {
        this.selectedQuest = id;
        this.detailScroll = 0;
    }

    int panX() {
        return this.panX;
    }

    int panY() {
        return this.panY;
    }

    /** Show a short message at the bottom of the book. */
    void showToast(Component message) {
        this.toast = message;
        this.toastUntil = System.currentTimeMillis() + TOAST_MS;
    }

    // --- definitions changes (E1) ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        Component queued = ClientEditor.pollToast();
        if (queued != null) {
            showToast(queued);
        }
        int generation = ClientQuestDefinitions.generation();
        if (generation != this.seenGeneration) {
            this.seenGeneration = generation;
            definitionsChanged();
        }
    }

    /**
     * A definitions sync landed: re-read chapters, drop optimistic node moves, and re-check the
     * authorisation hint — if it was withdrawn while editing, fall back to view mode with a toast.
     * Widgets are rebuilt only when what they show changed, so a sync caused by someone else's edit
     * does not steal focus from a half-typed field.
     */
    private void definitionsChanged() {
        this.pendingMoves.clear();
        boolean rebuild = false;
        if (this.editMode && !EditGate.editModeAfterSync(this.editMode, ClientQuestDefinitions.canAuthor())) {
            this.editMode = false;
            this.editor.reset();
            showToast(Component.translatable("gui.neroquests.editor.revoked"));
            rebuild = true;
        }
        refreshChapters();
        if (this.selectedQuest != null && ClientQuestDefinitions.quest(this.selectedQuest).isEmpty()
                && !this.editor.awaiting(this.selectedQuest)) {
            this.selectedQuest = null;
            rebuild = true;
        }
        Object signature = structureSignature();
        if (rebuild || !Objects.equals(signature, this.structure)) {
            this.structure = signature;
            rebuildWidgets();
        }
    }

    /** What the widgets depend on: authorisation, the chapter list and the selected quest's content. */
    private Object structureSignature() {
        List<Object> parts = new ArrayList<>();
        parts.add(Boolean.valueOf(ClientQuestDefinitions.canAuthor()));
        for (Chapter chapter : this.chapters) {
            parts.add(chapter.id());
            parts.add(chapter.title());
        }
        if (this.selectedQuest != null) {
            parts.add(ClientQuestDefinitions.quest(this.selectedQuest).orElse(null));
            parts.add(ClientQuestDefinitions.customExtra(this.selectedQuest).toString());
        }
        parts.add(ClientQuestDefinitions.editorInfo().toString());
        return parts;
    }

    private void refreshChapters() {
        Chapter before = currentChapter();
        Identifier keep = this.selectedChapterId != null ? this.selectedChapterId
                : before == null ? null : before.id();
        this.chapters.clear();
        this.chapters.addAll(ChapterOrder.sort(new ArrayList<>(ClientQuestDefinitions.allChapters()),
                chapter -> new ChapterOrder.Row(chapter.id(), chapter.source(), order(chapter))));
        this.selectedChapter = 0;
        boolean found = false;
        if (keep != null) {
            for (int index = 0; index < this.chapters.size(); index++) {
                if (this.chapters.get(index).id().equals(keep)) {
                    this.selectedChapter = index;
                    found = true;
                }
            }
        }
        // A chapter the editor just created stays the selection until the sync that brings it arrives.
        if (found || keep == null || !this.editor.awaiting(keep)) {
            Chapter now = currentChapter();
            this.selectedChapterId = now == null ? null : now.id();
        }
        int max = Math.max(0, this.chapters.size() - this.layout.tabRows());
        this.tabScroll = Mth.clamp(this.tabScroll, 0, max);
    }

    /** A custom chapter's {@code order} extra (0 for the others). */
    static int order(Chapter chapter) {
        try {
            var extra = ClientQuestDefinitions.customExtra(chapter.id()).get("order");
            return extra == null ? 0 : extra.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void toggleEditMode() {
        if (!this.editMode && !ClientQuestDefinitions.canAuthor()) {
            return;
        }
        this.editMode = !this.editMode;
        this.editor.reset();
        this.pendingMoves.clear();
        this.drag = Drag.NONE;
        if (this.editMode && this.selectedQuest != null && QuestSource.of(this.selectedQuest) != QuestSource.CUSTOM) {
            this.selectedQuest = null;
        }
        rebuildWidgets();
    }

    /** Whether the quest editor panel (rather than the graph or a read-only detail page) is showing. */
    boolean questPanelOpen() {
        return this.editMode && this.selectedQuest != null && QuestSource.of(this.selectedQuest) == QuestSource.CUSTOM;
    }

    // --- rendering ---------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Own dimmer: the book must stay readable regardless of what the vanilla screen background
        // does behind it.
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);
        drawPanel(graphics);
        boolean modal = this.editor.overlayOpen();
        Component tabTip = drawTabs(graphics, mouseX, mouseY);

        if (this.chapters.isEmpty() && !this.editMode) {
            Rect content = this.layout.content();
            graphics.centeredText(this.font, Component.translatable("gui.neroquests.quest_book.empty"),
                    content.x() + content.w() / 2, content.y() + 40, SUBTLE);
        } else if (questPanelOpen()) {
            this.editor.drawQuestPanel(graphics, mouseX, mouseY);
        } else if (this.selectedQuest != null) {
            Quest quest = ClientQuestDefinitions.quest(this.selectedQuest).orElse(null);
            if (quest == null) {
                this.selectedQuest = null;
            } else {
                Rect area = this.layout.content();
                frame(graphics, area);
                this.detailHeight = drawDetail(graphics, quest, area, this.detailScroll, true);
            }
        } else {
            if (this.editMode) {
                this.editor.drawToolbar(graphics);
            }
            drawGraph(graphics, mouseX, mouseY, modal);
        }

        if (modal) {
            graphics.nextStratum();
            this.editor.drawOverlay(graphics, mouseX, mouseY);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (!modal) {
            this.editor.drawAfterWidgets(graphics, mouseX, mouseY);
        }
        drawToast(graphics);
        if (tabTip != null && !modal) {
            graphics.setTooltipForNextFrame(this.font, this.font.split(tabTip, 200), mouseX, mouseY);
        }
    }

    static void frame(GuiGraphicsExtractor graphics, Rect area) {
        graphics.fill(area.x() - 1, area.y() - 1, area.right() + 1, area.bottom() + 1, PANEL_EDGE);
        graphics.fill(area.x(), area.y(), area.right(), area.bottom(), TROUGH);
    }

    private void drawPanel(GuiGraphicsExtractor graphics) {
        Rect panel = this.layout.panel();
        graphics.fill(panel.x() - 1, panel.y() - 1, panel.right() + 1, panel.bottom() + 1, PANEL_EDGE);
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), PANEL);
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.y() + 1, 0x22FFFFFF);
        int titleY = panel.y() + (this.layout.header().h() - this.font.lineHeight) / 2 + 1;
        int maxWidth = (ClientQuestDefinitions.canAuthor() ? this.layout.toggle().x() : panel.right())
                - panel.x() - this.layout.pad() - 4;
        Component title = this.editMode ? Component.translatable("gui.neroquests.editor.title") : this.title;
        graphics.text(this.font, trim(title, maxWidth), panel.x() + this.layout.pad() + 2, titleY, TITLE, false);
    }

    /** Draws the chapter column; returns the tooltip for the hovered row (compact titles, lock notes). */
    private Component drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Rect sidebar = this.layout.sidebar();
        int x = sidebar.x();
        int width = sidebar.w();
        int rows = this.layout.tabRows();
        boolean compact = width < BookLayout.TAB_WIDTH;
        Component tip = null;
        graphics.enableScissor(x, sidebar.y(), x + width, sidebar.y() + rows * BookLayout.TAB_HEIGHT);
        for (int row = 0; row < rows; row++) {
            int index = this.tabScroll + row;
            if (index >= this.chapters.size()) {
                break;
            }
            Chapter chapter = this.chapters.get(index);
            int tabY = sidebar.y() + row * BookLayout.TAB_HEIGHT;
            boolean selected = index == this.selectedChapter;
            boolean hovered = mouseX >= x && mouseX < x + width
                    && mouseY >= tabY && mouseY < tabY + BookLayout.TAB_HEIGHT - 2;
            graphics.fill(x, tabY, x + width, tabY + BookLayout.TAB_HEIGHT - 2,
                    selected ? COLOUR_AVAILABLE : (hovered ? PANEL_EDGE : INK));
            graphics.fill(x + 1, tabY + 1, x + width - 1, tabY + BookLayout.TAB_HEIGHT - 3,
                    selected ? 0xFF20143A : TROUGH);
            graphics.item(QuestBookText.icon(chapter.icon()), x + 2, tabY + 1);
            int badges = badgeWidth(chapter);
            if (!compact) {
                int textWidth = width - 23 - badges;
                graphics.text(this.font, trim(QuestText.title(chapter), textWidth),
                        x + 21, tabY + 5, selected ? TITLE : SUBTLE, false);
            }
            drawBadges(graphics, chapter, compact ? x + width - 7 : x + width - 3, tabY, compact);
            if (hovered) {
                tip = chapterTooltip(chapter, compact);
            }
        }
        graphics.disableScissor();
        return tip;
    }

    private int badgeWidth(Chapter chapter) {
        int width = chapter.modAbsent() ? 6 : 0;
        if (this.editMode) {
            width += 8;
        }
        return width;
    }

    /** Lock (spine/pack, edit mode), pencil (custom, edit mode) and "mod not installed" badges, right-aligned. */
    private void drawBadges(GuiGraphicsExtractor graphics, Chapter chapter, int right, int tabY, boolean compact) {
        int x = right;
        int y = compact ? tabY + 11 : tabY + 5;
        if (this.editMode) {
            x -= 6;
            if (chapter.source() == QuestSource.CUSTOM) {
                Glyphs.pencil(graphics, x, y, COLOUR_IN_PROGRESS);
            } else {
                Glyphs.lock(graphics, x, y, SUBTLE);
            }
            x -= 2;
        }
        if (chapter.modAbsent()) {
            x -= 4;
            Glyphs.warning(graphics, x, y, WARN);
        }
    }

    private Component chapterTooltip(Chapter chapter, boolean compact) {
        List<Component> lines = new ArrayList<>();
        if (compact) {
            lines.add(QuestText.title(chapter));
        }
        if (chapter.modAbsent()) {
            lines.add(Component.translatable("gui.neroquests.editor.chapter.mod_absent", chapter.id().getNamespace()));
        }
        if (this.editMode) {
            if (chapter.source() == QuestSource.CUSTOM) {
                lines.add(Component.translatable("gui.neroquests.editor.chapter.custom"));
            } else {
                lines.add(Component.translatable("gui.neroquests.editor.chapter.provided_by", providerName(chapter)));
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        Component joined = lines.get(0).copy();
        for (int index = 1; index < lines.size(); index++) {
            joined = Component.translatable("gui.neroquests.editor.lines", joined, lines.get(index));
        }
        return joined;
    }

    /** "Provided by" name: the owning mod's display name when the loader knows it, else the namespace. */
    static Component providerName(Chapter chapter) {
        String namespace = chapter.id().getNamespace();
        Optional<String> name = Services.PLATFORM.modDisplayName(namespace);
        return Component.literal(name.filter(value -> !value.isBlank()).orElse(namespace));
    }

    private void drawGraph(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean modal) {
        Chapter chapter = currentChapter();
        Rect canvas = this.layout.canvas();
        frame(graphics, canvas);
        if (chapter == null) {
            if (this.editMode) {
                graphics.centeredText(this.font, Component.translatable("gui.neroquests.editor.no_chapters"),
                        canvas.x() + canvas.w() / 2, canvas.y() + 20, SUBTLE);
            }
            return;
        }
        graphics.enableScissor(canvas.x(), canvas.y(), canvas.right(), canvas.bottom());

        List<Chapter.Entry> entries = chapter.quests();
        boolean editable = this.editMode && chapter.source() == QuestSource.CUSTOM;

        // Dependency lines first, so nodes always sit on top of them. A line is drawn only when both
        // ends are laid out in THIS chapter and both are visible; a prerequisite in another chapter
        // has no position here to draw from.
        int hoveredLineColour = 0xFFFF5252;
        for (Chapter.Entry entry : entries) {
            Quest quest = visibleQuest(entry.quest());
            if (quest == null) {
                continue;
            }
            int[] to = position(entry);
            int toX = to[0] + NODE / 2;
            int toY = to[1] + NODE / 2;
            for (Identifier prerequisite : quest.prerequisites()) {
                Optional<Chapter.Entry> from = chapter.entry(prerequisite);
                if (from.isEmpty() || visibleQuest(prerequisite) == null) {
                    continue;
                }
                int[] start = position(from.get());
                boolean hot = editable && !modal && this.drag == Drag.NONE
                        && GraphGeometry.onLine(mouseX, mouseY, start[0], start[1], to[0], to[1]);
                int colour = hot ? hoveredLineColour
                        : QuestStates.prerequisiteMet(prerequisite, ClientQuestDefinitions.quests(),
                                ClientQuestProgress.ownCompletedQuests(),
                                ClientQuestProgress.serverCompletedQuests()) ? COLOUR_COMPLETED : COLOUR_LOCKED;
                dottedLine(graphics, start[0] + NODE / 2, start[1] + NODE / 2, toX, toY, colour);
            }
        }

        Quest hoveredQuest = null;
        NodeState hoveredState = null;
        boolean hoveredHandle = false;
        for (Chapter.Entry entry : entries) {
            Quest quest = visibleQuest(entry.quest());
            if (quest == null) {
                continue;
            }
            int[] at = position(entry);
            int x = at[0];
            int y = at[1];
            NodeState state = stateOf(quest);
            boolean ghost = this.drag == Drag.NODE && this.dragged && entry.quest().equals(this.dragQuest);
            drawNode(graphics, quest, x, y, state, ghost);
            if (editable) {
                int hx = GraphGeometry.handleX(x);
                int hy = GraphGeometry.handleY(y);
                graphics.fill(hx, hy, hx + GraphGeometry.HANDLE, hy + GraphGeometry.HANDLE, PANEL_EDGE);
                graphics.fill(hx + 1, hy + 1, hx + GraphGeometry.HANDLE - 1, hy + GraphGeometry.HANDLE - 1,
                        COLOUR_AVAILABLE);
            }
            if (!modal && canvas.contains(mouseX, mouseY)) {
                if (editable && GraphGeometry.inHandle(mouseX, mouseY, x, y)) {
                    hoveredQuest = quest;
                    hoveredState = state;
                    hoveredHandle = true;
                } else if (GraphGeometry.inNode(mouseX, mouseY, x, y)) {
                    hoveredQuest = quest;
                    hoveredState = state;
                    hoveredHandle = false;
                }
            }
        }

        if (this.drag == Drag.NODE && this.dragged && this.dragQuest != null) {
            int[] cell = dropCell(mouseX, mouseY);
            int gx = GraphGeometry.nodeX(canvas.x(), this.panX, cell[0]);
            int gy = GraphGeometry.nodeY(canvas.y(), this.panY, cell[1]);
            graphics.fill(gx, gy, gx + NODE, gy + NODE, 0x66B388FF);
            Quest moving = ClientQuestDefinitions.quest(this.dragQuest).orElse(null);
            if (moving != null) {
                graphics.item(QuestBookText.icon(moving.icon()), gx + 3, gy + 3);
            }
        }
        if (this.drag == Drag.LINK && this.dragQuest != null) {
            chapter.entry(this.dragQuest).ifPresent(entry -> {
                int[] from = position(entry);
                dottedLine(graphics, from[0] + NODE / 2, from[1] + NODE / 2, mouseX, mouseY, COLOUR_AVAILABLE);
            });
        }
        graphics.disableScissor();

        if (hoveredQuest != null && hoveredState != null && this.drag == Drag.NONE) {
            List<Component> lines = tooltipFor(hoveredQuest, hoveredState);
            if (this.editMode) {
                lines.add(Component.translatable(hoveredHandle ? "gui.neroquests.editor.node.handle.tooltip"
                        : editable ? "gui.neroquests.editor.node.tooltip"
                        : "gui.neroquests.editor.node.locked.tooltip"));
            }
            graphics.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
        } else if (editable && !modal && this.drag == Drag.NONE && lineAt(mouseX, mouseY) != null) {
            graphics.setComponentTooltipForNextFrame(this.font,
                    List.of(Component.translatable("gui.neroquests.editor.line.tooltip")), mouseX, mouseY);
        }
    }

    /** Screen position of a chapter entry, honouring a node move that has not been confirmed yet. */
    private int[] position(Chapter.Entry entry) {
        int[] pending = this.pendingMoves.get(entry.quest());
        int gx = pending == null ? entry.x() : pending[0];
        int gy = pending == null ? entry.y() : pending[1];
        Rect canvas = this.layout.canvas();
        return new int[] {GraphGeometry.nodeX(canvas.x(), this.panX, gx), GraphGeometry.nodeY(canvas.y(), this.panY, gy)};
    }

    private void drawNode(GuiGraphicsExtractor graphics, Quest quest, int x, int y, NodeState state, boolean ghost) {
        int border = colourOf(state);
        graphics.fill(x, y, x + NODE, y + NODE, ghost ? 0x55FFFFFF : border);
        graphics.fill(x + 1, y + 1, x + NODE - 1, y + NODE - 1,
                state == NodeState.LOCKED ? 0xFF0A0C11 : 0xFF0C1E2B);
        graphics.item(QuestBookText.icon(quest.icon()), x + 3, y + 3);
        if (state == NodeState.COMPLETED) {
            // A small corner pip reads as "done" without needing a check-mark glyph.
            graphics.fill(x + NODE - 6, y + 2, x + NODE - 2, y + 6, COLOUR_COMPLETED);
        }
    }

    /**
     * The read-only quest page (view mode, and the editor's Preview tab): icon and title, state,
     * description, objectives with progress bars, rewards. Returns the drawn height for scrolling.
     */
    int drawDetail(GuiGraphicsExtractor graphics, Quest quest, Rect area, int scroll, boolean backHint) {
        graphics.enableScissor(area.x(), area.y(), area.right(), area.bottom());

        int contentLeft = area.x();
        int contentRight = area.right();
        int wrapWidth = contentRight - contentLeft - 24;
        int startY = area.y() + 6 - scroll;
        int y = startY;

        graphics.item(QuestBookText.icon(quest.icon()), contentLeft + 6, y);
        graphics.text(this.font, trim(QuestText.title(quest), contentRight - contentLeft - 34),
                contentLeft + 28, y + 4, TITLE, false);
        y += 22;

        NodeState state = stateOf(quest);
        graphics.text(this.font, Component.translatable(stateKey(state)),
                contentLeft + 8, y, colourOf(state), false);
        y += 13;
        if (quest.modAbsent()) {
            // B3: shown by the pack's show_when_absent, never completable here.
            graphics.text(this.font, Component.translatable("gui.neroquests.quest_book.mod_absent",
                    quest.id().getNamespace()), contentLeft + 8, y, colourOf(NodeState.LOCKED), false);
            y += 13;
        }
        if (quest.source() == QuestSource.CUSTOM) {
            // Who wrote it, without ever naming anyone (POPIA/GDPR): "you" or "another player".
            graphics.text(this.font, Component.translatable(ClientQuestDefinitions.authoredByMe(quest.id())
                            ? "gui.neroquests.editor.author.you" : "gui.neroquests.editor.author.other"),
                    contentLeft + 8, y, SUBTLE, false);
            y += 13;
        }

        if (!quest.description().isEmpty()) {
            for (FormattedCharSequence line : this.font.split(QuestText.description(quest), Math.max(20, wrapWidth))) {
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
                int countWidth = this.font.width(count);
                graphics.text(this.font, trim(QuestBookText.objective(spec, quest.source()),
                        contentRight - contentLeft - 26 - countWidth), contentLeft + 12, y, TITLE, false);
                graphics.text(this.font, count, contentRight - 10 - countWidth, y, SUBTLE, false);
                y += 11;
                bar(graphics, contentLeft + 12, y, Math.max(10, wrapWidth - 8), 4, current / (float) target,
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
                graphics.text(this.font, trim(Component.translatable("gui.neroquests.quest_book.line",
                        QuestBookText.reward(reward)), contentRight - contentLeft - 16), contentLeft + 12, y, SUBTLE,
                        false);
                y += 11;
            }
        }

        if (backHint) {
            y += 4;
            graphics.text(this.font, Component.translatable("gui.neroquests.quest_book.back"),
                    contentLeft + 8, y, COLOUR_LOCKED, false);
            y += 11;
        }

        graphics.disableScissor();
        return y - startY;
    }

    /** A dotted 2px line — cheaper and clearer than a solid diagonal at GUI scale. */
    static void dottedLine(GuiGraphicsExtractor graphics, int fromX, int fromY, int toX, int toY, int colour) {
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

    private void drawToast(GuiGraphicsExtractor graphics) {
        if (this.toast == null || System.currentTimeMillis() > this.toastUntil) {
            this.toast = null;
            return;
        }
        graphics.nextStratum();
        Rect content = this.layout.content();
        int width = Math.max(40, content.w() - 8);
        List<FormattedCharSequence> lines = this.font.split(this.toast, width - 8);
        int shown = Math.min(3, lines.size());
        int height = shown * this.font.lineHeight + 6;
        int x = content.x() + 4;
        int y = content.bottom() - height - 4;
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, COLOUR_AVAILABLE);
        graphics.fill(x, y, x + width, y + height, INK);
        for (int index = 0; index < shown; index++) {
            graphics.text(this.font, lines.get(index), x + 4, y + 3 + index * this.font.lineHeight, TITLE, false);
        }
    }

    /** {@code text} cut to {@code width} pixels with an ellipsis. */
    Component trim(Component text, int width) {
        if (width <= 0) {
            return Component.empty();
        }
        if (this.font.width(text) <= width) {
            return text;
        }
        String ellipsis = "...";
        String cut = this.font.plainSubstrByWidth(text.getString(), Math.max(0, width - this.font.width(ellipsis)));
        return Component.literal(cut + ellipsis).withStyle(text.getStyle());
    }

    List<Component> tooltipFor(Quest quest, NodeState state) {
        List<Component> lines = new ArrayList<>();
        lines.add(QuestText.title(quest));
        lines.add(Component.translatable(stateKey(state)));
        if (quest.modAbsent()) {
            lines.add(Component.translatable("gui.neroquests.quest_book.mod_absent", quest.id().getNamespace()));
        }
        QuestProgress progress = ClientQuestProgress.progress(quest);
        List<ObjectiveSpec> objectives = quest.objectives();
        for (int index = 0; index < objectives.size(); index++) {
            ObjectiveSpec spec = objectives.get(index);
            int target = Math.max(1, spec.target());
            int current = Mth.clamp(progress.counter(index), 0, target);
            lines.add(Component.translatable("gui.neroquests.quest_book.objective_line",
                    QuestBookText.objective(spec, quest.source()), Component.literal(current + " / " + target)));
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

    // --- state derivation (render only) ------------------------------------------------------------

    /**
     * The quest behind a chapter entry, or {@code null} when it should not be drawn — either it is
     * missing from the synced snapshot, or its {@code visible_gate} is still closed. A hidden quest
     * is not dimmed, it is absent: nothing about it may leak into the book.
     */
    static Quest visibleQuest(Identifier id) {
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
     * The shared rule (AUD-18, {@link QuestStates}): completed, then locked, then in progress, then
     * available — exactly what the engine and the NeroLink snapshot decide. Server-scope quests read
     * from the server-scope progress section ({@link ClientQuestProgress#progress(Quest)} picks the
     * section from the quest's own scope), and each prerequisite resolves by its own scope.
     */
    static NodeState stateOf(Quest quest) {
        QuestProgress progress = ClientQuestProgress.progress(quest);
        return switch (QuestStates.stateOf(quest, ClientQuestDefinitions.quests(),
                ClientQuestProgress.ownCompletedQuests(), ClientQuestProgress.serverCompletedQuests(),
                progress, ClientGates::isOpen)) {
            case COMPLETED -> NodeState.COMPLETED;
            case LOCKED -> NodeState.LOCKED;
            case IN_PROGRESS -> NodeState.IN_PROGRESS;
            case AVAILABLE -> NodeState.AVAILABLE;
        };
    }

    static String stateKey(NodeState state) {
        return switch (state) {
            case COMPLETED -> "gui.neroquests.state.completed";
            case IN_PROGRESS -> "gui.neroquests.state.in_progress";
            case AVAILABLE -> "gui.neroquests.state.available";
            case LOCKED -> "gui.neroquests.state.locked";
        };
    }

    static int colourOf(NodeState state) {
        return switch (state) {
            case COMPLETED -> COLOUR_COMPLETED;
            case IN_PROGRESS -> COLOUR_IN_PROGRESS;
            case AVAILABLE -> COLOUR_AVAILABLE;
            case LOCKED -> COLOUR_LOCKED;
        };
    }

    // --- graph hit-testing -------------------------------------------------------------------------

    /** The visible node under the mouse in the current chapter, or null. */
    private Chapter.Entry nodeAt(double mouseX, double mouseY) {
        Chapter chapter = currentChapter();
        if (chapter == null || !this.layout.canvas().contains(mouseX, mouseY)) {
            return null;
        }
        Chapter.Entry hit = null;
        for (Chapter.Entry entry : chapter.quests()) {
            if (visibleQuest(entry.quest()) == null) {
                continue;
            }
            int[] at = position(entry);
            if (GraphGeometry.inNode(mouseX, mouseY, at[0], at[1])) {
                hit = entry; // the last drawn wins, as it is on top
            }
        }
        return hit;
    }

    /** The visible node whose edge handle is under the mouse, or null. */
    private Chapter.Entry handleAt(double mouseX, double mouseY) {
        Chapter chapter = currentChapter();
        if (chapter == null || !this.layout.canvas().contains(mouseX, mouseY)) {
            return null;
        }
        for (Chapter.Entry entry : chapter.quests()) {
            if (visibleQuest(entry.quest()) == null) {
                continue;
            }
            int[] at = position(entry);
            if (GraphGeometry.inHandle(mouseX, mouseY, at[0], at[1])) {
                return entry;
            }
        }
        return null;
    }

    /** The dependency line under the mouse as {from, to}, or null. */
    private Identifier[] lineAt(double mouseX, double mouseY) {
        Chapter chapter = currentChapter();
        if (chapter == null || !this.layout.canvas().contains(mouseX, mouseY)) {
            return null;
        }
        for (Chapter.Entry entry : chapter.quests()) {
            Quest quest = visibleQuest(entry.quest());
            if (quest == null) {
                continue;
            }
            int[] to = position(entry);
            for (Identifier prerequisite : quest.prerequisites()) {
                Optional<Chapter.Entry> from = chapter.entry(prerequisite);
                if (from.isEmpty() || visibleQuest(prerequisite) == null) {
                    continue;
                }
                int[] start = position(from.get());
                if (GraphGeometry.onLine(mouseX, mouseY, start[0], start[1], to[0], to[1])) {
                    return new Identifier[] {prerequisite, entry.quest()};
                }
            }
        }
        return null;
    }

    /** The grid cell the dragged node would land on, clamped to the server's coordinate bounds. */
    private int[] dropCell(double mouseX, double mouseY) {
        Rect canvas = this.layout.canvas();
        int min = this.editor.limit("coord_min", -512);
        int max = this.editor.limit("coord_max", 512);
        return new int[] {
                GraphGeometry.snap(mouseX - this.dragGrabX, canvas.x(), this.panX, min, max),
                GraphGeometry.snap(mouseY - this.dragGrabY, canvas.y(), this.panY, min, max)};
    }

    /** Every occupied cell of the current chapter. */
    Set<Long> occupiedCells() {
        Set<Long> cells = new HashSet<>();
        Chapter chapter = currentChapter();
        if (chapter != null) {
            for (Chapter.Entry entry : chapter.quests()) {
                cells.add(Long.valueOf(GraphGeometry.key(entry.x(), entry.y())));
            }
        }
        return cells;
    }

    /** Keeps the graph from being dragged entirely out of the viewport (more room in edit mode). */
    private void clampPan() {
        Chapter chapter = currentChapter();
        if (chapter == null) {
            return;
        }
        int minX = 0;
        int minY = 0;
        int maxX = 0;
        int maxY = 0;
        for (Chapter.Entry entry : chapter.quests()) {
            minX = Math.min(minX, entry.x());
            minY = Math.min(minY, entry.y());
            maxX = Math.max(maxX, entry.x());
            maxY = Math.max(maxY, entry.y());
        }
        Rect canvas = this.layout.canvas();
        this.panX = GraphGeometry.clampPan(this.panX, minX, maxX, canvas.w(), this.editMode);
        this.panY = GraphGeometry.clampPan(this.panY, minY, maxY, canvas.h(), this.editMode);
    }

    // --- input -------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        this.pressX = event.x();
        this.pressY = event.y();
        this.dragged = false;
        this.drag = Drag.NONE;
        this.dragQuest = null;
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        setFocused(null);
        if (this.editor.overlayOpen()) {
            this.editor.overlayClicked(event.x(), event.y(), event.button());
            return true;
        }
        if (questPanelOpen()) {
            return true;
        }
        if (this.selectedQuest != null || !this.layout.canvas().contains(event.x(), event.y())) {
            return true;
        }
        Chapter chapter = currentChapter();
        boolean editable = this.editMode && chapter != null && chapter.source() == QuestSource.CUSTOM;
        if (this.editMode && event.button() == 1) {
            Identifier[] line = editable ? lineAt(event.x(), event.y()) : null;
            if (line != null) {
                this.editor.removePrerequisite(line[1], line[0]);
            } else if (!editable && nodeAt(event.x(), event.y()) != null) {
                refuseLocked();
            }
            return true;
        }
        if (event.button() != 0) {
            return true;
        }
        if (this.editMode) {
            Chapter.Entry handle = handleAt(event.x(), event.y());
            Chapter.Entry node = nodeAt(event.x(), event.y());
            if (handle != null && editable) {
                this.drag = Drag.LINK;
                this.dragQuest = handle.quest();
                return true;
            }
            if (node != null) {
                if (editable && QuestSource.of(node.quest()) == QuestSource.CUSTOM) {
                    int[] at = position(node);
                    this.drag = Drag.NODE;
                    this.dragQuest = node.quest();
                    this.dragGrabX = (int) Math.round(event.x()) - at[0];
                    this.dragGrabY = (int) Math.round(event.y()) - at[1];
                } else {
                    this.drag = Drag.LOCKED;
                    this.dragQuest = node.quest();
                }
                return true;
            }
        }
        this.drag = Drag.PAN;
        return true;
    }

    private void refuseLocked() {
        Chapter chapter = currentChapter();
        if (chapter != null) {
            showToast(Component.translatable("gui.neroquests.editor.locked_drag", providerName(chapter)));
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.drag == Drag.NONE) {
            return super.mouseDragged(event, dragX, dragY);
        }
        if (Math.abs(event.x() - this.pressX) > 3.0D || Math.abs(event.y() - this.pressY) > 3.0D) {
            if (!this.dragged && this.drag == Drag.LOCKED) {
                refuseLocked();
            }
            this.dragged = true;
        }
        if (this.drag == Drag.PAN) {
            this.panX += (int) Math.round(dragX);
            this.panY += (int) Math.round(dragY);
            clampPan();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        Drag was = this.drag;
        boolean wasDragged = this.dragged;
        Identifier quest = this.dragQuest;
        this.drag = Drag.NONE;
        this.dragged = false;
        this.dragQuest = null;
        if (was == Drag.NODE && wasDragged && quest != null) {
            int[] cell = dropCell(event.x(), event.y());
            this.editor.moveNode(quest, cell[0], cell[1]);
        } else if (was == Drag.LINK && quest != null) {
            Chapter.Entry target = nodeAt(event.x(), event.y());
            if (target != null && !target.quest().equals(quest)) {
                this.editor.addPrerequisite(target.quest(), quest);
            } else if (!wasDragged) {
                selectQuest(quest);
                rebuildWidgets();
            }
        } else if (!wasDragged && event.button() == 0 && !this.editor.overlayOpen()) {
            // Select on release, and only when the press was not a drag — otherwise every drag that
            // started on a node would also open it.
            handleClick(event.x(), event.y());
        }
        return super.mouseReleased(event);
    }

    private void handleClick(double mouseX, double mouseY) {
        Rect sidebar = this.layout.sidebar();
        for (int row = 0; row < this.layout.tabRows(); row++) {
            int index = this.tabScroll + row;
            if (index >= this.chapters.size()) {
                break;
            }
            int rowY = sidebar.y() + row * BookLayout.TAB_HEIGHT;
            if (mouseX >= sidebar.x() && mouseX < sidebar.right()
                    && mouseY >= rowY && mouseY < rowY + BookLayout.TAB_HEIGHT - 2) {
                selectChapter(this.chapters.get(index).id());
                rebuildWidgets();
                return;
            }
        }
        if (this.selectedQuest != null || !this.layout.canvas().contains(mouseX, mouseY)) {
            return;
        }
        Chapter.Entry node = nodeAt(mouseX, mouseY);
        if (node != null) {
            selectQuest(node.quest());
            this.editor.questOpened(node.quest());
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) Math.signum(scrollY);
        if (step == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (this.editor.overlayOpen() || questPanelOpen()) {
            if (this.editor.scrolled(mouseX, mouseY, step)) {
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (this.layout.sidebar().contains(mouseX, mouseY)) {
            int max = Math.max(0, this.chapters.size() - this.layout.tabRows());
            this.tabScroll = Mth.clamp(this.tabScroll - step, 0, max);
            return true;
        }
        if (this.selectedQuest != null) {
            int max = Math.max(0, this.detailHeight - this.layout.content().h() + 12);
            this.detailScroll = Mth.clamp(this.detailScroll - step * 12, 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Whether a text field has the keyboard (then typed keys belong to it, not to shortcuts). */
    boolean typing() {
        return getFocused() instanceof EditBox box && box.canConsumeInput()
                || getFocused() instanceof MultiLineEditBox;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Escape steps back one level (overlay, editor tab, detail page, edit mode) before closing.
        if (event.isEscape()) {
            if (this.editor.escape()) {
                return true;
            }
            if (this.selectedQuest != null) {
                this.selectedQuest = null;
                this.detailScroll = 0;
                rebuildWidgets();
                return true;
            }
            onClose();
            return true;
        }
        // Enter in a single-line field confirms the form it belongs to (Tab moves between fields).
        if (event.isConfirmation() && getFocused() instanceof EditBox && this.editor.confirm()) {
            return true;
        }
        // The same key that opened the book closes it, like the vanilla inventory — unless typing.
        if (!typing() && QuestBookKeys.OPEN_QUEST_BOOK.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

}
