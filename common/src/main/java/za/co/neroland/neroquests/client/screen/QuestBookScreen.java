package za.co.neroland.neroquests.client.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.LootTableReward;
import za.co.neroland.neroquests.quest.reward.XpReward;

/**
 * The quest book screen: a header with the open chapter and its progress, chapter rows down the left, a
 * pannable, zoomable quest graph on the right and a detail drawer that slides in over the graph for whichever
 * quest you click &mdash; plus, for players the server says may author, an <b>edit mode</b> (E1&ndash;E6)
 * handled by {@link BookEditor}.
 *
 * <p><b>View mode is a pure view.</b> Every value it shows comes from the client mirror caches the
 * server already pushed ({@link ClientQuestDefinitions}, {@link ClientQuestProgress}) plus Core's
 * {@link ClientGates} mirror. It sends nothing to the server, completes nothing and claims nothing. Hidden
 * quests are absent (never dimmed), chapters with nothing visible are not listed in view mode, and an
 * undiscovered prerequisite is only ever "an undiscovered quest".
 *
 * <p><b>Edit mode decides nothing either.</b> The toggle only appears when the last definitions sync
 * said {@code can_author}; every change is a serverbound request the server re-authorises and
 * validates, and the screen learns the outcome from the reply toast and the next sync. When a sync
 * says {@code can_author} is gone, the book drops back to view mode with a toast.
 *
 * <p><b>Drawing.</b> Everything is drawn with {@code GuiGraphicsExtractor} fills, text and item icons in the
 * {@link BookPaint} language (no GUI textures). Geometry comes from {@link BookLayout} (any GUI scale),
 * the camera from {@link GraphView}, line shapes from {@link GraphRouting} and hit-testing from
 * {@link GraphGeometry}. The per-chapter {@link ChapterGraph} is cached and rebuilt only on a sync, a progress
 * change, an unconfirmed node move or the slow gate timer. Graph content is scissor-clipped to the canvas;
 * lines are drawn before nodes, nodes before labels; the drawer, editor overlays and toasts each open a new
 * stratum so they always sit above the graph's items and text.
 *
 * <p>26.x rendering note: screens no longer have {@code render(GuiGraphics, ...)}. Drawing happens
 * in the extract pass, {@code extractRenderState(GuiGraphicsExtractor, ...)}; tooltips are queued
 * with {@code setComponentTooltipForNextFrame} and drawn afterwards by the (final) outer
 * {@code extractRenderStateWithTooltipAndSubtitles}. Input arrives as {@code MouseButtonEvent} /
 * {@code KeyEvent} records rather than loose ints. Widgets are drawn by {@code super.extractRenderState},
 * which therefore runs after the book's own fills.
 */
public class QuestBookScreen extends Screen {

    static final int PANEL = BookPaint.PANEL;
    static final int PANEL_EDGE = BookPaint.PANEL_EDGE;
    static final int TROUGH = BookPaint.TROUGH;
    static final int INK = BookPaint.INK;
    static final int TITLE = BookPaint.TITLE;
    static final int SUBTLE = BookPaint.SUBTLE;
    static final int WARN = BookPaint.WARN;

    static final int COLOUR_AVAILABLE = BookPaint.VIOLET;
    static final int COLOUR_COMPLETED = BookPaint.GREEN;
    static final int COLOUR_IN_PROGRESS = BookPaint.AMBER;
    static final int COLOUR_LOCKED = BookPaint.LOCKED;

    private static final String G = "gui.neroquests.quest_book.";

    /** How long a toast stays up. */
    private static final long TOAST_MS = 4_000L;
    /** Ticks between refreshes of gate-dependent state (Core's gate mirror has no sync counter). */
    private static final int REFRESH_TICKS = 10;
    /** Keyboard pan speed in screen pixels per second. */
    private static final double KEY_PAN_SPEED = 320.0D;
    /** The smallest zoom a chapter opens at; the fit button and the wheel may go further out. */
    private static final double READABLE_ZOOM = 0.75D;
    /** Width of the starfield tile; stars repeat every {@code STAR_TILE} pixels. */
    private static final int STAR_TILE = 512;
    /** Deterministic starfield: x, y, brightness (0..99) per star. */
    private static final int[] STARS = makeStars(96);

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

    /** One chapter row's numbers (visible quests only, so nothing hidden leaks into the counts). */
    private record ChapterStats(int done, int total, Component label) {

        float fraction() {
            return this.total == 0 ? 0.0F : this.done / (float) this.total;
        }
    }

    private final List<Chapter> chapters = new ArrayList<>();
    private final Map<Identifier, ChapterStats> stats = new HashMap<>();
    private final Map<Identifier, ItemStack> iconCache = new HashMap<>();
    private final BookEditor editor = new BookEditor(this);
    private final GraphView view = new GraphView();

    Layout layout = BookLayout.compute(0, 0, false);

    private int selectedChapter;
    private Identifier selectedChapterId;
    private int tabScroll;
    Identifier selectedQuest;

    private ChapterGraph graph;
    private boolean graphDirty = true;
    private int graphPendingHash;
    private int graphProgressGeneration = -1;
    private Identifier fittedChapter;
    private Rect fittedCanvas;
    private Identifier revealQuest;
    private int hoverIndex = -1;
    private int hoverLine = -1;

    private int detailScroll;
    private int detailHeight;
    private Identifier drawerQuest;
    private float drawerProgress;
    private long lastFrameNanos;
    private boolean interacted;

    private boolean panLeft;
    private boolean panRight;
    private boolean panUp;
    private boolean panDown;

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
    private int seenProgress = -1;
    private int ticks;
    private Object structure;

    private Component toast;
    private long toastUntil;

    private List<FormattedCharSequence> hintLines = List.of();
    private int hintWidth = -1;

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
        this.seenProgress = ClientQuestProgress.generation();
        this.structure = structureSignature();
        if (ClientQuestDefinitions.canAuthor()) {
            Rect toggle = this.layout.toggle();
            BookButton button = addRenderableWidget(BookButton.create(
                    Component.translatable(this.editMode ? "gui.neroquests.editor.toggle.done"
                            : "gui.neroquests.editor.toggle.edit"),
                    Component.translatable(this.editMode ? "gui.neroquests.editor.toggle.done.tooltip"
                            : "gui.neroquests.editor.toggle.edit.tooltip"),
                    toggle.x(), toggle.y(), toggle.w(), toggle.h(), this::toggleEditMode));
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
        releasePanKeys();
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
        this.drawerQuest = null;
        this.drawerProgress = 0.0F;
        this.detailScroll = 0;
        this.graphDirty = true;
    }

    void selectQuest(Identifier id) {
        this.selectedQuest = id;
        this.detailScroll = 0;
        if (id != null && !questPanelOpen()) {
            this.drawerQuest = id;
            this.revealQuest = id;
        }
    }

    /** Show a short message at the bottom of the book. */
    void showToast(Component message) {
        this.toast = message;
        this.toastUntil = System.currentTimeMillis() + TOAST_MS;
    }

    // --- definitions / progress changes (E1) -------------------------------------------------------

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
        int progress = ClientQuestProgress.generation();
        this.ticks++;
        if (progress != this.seenProgress || this.ticks % REFRESH_TICKS == 0) {
            // New progress, or gates may have opened: re-count chapters and rebuild the graph's states.
            this.seenProgress = progress;
            refreshChapters();
            this.graphDirty = true;
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
        this.iconCache.clear();
        this.graphDirty = true;
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

    /**
     * Re-reads the chapter list and each chapter's numbers. In view mode a chapter with no visible quest is not
     * listed at all — its title must not leak before its first quest is discovered; edit mode lists every
     * chapter so authors can work on empty ones.
     */
    private void refreshChapters() {
        Chapter before = currentChapter();
        Identifier keep = this.selectedChapterId != null ? this.selectedChapterId
                : before == null ? null : before.id();
        this.chapters.clear();
        this.stats.clear();
        List<Chapter> listed = new ArrayList<>();
        for (Chapter chapter : ClientQuestDefinitions.allChapters()) {
            int total = 0;
            int done = 0;
            for (Chapter.Entry entry : chapter.quests()) {
                Quest quest = visibleQuest(entry.quest());
                if (quest != null) {
                    total++;
                    if (ClientQuestProgress.progress(quest).isComplete()) {
                        done++;
                    }
                }
            }
            if (total == 0 && !this.editMode) {
                continue;
            }
            this.stats.put(chapter.id(), new ChapterStats(done, total, Component.translatable(G + "progress_short",
                    Integer.valueOf(done), Integer.valueOf(total))));
            listed.add(chapter);
        }
        this.chapters.addAll(ChapterOrder.sort(listed,
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

    private ChapterStats statsOf(Chapter chapter) {
        ChapterStats found = chapter == null ? null : this.stats.get(chapter.id());
        return found == null ? new ChapterStats(0, 0, Component.empty()) : found;
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
        if (this.selectedQuest == null) {
            this.drawerQuest = null;
            this.drawerProgress = 0.0F;
        }
        this.graphDirty = true;
        refreshChapters();
        rebuildWidgets();
    }

    /** Whether the quest editor panel (rather than the graph and the read-only drawer) is showing. */
    boolean questPanelOpen() {
        return this.editMode && this.selectedQuest != null && QuestSource.of(this.selectedQuest) == QuestSource.CUSTOM;
    }

    /** Whether the read-only detail drawer is (or should be) open. */
    private boolean drawerOpen() {
        return this.selectedQuest != null && !questPanelOpen();
    }

    private void closeDrawer() {
        this.selectedQuest = null;
        this.detailScroll = 0;
        rebuildWidgets();
    }

    ItemStack icon(Identifier id) {
        return this.iconCache.computeIfAbsent(id, QuestBookText::icon);
    }

    // --- frame update ------------------------------------------------------------------------------

    /** Rebuilds the cached chapter graph when anything it depends on changed, and applies fit / reveal. */
    private void ensureGraph() {
        Rect canvas = this.layout.canvas();
        this.view.setViewport(canvas.x(), canvas.y(), canvas.w(), canvas.h());
        boolean drawer = drawerOpen();
        this.view.setInsetRight(drawer && !this.layout.compact() ? this.layout.drawer().w() : 0);
        Chapter chapter = currentChapter();
        if (chapter == null) {
            this.graph = null;
            this.view.clearBounds();
            return;
        }
        int pendingHash = this.pendingMoves.hashCode() * 31 + this.pendingMoves.size();
        int progress = ClientQuestProgress.generation();
        if (this.graph == null || this.graph.chapter != chapter || this.graphDirty
                || pendingHash != this.graphPendingHash || progress != this.graphProgressGeneration) {
            this.graph = ChapterGraph.build(chapter, this.pendingMoves);
            this.graphDirty = false;
            this.graphPendingHash = pendingHash;
            this.graphProgressGeneration = progress;
            if (this.graph.count == 0) {
                this.view.clearBounds();
            } else {
                this.view.setBounds(this.graph.minX, this.graph.minY, this.graph.maxX, this.graph.maxY,
                        this.editMode ? GraphGeometry.EDIT_SLACK : 0);
            }
        }
        if (!chapter.id().equals(this.fittedChapter) || !canvas.equals(this.fittedCanvas)) {
            this.fittedChapter = chapter.id();
            this.fittedCanvas = canvas;
            // Opening a chapter: fit, but stay readable (a wide chapter starts at its first quest instead).
            this.view.fit(true, READABLE_ZOOM);
        }
        if (this.revealQuest != null) {
            int index = this.graph.indexOf(this.revealQuest);
            if (index >= 0) {
                this.view.ensureVisible(this.graph.worldX[index] - 4, this.graph.worldY[index] - 4,
                        GraphGeometry.NODE + 8, GraphGeometry.NODE + 8, 6);
            }
            this.revealQuest = null;
        }
    }

    /** Keyboard panning, camera easing and the drawer slide. */
    private void updateMotion(double seconds) {
        if (this.panLeft || this.panRight || this.panUp || this.panDown) {
            if (typing() || this.editor.overlayOpen() || questPanelOpen()) {
                releasePanKeys();
            } else {
                double step = KEY_PAN_SPEED * seconds;
                this.view.panBy(((this.panLeft ? 1 : 0) - (this.panRight ? 1 : 0)) * step,
                        ((this.panUp ? 1 : 0) - (this.panDown ? 1 : 0)) * step, false);
                this.interacted = true;
            }
        }
        this.view.tick(seconds);
        boolean wanted = drawerOpen();
        if (wanted) {
            this.drawerQuest = this.selectedQuest;
        }
        float speed = (float) seconds * 7.0F;
        this.drawerProgress = Mth.clamp(this.drawerProgress + (wanted ? speed : -speed), 0.0F, 1.0F);
        if (!wanted && this.drawerProgress <= 0.0F) {
            this.drawerQuest = null;
        }
    }

    private void releasePanKeys() {
        this.panLeft = false;
        this.panRight = false;
        this.panUp = false;
        this.panDown = false;
    }

    // --- rendering ---------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        double seconds = this.lastFrameNanos == 0L ? 0.0D : (now - this.lastFrameNanos) / 1.0E9D;
        this.lastFrameNanos = now;
        ensureGraph();
        updateMotion(seconds);

        // Own dimmer: the book must stay readable regardless of what the vanilla screen background
        // does behind it.
        graphics.fillGradient(0, 0, this.width, this.height, 0xC0040812, 0xE0020409);
        boolean modal = this.editor.overlayOpen();
        drawPanel(graphics);
        drawHeader(graphics);
        Component tabTip = drawTabs(graphics, mouseX, mouseY, modal);

        if (this.chapters.isEmpty() && !this.editMode) {
            drawEmptyBook(graphics);
        } else if (questPanelOpen()) {
            this.editor.drawQuestPanel(graphics, mouseX, mouseY);
        } else {
            if (this.editMode && this.selectedQuest == null) {
                this.editor.drawToolbar(graphics);
            }
            drawGraph(graphics, mouseX, mouseY, modal);
            drawDrawer(graphics, mouseX, mouseY, modal);
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

    /** The shared inset frame (editor panels, modals, the picker). */
    static void frame(GuiGraphicsExtractor graphics, Rect area) {
        BookPaint.frame(graphics, area.x(), area.y(), area.w(), area.h());
    }

    private void drawPanel(GuiGraphicsExtractor graphics) {
        Rect panel = this.layout.panel();
        Rect header = this.layout.header();
        graphics.fill(panel.x() - 3, panel.y() - 2, panel.right() + 3, panel.bottom() + 4, 0x66000000);
        graphics.fill(panel.x() - 1, panel.y() - 1, panel.right() + 1, panel.bottom() + 1, PANEL_EDGE);
        graphics.fillGradient(panel.x(), panel.y(), panel.right(), panel.bottom(), 0xFF101828, 0xFF090E1A);
        graphics.fillGradient(panel.x(), panel.y(), panel.right(), header.bottom(),
                BookPaint.alpha(BookPaint.CYAN, 0x14), BookPaint.alpha(BookPaint.CYAN, 0x00));
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.y() + 1, BookPaint.alpha(0xFFFFFF, 0x26));
        graphics.fill(panel.x() + 1, header.bottom(), panel.right() - 1, header.bottom() + 1, PANEL_EDGE);
        BookPaint.corners(graphics, panel.x() - 1, panel.y() - 1, panel.w() + 2, panel.h() + 2, 7,
                BookPaint.alpha(BookPaint.CYAN, 0xC0));
    }

    private void drawHeader(GuiGraphicsExtractor graphics) {
        Rect panel = this.layout.panel();
        Rect header = this.layout.header();
        int x = panel.x() + this.layout.pad() + 2;
        int right = (ClientQuestDefinitions.canAuthor() ? this.layout.toggle().x() - 8 : panel.right() - this.layout.pad() - 2);
        Chapter chapter = currentChapter();
        ChapterStats numbers = statsOf(chapter);
        Component book = this.editMode ? Component.translatable("gui.neroquests.editor.title") : this.title;
        Component title = chapter == null ? book : QuestText.title(chapter);
        if (this.layout.compact()) {
            int textRight = right;
            if (numbers.total() > 0) {
                Component count = numbers.label();
                int width = this.font.width(count);
                graphics.text(this.font, count, right - width, header.y() + (header.h() - 8) / 2, BookPaint.MUTED, false);
                textRight = right - width - 6;
                int sepW = panel.w() - 2;
                graphics.fill(panel.x() + 1, header.bottom(), panel.x() + 1 + Math.round(sepW * numbers.fraction()),
                        header.bottom() + 1, BookPaint.alpha(COLOUR_COMPLETED, 0xC0));
            }
            graphics.text(this.font, trim(title, textRight - x), x, header.y() + (header.h() - 8) / 2, TITLE, false);
            return;
        }
        int textX = x;
        if (chapter != null) {
            int iconY = header.y() + (header.h() - 16) / 2;
            graphics.item(icon(chapter.icon()), x, iconY);
            textX = x + 21;
        }
        int textRight = right;
        if (chapter != null && numbers.total() > 0) {
            Component count = Component.translatable(G + "chapter_progress",
                    Integer.valueOf(numbers.done()), Integer.valueOf(numbers.total()));
            int width = this.font.width(count);
            int barW = Math.max(64, width);
            graphics.text(this.font, count, right - width, header.y() + 6, SUBTLE, false);
            BookPaint.bar(graphics, right - barW, header.y() + 18, barW, 3, numbers.fraction(),
                    numbers.done() == numbers.total() ? COLOUR_COMPLETED : BookPaint.CYAN);
            textRight = right - barW - 10;
        }
        graphics.text(this.font, trim(title, textRight - textX), textX, header.y() + 6, TITLE, false);
        if (chapter != null) {
            graphics.text(this.font, trim(book, textRight - textX), textX, header.y() + 17, BookPaint.MUTED, false);
        }
        graphics.fill(x, header.bottom(), x + 28, header.bottom() + 1, BookPaint.CYAN);
    }

    /** The chapter column; returns the tooltip for the hovered row. */
    private Component drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean modal) {
        Rect sidebar = this.layout.sidebar();
        Rect list = this.layout.tabList();
        boolean iconOnly = this.layout.iconOnly();
        int rows = this.layout.tabRows();
        Component tip = null;
        graphics.fill(sidebar.x(), sidebar.y(), sidebar.right(), sidebar.bottom(), BookPaint.alpha(INK, 0x60));
        graphics.enableScissor(list.x(), list.y(), list.right(), list.bottom());
        for (int row = 0; row < rows; row++) {
            int index = this.tabScroll + row;
            if (index >= this.chapters.size()) {
                break;
            }
            Chapter chapter = this.chapters.get(index);
            ChapterStats numbers = statsOf(chapter);
            Rect r = this.layout.tabRow(row);
            boolean selected = index == this.selectedChapter;
            boolean hovered = !modal && r.contains(mouseX, mouseY);
            if (selected) {
                graphics.fillGradient(r.x(), r.y(), r.right(), r.bottom(), 0xFF1A2A4C, 0xFF111B33);
                graphics.fill(r.x(), r.y(), r.right(), r.y() + 1, BookPaint.alpha(BookPaint.CYAN, 0x40));
                graphics.fill(r.x(), r.y(), r.x() + 2, r.bottom(), BookPaint.CYAN);
            } else if (hovered) {
                graphics.fill(r.x(), r.y(), r.right(), r.bottom(), BookPaint.alpha(BookPaint.EDGE_HI, 0x48));
                graphics.fill(r.x(), r.y(), r.x() + 1, r.bottom(), BookPaint.alpha(BookPaint.CYAN, 0x90));
            }
            int barColour = numbers.total() > 0 && numbers.done() == numbers.total() ? COLOUR_COMPLETED : BookPaint.CYAN;
            if (iconOnly) {
                graphics.item(icon(chapter.icon()), r.x() + 4, r.y() + 1);
                BookPaint.bar(graphics, r.x() + 4, r.bottom() - 2, r.w() - 8, 1, numbers.fraction(), barColour);
                drawBadges(graphics, chapter, r.right(), r.y(), true);
            } else {
                boolean tall = r.h() >= 22;
                graphics.item(icon(chapter.icon()), r.x() + 5, r.y() + (r.h() - 16) / 2);
                int textX = r.x() + 25;
                int badges = badgeWidth(chapter);
                graphics.text(this.font, trim(QuestText.title(chapter), r.right() - textX - 3 - badges), textX,
                        r.y() + (tall ? 4 : 2), selected || hovered ? TITLE : SUBTLE, false);
                Component count = numbers.label();
                int countW = this.font.width(count);
                int lineY = r.y() + (tall ? 14 : 11);
                graphics.text(this.font, count, r.right() - 3 - countW, lineY, BookPaint.MUTED, false);
                BookPaint.bar(graphics, textX, lineY + 3, Math.max(0, r.right() - 3 - countW - 4 - textX), 2,
                        numbers.fraction(), barColour);
                drawBadges(graphics, chapter, r.right() - 3, r.y() + (tall ? 4 : 2), false);
            }
            if (hovered) {
                tip = chapterTooltip(chapter, iconOnly, numbers);
            }
        }
        graphics.disableScissor();
        if (this.chapters.size() > rows) {
            int trackH = list.h();
            int thumbH = Math.max(8, trackH * rows / this.chapters.size());
            int maxScroll = this.chapters.size() - rows;
            int thumbY = list.y() + (trackH - thumbH) * this.tabScroll / Math.max(1, maxScroll);
            graphics.fill(sidebar.right() - 1, list.y(), sidebar.right(), list.bottom(), BookPaint.alpha(BookPaint.EDGE_HI, 0x50));
            graphics.fill(sidebar.right() - 1, thumbY, sidebar.right(), thumbY + thumbH, BookPaint.CYAN);
        }
        Rect hint = this.layout.hint();
        if (!this.editMode && hint.h() > 0) {
            if (this.hintWidth != hint.w()) {
                this.hintWidth = hint.w();
                this.hintLines = this.font.split(Component.translatable(G + "more_chapters"), Math.max(20, hint.w() - 2));
            }
            int max = Math.max(1, hint.h() / 10);
            graphics.fill(hint.x(), hint.y() - 3, hint.right(), hint.y() - 2, BookPaint.alpha(BookPaint.EDGE_HI, 0x50));
            for (int line = 0; line < Math.min(max, this.hintLines.size()); line++) {
                graphics.text(this.font, this.hintLines.get(line), hint.x() + 1, hint.y() + 1 + line * 10,
                        BookPaint.MUTED, false);
            }
        }
        return tip;
    }

    private int badgeWidth(Chapter chapter) {
        int width = 0;
        if (chapter.modAbsent()) {
            width += 6;
        }
        if (this.editMode || chapter.source() != QuestSource.SPINE) {
            width += 8;
        }
        return width;
    }

    /**
     * Right-aligned badges: pencil (custom), cube (pack; in edit mode a padlock for every provided chapter) and
     * "mod not installed".
     */
    private void drawBadges(GuiGraphicsExtractor graphics, Chapter chapter, int right, int y, boolean iconOnly) {
        int x = iconOnly ? right - 7 : right;
        int by = iconOnly ? y + 12 : y;
        if (chapter.source() == QuestSource.CUSTOM) {
            x -= 6;
            Glyphs.pencil(graphics, x, by, COLOUR_IN_PROGRESS);
            x -= 2;
        } else if (this.editMode) {
            x -= 6;
            Glyphs.lock(graphics, x, by, SUBTLE);
            x -= 2;
        } else if (chapter.source() == QuestSource.PACK && !iconOnly) {
            x -= 6;
            Glyphs.cube(graphics, x, by + 1, BookPaint.MUTED);
            x -= 2;
        }
        if (chapter.modAbsent()) {
            x -= 4;
            Glyphs.warning(graphics, x, by, WARN);
        }
    }

    private Component chapterTooltip(Chapter chapter, boolean iconOnly, ChapterStats numbers) {
        List<Component> lines = new ArrayList<>();
        if (iconOnly) {
            lines.add(QuestText.title(chapter));
            if (numbers.total() > 0) {
                lines.add(Component.translatable(G + "chapter_progress", Integer.valueOf(numbers.done()),
                        Integer.valueOf(numbers.total())));
            }
        }
        if (chapter.modAbsent()) {
            lines.add(Component.translatable("gui.neroquests.editor.chapter.mod_absent", chapter.id().getNamespace()));
        }
        if (chapter.source() == QuestSource.CUSTOM) {
            if (this.editMode) {
                lines.add(Component.translatable("gui.neroquests.editor.chapter.custom"));
            }
        } else if (this.editMode || chapter.source() == QuestSource.PACK) {
            lines.add(Component.translatable("gui.neroquests.editor.chapter.provided_by", providerName(chapter)));
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

    /** No chapters at all (view mode): a friendly centred message instead of an empty graph. */
    private void drawEmptyBook(GuiGraphicsExtractor graphics) {
        Rect content = this.layout.content();
        drawCanvasBackground(graphics, content, false);
        Component message = ClientQuestDefinitions.isEmpty() ? Component.translatable("gui.neroquests.quest_book.empty")
                : Component.translatable(G + "nothing_visible");
        drawCentredMessage(graphics, content, new ItemStack(Items.WRITABLE_BOOK),
                Component.translatable(G + "empty.title"), message);
    }

    /** A centred icon, title and wrapped message (empty states). */
    private void drawCentredMessage(GuiGraphicsExtractor graphics, Rect area, ItemStack icon, Component title,
                                    Component message) {
        List<FormattedCharSequence> lines = this.font.split(message, Math.max(40, Math.min(220, area.w() - 24)));
        int shown = Math.min(lines.size(), Math.max(1, (area.h() - 50) / 10));
        int blockH = 34 + 14 + shown * 10;
        int cx = area.x() + area.w() / 2;
        int y = area.y() + Math.max(4, (area.h() - blockH) / 2);
        boolean big = area.h() >= 100;
        if (big) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(cx - 16, y);
            graphics.pose().scale(2.0F, 2.0F);
            graphics.item(icon, 0, 0);
            graphics.pose().popMatrix();
            y += 38;
        } else {
            graphics.item(icon, cx - 8, y);
            y += 20;
        }
        graphics.centeredText(this.font, title, cx, y, TITLE);
        y += 14;
        for (int index = 0; index < shown; index++) {
            graphics.centeredText(this.font, lines.get(index), cx, y, SUBTLE);
            y += 10;
        }
    }

    // --- graph ---------------------------------------------------------------------------------------

    /** Node side on screen at the current zoom. */
    private int nodeSize() {
        return Math.max(8, (int) Math.round(GraphGeometry.NODE * this.view.zoom()));
    }

    /** Maps the cached graph to the screen for this frame (no allocation). */
    private void layoutNodes() {
        ChapterGraph g = this.graph;
        for (int index = 0; index < g.count; index++) {
            g.screenX[index] = (int) Math.round(this.view.screenX(g.worldX[index]));
            g.screenY[index] = (int) Math.round(this.view.screenY(g.worldY[index]));
        }
        for (int edge = 0; edge < g.edgeCount(); edge++) {
            int[] route = g.routes[edge];
            int[] out = g.screenRoutes[edge];
            for (int k = 0; k < route.length; k += 2) {
                out[k] = (int) Math.round(this.view.screenX(route[k]));
                out[k + 1] = (int) Math.round(this.view.screenY(route[k + 1]));
            }
        }
    }

    /** The canvas backdrop: deep gradient, parallax starfield, dot grid on the layout grid and a vignette. */
    private void drawCanvasBackground(GuiGraphicsExtractor graphics, Rect canvas, boolean grid) {
        graphics.fill(canvas.x() - 1, canvas.y() - 1, canvas.right() + 1, canvas.bottom() + 1, PANEL_EDGE);
        graphics.fillGradient(canvas.x(), canvas.y(), canvas.right(), canvas.bottom(), BookPaint.VOID_TOP,
                BookPaint.VOID_BOTTOM);
        graphics.enableScissor(canvas.x(), canvas.y(), canvas.right(), canvas.bottom());
        double zoom = this.view.zoom();
        int shiftX = (int) Math.floor(-this.view.cameraX() * zoom * 0.2D);
        int shiftY = (int) Math.floor(-this.view.cameraY() * zoom * 0.2D);
        long millis = System.currentTimeMillis();
        for (int star = 0; star < STARS.length; star += 3) {
            int bright = STARS[star + 2];
            int alpha = 0x22 + bright * 0x50 / 100;
            if (bright > 92) {
                // A few stars twinkle, each on its own phase.
                alpha += (int) (0x30 * Math.sin((millis % 4000L) / 4000.0D * Math.PI * 2.0D + star));
            }
            int colour = BookPaint.alpha(bright % 3 == 0 ? 0xBFE9FF : 0xFFFFFF, alpha);
            int size = bright > 96 ? 2 : 1;
            for (int tx = Math.floorMod(STARS[star] + shiftX, STAR_TILE); tx < canvas.w(); tx += STAR_TILE) {
                for (int ty = Math.floorMod(STARS[star + 1] + shiftY, STAR_TILE); ty < canvas.h(); ty += STAR_TILE) {
                    graphics.fill(canvas.x() + tx, canvas.y() + ty, canvas.x() + tx + size, canvas.y() + ty + size, colour);
                }
            }
        }
        if (grid) {
            // Dots sit in the gutters between cells (world k * GRID - 2), so they line up with the layout grid.
            int step = zoom < 0.75D ? 2 * GraphGeometry.GRID : GraphGeometry.GRID;
            int dot = BookPaint.alpha(0x7FA6D6, zoom < 0.75D ? 0x1C : 0x26);
            double firstX = Math.floor((this.view.worldX(canvas.x()) + 2) / step) * step - 2;
            double firstY = Math.floor((this.view.worldY(canvas.y()) + 2) / step) * step - 2;
            for (double wy = firstY; ; wy += step) {
                int sy = (int) Math.round(this.view.screenY(wy));
                if (sy >= canvas.bottom()) {
                    break;
                }
                for (double wx = firstX; ; wx += step) {
                    int sx = (int) Math.round(this.view.screenX(wx));
                    if (sx >= canvas.right()) {
                        break;
                    }
                    graphics.fill(sx, sy, sx + 1, sy + 1, dot);
                }
            }
        }
        for (int side = 0; side < 4; side++) {
            BookPaint.edgeGlow(graphics, canvas.x(), canvas.y(), canvas.w(), canvas.h(), side, 16, 0x000000, 0x90);
        }
        graphics.disableScissor();
    }

    private void drawGraph(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean modal) {
        Chapter chapter = currentChapter();
        Rect canvas = this.layout.canvas();
        boolean hasNodes = chapter != null && this.graph != null && this.graph.count > 0;
        drawCanvasBackground(graphics, canvas, hasNodes);
        this.hoverIndex = -1;
        this.hoverLine = -1;
        if (chapter == null) {
            if (this.editMode) {
                drawCentredMessage(graphics, canvas, new ItemStack(Items.WRITABLE_BOOK),
                        Component.translatable(G + "empty.title"), Component.translatable("gui.neroquests.editor.no_chapters"));
            }
            return;
        }
        if (!hasNodes) {
            drawCentredMessage(graphics, canvas, new ItemStack(Items.SPYGLASS),
                    Component.translatable(G + "chapter_empty.title"), Component.translatable(G + "chapter_empty.hint"));
            return;
        }
        ChapterGraph g = this.graph;
        layoutNodes();
        int size = nodeSize();
        double zoom = this.view.zoom();
        boolean editable = this.editMode && chapter.source() == QuestSource.CUSTOM;
        boolean pointer = !modal && canvas.contains(mouseX, mouseY) && !overDrawer(mouseX, mouseY)
                && zoomButtonAt(mouseX, mouseY) < 0;
        boolean hoveredHandle = false;
        if (pointer && (this.drag == Drag.NONE || !this.dragged)) {
            for (int index = 0; index < g.count; index++) {
                if (editable && !drawerOpen() && GraphGeometry.inHandle(mouseX, mouseY, g.screenX[index], g.screenY[index], size)) {
                    this.hoverIndex = index;
                    hoveredHandle = true;
                } else if (GraphGeometry.inNode(mouseX, mouseY, g.screenX[index], g.screenY[index], size)) {
                    this.hoverIndex = index;
                    hoveredHandle = false;
                }
            }
            if (this.hoverIndex < 0 && editable && !drawerOpen() && this.drag == Drag.NONE) {
                this.hoverLine = lineIndexAt(mouseX, mouseY);
            }
        }
        int selected = drawerOpen() ? g.indexOf(this.selectedQuest) : -1;

        graphics.enableScissor(canvas.x(), canvas.y(), canvas.right(), canvas.bottom());
        int thickness = zoom >= 0.99D ? 2 : 1;
        int arrow = zoom >= 0.99D ? 4 : 3;
        // Lines first so nodes always sit on top of them; the hovered node's lines are redrawn last, in cyan.
        for (int edge = 0; edge < g.edgeCount(); edge++) {
            int colour = edge == this.hoverLine ? BookPaint.DANGER : lineColour(g, edge);
            polyline(graphics, g.screenRoutes[edge], thickness, colour);
            arrowhead(graphics, g.screenRoutes[edge], thickness, arrow, colour);
        }
        int focus = this.hoverIndex >= 0 ? this.hoverIndex : selected;
        if (focus >= 0) {
            for (int edge = 0; edge < g.edgeCount(); edge++) {
                if (g.edgeFrom[edge] == focus || g.edgeTo[edge] == focus) {
                    int colour = g.edgeTo[edge] == focus ? BookPaint.CYAN : BookPaint.mix(BookPaint.CYAN, 0xFFFFFFFF, 0.35F);
                    polyline(graphics, g.screenRoutes[edge], thickness, colour);
                    arrowhead(graphics, g.screenRoutes[edge], thickness, arrow, colour);
                }
            }
        }

        long millis = System.currentTimeMillis();
        float pulse = 0.5F + 0.5F * (float) Math.sin((millis % 1800L) / 1800.0D * Math.PI * 2.0D);
        for (int index = 0; index < g.count; index++) {
            boolean ghost = this.drag == Drag.NODE && this.dragged && g.quests[index].id().equals(this.dragQuest);
            drawNode(graphics, g, index, size, index == this.hoverIndex, index == selected, ghost, pulse);
            if (editable && !drawerOpen()) {
                int hx = GraphGeometry.handleX(g.screenX[index], size);
                int hy = GraphGeometry.handleY(g.screenY[index], size);
                boolean hot = hoveredHandle && index == this.hoverIndex;
                graphics.fill(hx, hy, hx + GraphGeometry.HANDLE, hy + GraphGeometry.HANDLE, hot ? BookPaint.CYAN : BookPaint.EDGE_HI);
                graphics.fill(hx + 1, hy + 1, hx + GraphGeometry.HANDLE - 1, hy + GraphGeometry.HANDLE - 1, COLOUR_AVAILABLE);
            }
        }
        // Labels after every node, so a label is never covered by its neighbour.
        for (int index = 0; index < g.count; index++) {
            boolean emphasised = index == this.hoverIndex || index == selected;
            if (zoom < 0.99D && !emphasised) {
                continue;
            }
            int room = emphasised ? 150 : (int) Math.floor(g.labelRoom[index] * zoom);
            if (room < 18) {
                continue;
            }
            if (g.labelTrimmedFor[index] != room) {
                g.labelTrimmedFor[index] = room;
                g.labels[index] = trim(QuestText.title(g.quests[index]), room - 2);
            }
            Component label = g.labels[index];
            int width = this.font.width(label);
            int cx = g.screenX[index] + size / 2;
            int ly = g.screenY[index] + size + 3;
            if (emphasised) {
                graphics.fill(cx - width / 2 - 3, ly - 2, cx - width / 2 + width + 3, ly + 9, BookPaint.alpha(INK, 0xE0));
                graphics.fill(cx - width / 2 - 3, ly + 9, cx - width / 2 + width + 3, ly + 10,
                        index == selected ? BookPaint.CYAN : colourOf(g.states[index]));
            }
            int colour = emphasised ? TITLE : g.states[index] == NodeState.LOCKED ? BookPaint.MUTED : SUBTLE;
            graphics.text(this.font, label, cx - width / 2, ly, colour, false);
        }

        if (this.drag == Drag.NODE && this.dragged && this.dragQuest != null) {
            int[] cell = dropCell(mouseX, mouseY);
            int gx = (int) Math.round(this.view.screenX(GraphGeometry.world(cell[0])));
            int gy = (int) Math.round(this.view.screenY(GraphGeometry.world(cell[1])));
            graphics.fill(gx - 1, gy - 1, gx + size + 1, gy + size + 1, BookPaint.alpha(BookPaint.CYAN, 0x50));
            graphics.outline(gx - 1, gy - 1, size + 2, size + 2, BookPaint.CYAN);
            Quest moving = ClientQuestDefinitions.quest(this.dragQuest).orElse(null);
            if (moving != null) {
                drawIcon(graphics, icon(moving.icon()), gx, gy, size);
            }
        }
        if (this.drag == Drag.LINK && this.dragQuest != null) {
            int from = g.indexOf(this.dragQuest);
            if (from >= 0) {
                int sx = g.screenX[from] + size;
                int sy = g.screenY[from] + size / 2;
                int t = thickness;
                graphics.fill(Math.min(sx, mouseX), sy - t / 2, Math.max(sx, mouseX) + 1, sy - t / 2 + t, COLOUR_AVAILABLE);
                graphics.fill(mouseX - t / 2, Math.min(sy, mouseY), mouseX - t / 2 + t, Math.max(sy, mouseY) + 1, COLOUR_AVAILABLE);
            }
        }
        graphics.disableScissor();

        drawOverflow(graphics, canvas, millis);
        drawZoomBar(graphics, mouseX, mouseY, modal);
        if (!this.interacted && !this.layout.compact() && this.view.overflow() != 0 && !drawerOpen()) {
            Component hint = Component.translatable(G + "pan_hint");
            graphics.text(this.font, trim(hint, this.layout.zoomBar().x() - canvas.x() - 12), canvas.x() + 6,
                    canvas.bottom() - 12, BookPaint.MUTED, false);
        }

        if (this.hoverIndex >= 0 && (this.drag == Drag.NONE || !this.dragged)) {
            Quest quest = g.quests[this.hoverIndex];
            List<Component> lines = tooltipFor(quest, g.states[this.hoverIndex]);
            if (this.editMode && !drawerOpen()) {
                lines.add(Component.translatable(hoveredHandle ? "gui.neroquests.editor.node.handle.tooltip"
                        : editable ? "gui.neroquests.editor.node.tooltip"
                        : "gui.neroquests.editor.node.locked.tooltip").withColor(BookPaint.MUTED));
            }
            graphics.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
        } else if (this.hoverLine >= 0) {
            graphics.setComponentTooltipForNextFrame(this.font,
                    List.of(Component.translatable("gui.neroquests.editor.line.tooltip")), mouseX, mouseY);
        }
    }

    /** Green when the prerequisite is met, violet when the dependent is open, else dim slate. */
    private static int lineColour(ChapterGraph g, int edge) {
        if (g.edgeMet[edge]) {
            return COLOUR_COMPLETED;
        }
        NodeState target = g.states[g.edgeTo[edge]];
        return target == NodeState.AVAILABLE || target == NodeState.IN_PROGRESS ? COLOUR_AVAILABLE : BookPaint.LINE_IDLE;
    }

    /** A solid orthogonal polyline of {@code t}-pixel thickness with square joins. */
    static void polyline(GuiGraphicsExtractor graphics, int[] points, int t, int colour) {
        int h = t / 2;
        for (int k = 0; k + 3 < points.length; k += 2) {
            int x0 = points[k];
            int y0 = points[k + 1];
            int x1 = points[k + 2];
            int y1 = points[k + 3];
            if (y0 == y1) {
                graphics.fill(Math.min(x0, x1) - h, y0 - h, Math.max(x0, x1) - h + t, y0 - h + t, colour);
            } else {
                graphics.fill(x0 - h, Math.min(y0, y1) - h, x0 - h + t, Math.max(y0, y1) - h + t, colour);
            }
        }
    }

    /** A pixel arrowhead at the polyline's end, its tip touching the dependent's edge. */
    static void arrowhead(GuiGraphicsExtractor graphics, int[] points, int t, int size, int colour) {
        int n = points.length;
        int ex = points[n - 2];
        int ey = points[n - 1];
        int h = t / 2;
        int direction = GraphRouting.arrival(points);
        for (int k = 0; k < size; k++) {
            switch (direction) {
                case GraphRouting.ARRIVE_RIGHT -> graphics.fill(ex - 1 - k, ey - h - k, ex - k, ey - h + t + k, colour);
                case GraphRouting.ARRIVE_LEFT -> graphics.fill(ex + k, ey - h - k, ex + k + 1, ey - h + t + k, colour);
                case GraphRouting.ARRIVE_DOWN -> graphics.fill(ex - h - k, ey - 1 - k, ex - h + t + k, ey - k, colour);
                default -> graphics.fill(ex - h - k, ey + k, ex - h + t + k, ey + k + 1, colour);
            }
        }
    }

    /** An item icon scaled to a node of {@code size} pixels (16 px icon in a 26 px node at 1x). */
    private static void drawIcon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, int size) {
        float scale = size / (float) GraphGeometry.NODE;
        if (Math.abs(scale - 1.0F) < 0.02F) {
            graphics.item(stack, x + (size - 16) / 2, y + (size - 16) / 2);
            return;
        }
        float icon = 16.0F * scale;
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + (size - icon) / 2.0F, y + (size - icon) / 2.0F);
        graphics.pose().scale(scale, scale);
        graphics.item(stack, 0, 0);
        graphics.pose().popMatrix();
    }

    private void drawNode(GuiGraphicsExtractor graphics, ChapterGraph g, int index, int baseSize, boolean hovered,
                          boolean selected, boolean ghost, float pulse) {
        NodeState state = g.states[index];
        Quest quest = g.quests[index];
        int size = hovered ? baseSize + 2 : baseSize;
        int x = hovered ? g.screenX[index] - 1 : g.screenX[index];
        int y = hovered ? g.screenY[index] - 1 : g.screenY[index];
        int accent = colourOf(state);
        boolean small = baseSize < 18;
        if (state == NodeState.AVAILABLE && !ghost) {
            int glow = (int) (0x28 + 0x50 * pulse);
            graphics.fill(x - 3, y - 3, x + size + 3, y + size + 3, BookPaint.alpha(COLOUR_AVAILABLE, glow / 3));
            graphics.fill(x - 2, y - 2, x + size + 2, y + size + 2, BookPaint.alpha(COLOUR_AVAILABLE, glow / 2));
            graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, BookPaint.alpha(COLOUR_AVAILABLE, glow));
        }
        if (selected) {
            graphics.outline(x - 3, y - 3, size + 6, size + 6, BookPaint.CYAN);
            BookPaint.corners(graphics, x - 5, y - 5, size + 10, size + 10, 4, BookPaint.CYAN);
        }
        int edge = state == NodeState.LOCKED ? 0xFF34405A : accent;
        if (hovered) {
            edge = BookPaint.mix(edge, 0xFFFFFFFF, 0.35F);
        }
        int top = state == NodeState.LOCKED ? 0xFF0D1220 : BookPaint.mix(0xFF111B2E, accent, 0.16F);
        int bottom = state == NodeState.LOCKED ? 0xFF080C15 : 0xFF0A1120;
        BookPaint.bevel(graphics, x, y, size, size, edge, top, bottom);
        if (state == NodeState.COMPLETED || state == NodeState.IN_PROGRESS) {
            // An inner accent ring makes finished / running quests read at a glance.
            graphics.outline(x + 2, y + 2, size - 4, size - 4, BookPaint.alpha(accent, 0x55));
        }
        drawIcon(graphics, icon(quest.icon()), x, y, size);
        if (state == NodeState.LOCKED) {
            graphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, 0xA0060A13);
        }
        if (ghost) {
            graphics.fill(x, y, x + size, y + size, 0xB0060A13);
        }
        if (state == NodeState.IN_PROGRESS) {
            int inset = small ? 2 : 3;
            BookPaint.bar(graphics, x + inset, y + size - inset - 2, size - 2 * inset, 2, g.progress[index], COLOUR_IN_PROGRESS);
        }
        if (state == NodeState.COMPLETED) {
            if (small) {
                graphics.fill(x + size - 4, y - 1, x + size + 1, y + 4, COLOUR_COMPLETED);
            } else {
                graphics.fill(x + size - 6, y - 3, x + size + 4, y + 5, INK);
                graphics.fill(x + size - 5, y - 2, x + size + 3, y + 4, COLOUR_COMPLETED);
                Glyphs.check(graphics, x + size - 4, y - 2, INK);
            }
        } else if (state == NodeState.LOCKED && !small) {
            graphics.fill(x + size - 6, y + size - 7, x + size + 2, y + size + 3, INK);
            graphics.fill(x + size - 5, y + size - 6, x + size + 1, y + size + 2, 0xFF1A2236);
            Glyphs.lock(graphics, x + size - 5, y + size - 6, 0xFF9AA7BE);
        }
        if (quest.modAbsent()) {
            graphics.fill(x - 3, y - 3, x + 4, y + 7, INK);
            graphics.fill(x - 2, y - 2, x + 3, y + 6, WARN);
            Glyphs.warning(graphics, x - 2, y - 2, INK);
        }
    }

    /** Edge glows and chevrons on every side the graph continues past. */
    private void drawOverflow(GuiGraphicsExtractor graphics, Rect canvas, long millis) {
        int flags = this.view.overflow();
        if (flags == 0) {
            return;
        }
        float pulse = 0.5F + 0.5F * (float) Math.sin((millis % 2200L) / 2200.0D * Math.PI * 2.0D);
        int glow = (int) (0x50 + 0x30 * pulse);
        int chevron = BookPaint.alpha(BookPaint.CYAN, (int) (0xA0 + 0x50 * pulse));
        int right = canvas.right() - this.view.insetRight();
        int w = right - canvas.x();
        int midX = canvas.x() + w / 2;
        int midY = canvas.y() + canvas.h() / 2;
        if ((flags & GraphView.LEFT) != 0) {
            BookPaint.edgeGlow(graphics, canvas.x(), canvas.y(), w, canvas.h(), 0, 12, BookPaint.CYAN, glow);
            Glyphs.chevron(graphics, canvas.x() + 3, midY - 3, 0, chevron);
        }
        if ((flags & GraphView.RIGHT) != 0) {
            BookPaint.edgeGlow(graphics, canvas.x(), canvas.y(), w, canvas.h(), 1, 12, BookPaint.CYAN, glow);
            Glyphs.chevron(graphics, right - 7, midY - 3, 1, chevron);
        }
        if ((flags & GraphView.UP) != 0) {
            BookPaint.edgeGlow(graphics, canvas.x(), canvas.y(), w, canvas.h(), 2, 12, BookPaint.CYAN, glow);
            Glyphs.chevron(graphics, midX - 3, canvas.y() + 3, 2, chevron);
        }
        if ((flags & GraphView.DOWN) != 0) {
            BookPaint.edgeGlow(graphics, canvas.x(), canvas.y(), w, canvas.h(), 3, 12, BookPaint.CYAN, glow);
            Glyphs.chevron(graphics, midX - 3, canvas.bottom() - 7, 3, chevron);
        }
    }

    /** The zoom bar, moved left of the drawer while it is open (hidden behind a full-width drawer). */
    private Rect zoomButtonRect(int index) {
        if (this.graph == null || this.graph.count == 0 || currentChapter() == null) {
            return Rect.EMPTY;
        }
        Rect button = this.layout.zoomButton(index);
        if (this.drawerQuest != null && this.drawerProgress > 0.0F) {
            if (this.layout.compact()) {
                return Rect.EMPTY;
            }
            int shift = Math.round(this.layout.drawer().w() * easeOut(this.drawerProgress));
            return new Rect(button.x() - shift, button.y(), button.w(), button.h());
        }
        return button;
    }

    private int zoomButtonAt(double mouseX, double mouseY) {
        for (int index = 0; index < 3; index++) {
            if (zoomButtonRect(index).contains(mouseX, mouseY)) {
                return index;
            }
        }
        return -1;
    }

    private void drawZoomBar(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean modal) {
        int hovered = modal ? -1 : zoomButtonAt(mouseX, mouseY);
        double zoom = this.view.targetZoom();
        for (int index = 0; index < 3; index++) {
            Rect r = zoomButtonRect(index);
            if (r.w() == 0) {
                return;
            }
            boolean enabled = index == 2 || (index == 0 ? zoom > GraphView.ZOOM_STEPS[0] + 1e-6
                    : zoom < GraphView.ZOOM_STEPS[GraphView.ZOOM_STEPS.length - 1] - 1e-6);
            boolean hot = enabled && index == hovered;
            BookPaint.bevel(graphics, r.x(), r.y(), r.w(), r.h(), hot ? BookPaint.CYAN : BookPaint.EDGE_HI,
                    hot ? 0xFF1B2B45 : BookPaint.PANEL_HI, BookPaint.PANEL);
            int colour = !enabled ? BookPaint.MUTED : hot ? TITLE : SUBTLE;
            int cx = r.x() + r.w() / 2;
            int cy = r.y() + r.h() / 2;
            switch (index) {
                case 0 -> Glyphs.minus(graphics, cx - 2, cy - 2, colour);
                case 1 -> Glyphs.plus(graphics, cx - 2, cy - 2, colour);
                default -> Glyphs.fit(graphics, cx - 3, cy - 3, colour);
            }
            if (hot) {
                String key = index == 0 ? "zoom_out" : index == 1 ? "zoom_in" : "fit";
                graphics.setComponentTooltipForNextFrame(this.font, List.of(Component.translatable(G + key)), mouseX, mouseY);
            }
        }
        if (!this.layout.compact()) {
            Rect first = zoomButtonRect(0);
            Component percent = Component.translatable(G + "zoom", Integer.valueOf((int) Math.round(zoom * 100)));
            graphics.text(this.font, percent, first.x() - 4 - this.font.width(percent), first.y() + (first.h() - 8) / 2,
                    BookPaint.MUTED, false);
        }
    }

    private void pressZoom(int index) {
        Rect canvas = this.layout.canvas();
        double cx = canvas.x() + (canvas.w() - this.view.insetRight()) / 2.0D;
        double cy = canvas.y() + canvas.h() / 2.0D;
        switch (index) {
            case 0 -> this.view.zoomBy(-1, cx, cy);
            case 1 -> this.view.zoomBy(1, cx, cy);
            default -> this.view.fit(false);
        }
        this.interacted = true;
    }

    // --- detail drawer -------------------------------------------------------------------------------

    private static float easeOut(float t) {
        float inverse = 1.0F - t;
        return 1.0F - inverse * inverse * inverse;
    }

    /** The drawer's current left edge (it slides in from the canvas's right edge). */
    private int drawerX() {
        Rect drawer = this.layout.drawer();
        return drawer.x() + Math.round((1.0F - easeOut(this.drawerProgress)) * (drawer.w() + 8));
    }

    private boolean overDrawer(double mouseX, double mouseY) {
        if (this.drawerQuest == null || this.drawerProgress <= 0.0F) {
            return false;
        }
        Rect drawer = this.layout.drawer();
        return mouseX >= drawerX() && mouseX < drawer.right() && mouseY >= drawer.y() && mouseY < drawer.bottom();
    }

    private Rect drawerClose() {
        Rect drawer = this.layout.drawer();
        return new Rect(drawerX() + drawer.w() - 16, drawer.y() + 4, 12, 12);
    }

    /** The drawer's scrolling content area. */
    private Rect drawerContent() {
        Rect drawer = this.layout.drawer();
        return new Rect(drawerX() + 6, drawer.y() + 4, Math.max(20, drawer.w() - 6 - 20), Math.max(10, drawer.h() - 8));
    }

    private void drawDrawer(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean modal) {
        if (this.drawerQuest == null || this.drawerProgress <= 0.0F) {
            return;
        }
        Quest quest = ClientQuestDefinitions.quest(this.drawerQuest).orElse(null);
        if (quest == null) {
            this.drawerQuest = null;
            return;
        }
        Rect canvas = this.layout.canvas();
        Rect drawer = this.layout.drawer();
        float eased = easeOut(this.drawerProgress);
        int x = drawerX();
        // A new stratum: the drawer always covers the graph's items and labels.
        graphics.nextStratum();
        graphics.enableScissor(canvas.x(), canvas.y(), canvas.right(), canvas.bottom());
        graphics.fill(canvas.x(), canvas.y(), x, canvas.bottom(), BookPaint.alpha(INK, (int) (0x58 * eased)));
        BookPaint.shadowLeft(graphics, x, drawer.y(), drawer.h(), 6);
        graphics.fill(x, drawer.y(), drawer.right(), drawer.bottom(), PANEL_EDGE);
        graphics.fillGradient(x + 1, drawer.y(), drawer.right(), drawer.bottom(), 0xFF131C30, 0xFF0B111F);
        NodeState state = cachedState(quest);
        graphics.fill(x, drawer.y(), x + 2, drawer.bottom(), colourOf(state));
        graphics.fill(x + 2, drawer.y(), drawer.right(), drawer.y() + 1, BookPaint.alpha(0xFFFFFF, 0x20));

        Rect content = drawerContent();
        boolean pointer = !modal && overDrawer(mouseX, mouseY);
        this.detailHeight = drawDetail(graphics, quest, content, this.detailScroll, pointer ? mouseX : -1,
                pointer ? mouseY : -1, true, false);
        int max = Math.max(0, this.detailHeight - content.h());
        if (this.detailScroll > max) {
            this.detailScroll = max;
        }
        if (max > 0) {
            int trackX = drawer.right() - 4;
            int trackTop = drawer.y() + 20;
            int trackH = Math.max(10, drawer.bottom() - 4 - trackTop);
            int thumbH = Math.max(10, trackH * content.h() / Math.max(1, this.detailHeight));
            int thumbY = trackTop + (trackH - thumbH) * this.detailScroll / max;
            graphics.fill(trackX, trackTop, trackX + 2, trackTop + trackH, BookPaint.alpha(BookPaint.EDGE_HI, 0x50));
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, BookPaint.CYAN);
        }
        Rect close = drawerClose();
        boolean hot = pointer && close.contains(mouseX, mouseY);
        BookPaint.bevel(graphics, close.x(), close.y(), close.w(), close.h(), hot ? BookPaint.CYAN : BookPaint.EDGE_HI,
                hot ? 0xFF1B2B45 : BookPaint.PANEL_HI, BookPaint.PANEL);
        Glyphs.cross(graphics, close.x() + 3, close.y() + 3, hot ? TITLE : SUBTLE);
        graphics.disableScissor();
        if (hot) {
            graphics.setComponentTooltipForNextFrame(this.font, List.of(Component.translatable(G + "close")), mouseX, mouseY);
        }
    }

    /**
     * The read-only quest page (the view-mode drawer, and the editor's Preview tab): icon, title and state chip,
     * description, objectives with progress bars, rewards as item slots and chips, and prerequisites. Returns the
     * drawn height for scrolling.
     */
    int drawDetail(GuiGraphicsExtractor graphics, Quest quest, Rect area, int scroll, boolean backHint) {
        return drawDetail(graphics, quest, area, scroll, -1, -1, false, backHint);
    }

    private int drawDetail(GuiGraphicsExtractor graphics, Quest quest, Rect area, int scroll, int mouseX, int mouseY,
                           boolean tips, boolean backHint) {
        graphics.enableScissor(area.x(), area.y(), area.right(), area.bottom());
        boolean pointer = tips && area.contains(mouseX, mouseY);
        int left = area.x() + 4;
        int right = area.right() - 4;
        int width = Math.max(20, right - left);
        int startY = area.y() + 4 - scroll;
        int y = startY;

        NodeState state = cachedState(quest);
        int accent = colourOf(state);
        BookPaint.bevel(graphics, left, y, 24, 24, accent, BookPaint.mix(0xFF111B2E, accent, 0.18F), 0xFF0A1120);
        graphics.item(icon(quest.icon()), left + 4, y + 4);
        int textX = left + 30;
        int textW = Math.max(20, right - textX);
        List<FormattedCharSequence> title = this.font.split(QuestText.title(quest), textW);
        int titleLines = Math.min(2, title.size());
        for (int line = 0; line < titleLines; line++) {
            graphics.text(this.font, title.get(line), textX, y + 1 + line * 10, TITLE, false);
        }
        int chipY = y + 2 + titleLines * 10;
        BookPaint.chip(graphics, this.font, Component.translatable(stateKey(state)), textX, chipY, accent, textW);
        y = Math.max(y + 28, chipY + 15);

        if (quest.modAbsent()) {
            // B3: shown by the pack's show_when_absent, never completable here.
            Glyphs.warning(graphics, left, y + 1, WARN);
            y = paragraph(graphics, Component.translatable("gui.neroquests.quest_book.mod_absent",
                    quest.id().getNamespace()), left + 8, y, width - 8, WARN, 3) + 3;
        }
        if (quest.source() == QuestSource.CUSTOM) {
            // Who wrote it, without ever naming anyone (POPIA/GDPR): "you" or "another player".
            Glyphs.pencil(graphics, left, y + 1, COLOUR_IN_PROGRESS);
            graphics.text(this.font, trim(Component.translatable(ClientQuestDefinitions.authoredByMe(quest.id())
                    ? "gui.neroquests.editor.author.you" : "gui.neroquests.editor.author.other"), width - 10),
                    left + 10, y, SUBTLE, false);
            y += 12;
            Component repeat = repeatText(quest);
            if (repeat != null) {
                BookPaint.chip(graphics, this.font, repeat, left, y, BookPaint.CYAN, width);
                y += 15;
            }
        }

        if (!quest.description().isEmpty()) {
            graphics.fill(left, y, right, y + 1, BookPaint.alpha(BookPaint.EDGE_HI, 0x60));
            y += 5;
            y = paragraph(graphics, QuestText.description(quest), left, y, width, SUBTLE, Integer.MAX_VALUE) + 4;
        }

        QuestProgress progress = ClientQuestProgress.progress(quest);
        List<ObjectiveSpec> objectives = quest.objectives();
        if (!objectives.isEmpty()) {
            int finished = 0;
            for (int index = 0; index < objectives.size(); index++) {
                if (progress.isComplete() || progress.counter(index) >= Math.max(1, objectives.get(index).target())) {
                    finished++;
                }
            }
            y = section(graphics, Component.translatable("gui.neroquests.quest_book.objectives"),
                    Component.translatable(G + "progress_short", Integer.valueOf(finished), Integer.valueOf(objectives.size())),
                    left, right, y);
            for (int index = 0; index < objectives.size(); index++) {
                ObjectiveSpec spec = objectives.get(index);
                int target = Math.max(1, spec.target());
                int current = progress.isComplete() ? target : Mth.clamp(progress.counter(index), 0, target);
                boolean done = current >= target;
                ItemStack stack = QuestBookText.objectiveIcon(spec);
                if (stack.isEmpty()) {
                    Glyphs.diamond(graphics, left + 6, y + 6, done ? COLOUR_COMPLETED : accent);
                } else {
                    graphics.item(stack, left, y);
                }
                Component count = Component.literal(current + " / " + target);
                int countW = this.font.width(count);
                int labelX = left + 20;
                int labelW = right - labelX - countW - (done ? 13 : 4);
                Component label = QuestBookText.objective(spec, quest.source());
                Component shown = trim(label, labelW);
                graphics.text(this.font, shown, labelX, y + 1, done ? SUBTLE : TITLE, false);
                if (done) {
                    Glyphs.check(graphics, right - countW - 10, y + 2, COLOUR_COMPLETED);
                }
                graphics.text(this.font, count, right - countW, y + 1, done ? COLOUR_COMPLETED : SUBTLE, false);
                int barColour = done ? COLOUR_COMPLETED : state == NodeState.LOCKED ? COLOUR_LOCKED : COLOUR_IN_PROGRESS;
                BookPaint.bar(graphics, labelX, y + 12, right - labelX, 3, current / (float) target, barColour);
                if (pointer && shown != label && mouseX >= labelX && mouseX < right && mouseY >= y && mouseY < y + 16) {
                    graphics.setComponentTooltipForNextFrame(this.font, List.of(label), mouseX, mouseY);
                }
                y += 19;
            }
            y += 2;
        }

        List<RewardSpec> rewards = quest.rewards();
        if (!rewards.isEmpty()) {
            y = section(graphics, Component.translatable("gui.neroquests.quest_book.rewards"), null, left, right, y);
            int slotX = left;
            boolean anySlot = false;
            for (RewardSpec reward : rewards) {
                ItemStack stack = QuestBookText.rewardStack(reward);
                if (stack.isEmpty()) {
                    continue;
                }
                if (slotX + 20 > right) {
                    slotX = left;
                    y += 22;
                }
                graphics.fill(slotX, y, slotX + 20, y + 20, BookPaint.EDGE_HI);
                graphics.fillGradient(slotX + 1, y + 1, slotX + 19, y + 19, 0xFF0A101C, 0xFF111A2C);
                graphics.item(stack, slotX + 2, y + 2);
                graphics.itemDecorations(this.font, stack, slotX + 2, y + 2);
                if (pointer && mouseX >= slotX && mouseX < slotX + 20 && mouseY >= y && mouseY < y + 20) {
                    graphics.fill(slotX + 1, y + 1, slotX + 19, y + 19, BookPaint.alpha(0xFFFFFF, 0x30));
                    graphics.setTooltipForNextFrame(this.font, stack, mouseX, mouseY);
                }
                slotX += 22;
                anySlot = true;
            }
            if (anySlot) {
                y += 24;
            }
            int chipX = left;
            boolean anyChip = false;
            for (RewardSpec reward : rewards) {
                if (!QuestBookText.rewardStack(reward).isEmpty()) {
                    continue;
                }
                Component text = QuestBookText.reward(reward);
                int colour = reward instanceof XpReward ? COLOUR_COMPLETED
                        : reward instanceof CurrencyReward ? COLOUR_IN_PROGRESS
                        : reward instanceof LootTableReward ? COLOUR_AVAILABLE : BookPaint.CYAN;
                int chipW = Math.min(width, this.font.width(text) + 8);
                if (chipX > left && chipX + chipW > right) {
                    chipX = left;
                    y += 14;
                }
                int drawn = BookPaint.chip(graphics, this.font, text, chipX, y, colour, right - chipX);
                if (pointer && drawn < this.font.width(text) + 8 && mouseX >= chipX && mouseX < chipX + drawn
                        && mouseY >= y && mouseY < y + 11) {
                    graphics.setComponentTooltipForNextFrame(this.font, List.of(text), mouseX, mouseY);
                }
                chipX += drawn + 3;
                anyChip = true;
            }
            if (anyChip) {
                y += 15;
            }
            y += 2;
        }

        List<Identifier> prerequisites = quest.prerequisites();
        if (!prerequisites.isEmpty()) {
            y = section(graphics, Component.translatable(G + "prerequisites"), null, left, right, y);
            for (Identifier id : prerequisites) {
                Quest required = visibleQuest(id);
                if (required == null) {
                    // Hidden or not sent: never its title, icon or id.
                    Glyphs.question(graphics, left + 5, y + 4, BookPaint.MUTED);
                    graphics.text(this.font, trim(QuestBookText.questName(id), width - 20), left + 20, y + 4,
                            BookPaint.MUTED, false);
                } else {
                    NodeState requiredState = cachedState(required);
                    graphics.item(icon(required.icon()), left, y);
                    graphics.text(this.font, trim(QuestText.title(required), width - 30), left + 20, y + 4,
                            requiredState == NodeState.COMPLETED ? SUBTLE : TITLE, false);
                    graphics.fill(right - 5, y + 6, right - 1, y + 10, colourOf(requiredState));
                }
                y += 18;
            }
        }

        if (backHint) {
            y += 4;
            graphics.text(this.font, Component.translatable("gui.neroquests.quest_book.back"), left, y,
                    COLOUR_LOCKED, false);
            y += 11;
        }

        graphics.disableScissor();
        return y - startY + 4;
    }

    /** A section heading: label in cyan, an optional count on the right and a hairline between. */
    private int section(GuiGraphicsExtractor graphics, Component label, Component count, int left, int right, int y) {
        graphics.text(this.font, label, left, y, BookPaint.CYAN, false);
        int labelEnd = left + this.font.width(label) + 4;
        int lineEnd = right;
        if (count != null) {
            int countW = this.font.width(count);
            graphics.text(this.font, count, right - countW, y, BookPaint.MUTED, false);
            lineEnd = right - countW - 4;
        }
        if (lineEnd > labelEnd) {
            graphics.fill(labelEnd, y + 4, lineEnd, y + 5, BookPaint.alpha(BookPaint.CYAN, 0x40));
        }
        return y + 13;
    }

    /** Wrapped text, at most {@code maxLines} lines; returns the y under it. */
    private int paragraph(GuiGraphicsExtractor graphics, Component text, int x, int y, int width, int colour, int maxLines) {
        List<FormattedCharSequence> lines = this.font.split(text, Math.max(20, width));
        int shown = Math.min(maxLines, lines.size());
        for (int index = 0; index < shown; index++) {
            graphics.text(this.font, lines.get(index), x, y, colour, false);
            y += this.font.lineHeight;
        }
        return y;
    }

    /** "Repeats after ..." for a repeatable custom quest, else null. */
    private static Component repeatText(Quest quest) {
        try {
            JsonObject extra = ClientQuestDefinitions.customExtra(quest.id());
            if (!extra.has("repeatable") || !extra.get("repeatable").getAsBoolean()) {
                return null;
            }
            long seconds = extra.has("cooldown_seconds") ? extra.get("cooldown_seconds").getAsLong() : 0L;
            long[] parts = EditorForms.splitDuration(Math.max(0L, seconds));
            return Component.translatable("gui.neroquests.editor.details.cooldown.human",
                    Long.valueOf(parts[0]), Long.valueOf(parts[1]), Long.valueOf(parts[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void drawToast(GuiGraphicsExtractor graphics) {
        if (this.toast == null || System.currentTimeMillis() > this.toastUntil) {
            this.toast = null;
            return;
        }
        graphics.nextStratum();
        Rect content = this.layout.content();
        int width = Math.max(40, Math.min(320, content.w() - 8));
        List<FormattedCharSequence> lines = this.font.split(this.toast, width - 12);
        int shown = Math.min(3, lines.size());
        int height = shown * this.font.lineHeight + 7;
        int x = content.x() + (content.w() - width) / 2;
        int y = content.bottom() - height - 6;
        graphics.fill(x - 2, y - 1, x + width + 2, y + height + 3, 0x66000000);
        BookPaint.bevel(graphics, x, y, width, height, BookPaint.CYAN, 0xFF16223A, 0xFF0C1322);
        graphics.fill(x + 1, y + 1, x + 3, y + height - 1, BookPaint.CYAN);
        for (int index = 0; index < shown; index++) {
            graphics.text(this.font, lines.get(index), x + 7, y + 4 + index * this.font.lineHeight, TITLE, false);
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

    /** The node tooltip: title, state, a short objective summary and a hint. */
    List<Component> tooltipFor(Quest quest, NodeState state) {
        List<Component> lines = new ArrayList<>();
        lines.add(QuestText.title(quest).copy().withColor(TITLE));
        lines.add(Component.translatable(stateKey(state)).withColor(colourOf(state)));
        if (quest.modAbsent()) {
            lines.add(Component.translatable("gui.neroquests.quest_book.mod_absent", quest.id().getNamespace())
                    .withColor(WARN));
        }
        QuestProgress progress = ClientQuestProgress.progress(quest);
        List<ObjectiveSpec> objectives = quest.objectives();
        int shown = 0;
        int hidden = 0;
        for (int index = 0; index < objectives.size(); index++) {
            ObjectiveSpec spec = objectives.get(index);
            int target = Math.max(1, spec.target());
            int current = progress.isComplete() ? target : Mth.clamp(progress.counter(index), 0, target);
            if (shown >= 3) {
                hidden++;
                continue;
            }
            lines.add(Component.translatable("gui.neroquests.quest_book.line",
                    Component.translatable("gui.neroquests.quest_book.objective_line",
                            QuestBookText.objective(spec, quest.source()), Component.literal(current + " / " + target)))
                    .withColor(current >= target ? COLOUR_COMPLETED : SUBTLE));
            shown++;
        }
        if (hidden > 0) {
            lines.add(Component.translatable(G + "more_objectives", Integer.valueOf(hidden)).withColor(BookPaint.MUTED));
        }
        if (!this.editMode && !drawerOpen()) {
            lines.add(Component.translatable(G + "click_details").withColor(BookPaint.MUTED));
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

    /** {@link #stateOf} from the cached chapter graph when the quest is drawn there (no per-frame allocation). */
    private NodeState cachedState(Quest quest) {
        if (this.graph != null) {
            int index = this.graph.indexOf(quest.id());
            if (index >= 0 && this.graph.quests[index] == quest) {
                return this.graph.states[index];
            }
        }
        return stateOf(quest);
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

    private static int[] makeStars(int count) {
        int[] stars = new int[count * 3];
        long seed = 0x4E45524FL;
        for (int star = 0; star < count; star++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            stars[star * 3] = (int) ((seed >>> 33) % STAR_TILE);
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            stars[star * 3 + 1] = (int) ((seed >>> 33) % STAR_TILE);
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            stars[star * 3 + 2] = (int) ((seed >>> 33) % 100);
        }
        return stars;
    }

    // --- graph hit-testing (positions from the last drawn frame) -------------------------------------

    private boolean graphReady() {
        return this.graph != null && this.graph.count > 0 && this.graph.chapter == currentChapter();
    }

    /** Whether the point is on the visible graph (inside the canvas, not under the drawer or the zoom bar). */
    private boolean onGraph(double mouseX, double mouseY) {
        return this.layout.canvas().contains(mouseX, mouseY) && !overDrawer(mouseX, mouseY)
                && zoomButtonAt(mouseX, mouseY) < 0;
    }

    /** The visible node under the mouse, or -1 (the last drawn wins, as it is on top). */
    private int nodeIndexAt(double mouseX, double mouseY) {
        if (!graphReady() || !onGraph(mouseX, mouseY)) {
            return -1;
        }
        int size = nodeSize();
        int hit = -1;
        for (int index = 0; index < this.graph.count; index++) {
            if (GraphGeometry.inNode(mouseX, mouseY, this.graph.screenX[index], this.graph.screenY[index], size)) {
                hit = index;
            }
        }
        return hit;
    }

    /** The visible node whose edge handle is under the mouse, or -1. */
    private int handleIndexAt(double mouseX, double mouseY) {
        if (!graphReady() || !onGraph(mouseX, mouseY)) {
            return -1;
        }
        int size = nodeSize();
        for (int index = 0; index < this.graph.count; index++) {
            if (GraphGeometry.inHandle(mouseX, mouseY, this.graph.screenX[index], this.graph.screenY[index], size)) {
                return index;
            }
        }
        return -1;
    }

    /** The dependency line under the mouse (not on either node), or -1. */
    private int lineIndexAt(double mouseX, double mouseY) {
        if (!graphReady() || !onGraph(mouseX, mouseY)) {
            return -1;
        }
        int size = nodeSize();
        ChapterGraph g = this.graph;
        for (int edge = 0; edge < g.edgeCount(); edge++) {
            int from = g.edgeFrom[edge];
            int to = g.edgeTo[edge];
            if (GraphGeometry.inNode(mouseX, mouseY, g.screenX[from], g.screenY[from], size)
                    || GraphGeometry.inNode(mouseX, mouseY, g.screenX[to], g.screenY[to], size)) {
                continue;
            }
            if (GraphGeometry.onPolyline(mouseX, mouseY, g.screenRoutes[edge])) {
                return edge;
            }
        }
        return -1;
    }

    /** The grid cell the dragged node would land on, clamped to the server's coordinate bounds. */
    private int[] dropCell(double mouseX, double mouseY) {
        int min = this.editor.limit("coord_min", -512);
        int max = this.editor.limit("coord_max", 512);
        return new int[] {
                GraphGeometry.snap(this.view.worldX(mouseX - this.dragGrabX), min, max),
                GraphGeometry.snap(this.view.worldY(mouseY - this.dragGrabY), min, max)};
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

    // --- input -------------------------------------------------------------------------------------

    // Mouse buttons are always compared against InputConstants.MOUSE_BUTTON_*, never literals: 26.3 moved input
    // from GLFW (left 0, right 1) to SDL (left 1, middle 2, right 3), and each MC version compiles its own
    // constants. A literal 0 silently made every left click on the graph and the chapter tabs a no-op on 26.3.

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
        if (questPanelOpen() || !this.layout.canvas().contains(event.x(), event.y())) {
            return true;
        }
        if (overDrawer(event.x(), event.y())) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && drawerClose().contains(event.x(), event.y())) {
                closeDrawer();
            }
            return true;
        }
        int zoom = zoomButtonAt(event.x(), event.y());
        if (zoom >= 0) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                pressZoom(zoom);
            }
            return true;
        }
        if (drawerOpen()) {
            // With the drawer open the graph only pans, selects or closes (on release).
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                this.drag = Drag.PAN;
            }
            return true;
        }
        Chapter chapter = currentChapter();
        boolean editable = this.editMode && chapter != null && chapter.source() == QuestSource.CUSTOM;
        if (this.editMode && event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            int line = editable ? lineIndexAt(event.x(), event.y()) : -1;
            if (line >= 0) {
                this.editor.removePrerequisite(this.graph.quests[this.graph.edgeTo[line]].id(),
                        this.graph.quests[this.graph.edgeFrom[line]].id());
            } else if (!editable && nodeIndexAt(event.x(), event.y()) >= 0) {
                refuseLocked();
            }
            return true;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return true;
        }
        if (this.editMode) {
            int handle = handleIndexAt(event.x(), event.y());
            int node = nodeIndexAt(event.x(), event.y());
            if (handle >= 0 && editable) {
                this.drag = Drag.LINK;
                this.dragQuest = this.graph.quests[handle].id();
                return true;
            }
            if (node >= 0) {
                Identifier id = this.graph.quests[node].id();
                if (editable && QuestSource.of(id) == QuestSource.CUSTOM) {
                    this.drag = Drag.NODE;
                    this.dragQuest = id;
                    this.dragGrabX = (int) Math.round(event.x()) - this.graph.screenX[node];
                    this.dragGrabY = (int) Math.round(event.y()) - this.graph.screenY[node];
                } else {
                    this.drag = Drag.LOCKED;
                    this.dragQuest = id;
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
            this.view.panBy(dragX, dragY, true);
            this.interacted = true;
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
            int target = nodeIndexAt(event.x(), event.y());
            if (target >= 0 && !this.graph.quests[target].id().equals(quest)) {
                this.editor.addPrerequisite(this.graph.quests[target].id(), quest);
            } else if (!wasDragged) {
                selectQuest(quest);
                rebuildWidgets();
            }
        } else if (!wasDragged && event.button() == InputConstants.MOUSE_BUTTON_LEFT && !this.editor.overlayOpen()) {
            // Select on release, and only when the press was not a drag — otherwise every drag that
            // started on a node would also open it.
            handleClick(event.x(), event.y());
        }
        return super.mouseReleased(event);
    }

    private void handleClick(double mouseX, double mouseY) {
        for (int row = 0; row < this.layout.tabRows(); row++) {
            int index = this.tabScroll + row;
            if (index >= this.chapters.size()) {
                break;
            }
            if (this.layout.tabRow(row).contains(mouseX, mouseY)) {
                selectChapter(this.chapters.get(index).id());
                rebuildWidgets();
                return;
            }
        }
        if (questPanelOpen() || !onGraph(mouseX, mouseY)) {
            return;
        }
        int node = nodeIndexAt(mouseX, mouseY);
        if (node >= 0) {
            Identifier id = this.graph.quests[node].id();
            if (id.equals(this.selectedQuest)) {
                return;
            }
            selectQuest(id);
            this.editor.questOpened(id);
            rebuildWidgets();
        } else if (drawerOpen()) {
            closeDrawer();
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
        if (overDrawer(mouseX, mouseY)) {
            int max = Math.max(0, this.detailHeight - drawerContent().h());
            this.detailScroll = Mth.clamp(this.detailScroll - step * 12, 0, max);
            return true;
        }
        if (graphReady() && this.layout.canvas().contains(mouseX, mouseY)) {
            this.view.zoomBy(step, mouseX, mouseY);
            this.interacted = true;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Whether a text field has the keyboard (then typed keys belong to it, not to shortcuts). */
    boolean typing() {
        return getFocused() instanceof EditBox box && box.canConsumeInput()
                || getFocused() instanceof MultiLineEditBox;
    }

    /** Whether graph shortcuts (pan / zoom / fit / chapter keys) may take this key. */
    private boolean graphKeys() {
        return !typing() && !this.editor.overlayOpen() && !questPanelOpen() && !this.chapters.isEmpty();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Escape steps back one level (overlay, editor tab, drawer, edit mode) before closing.
        if (event.isEscape()) {
            if (this.editor.escape()) {
                return true;
            }
            if (this.selectedQuest != null) {
                closeDrawer();
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
        if (graphKeys()) {
            int key = event.key();
            boolean arrows = getFocused() == null;
            if (key == InputConstants.KEY_A || arrows && event.isLeft()) {
                this.panLeft = true;
                return true;
            }
            if (key == InputConstants.KEY_D || arrows && event.isRight()) {
                this.panRight = true;
                return true;
            }
            if (key == InputConstants.KEY_W || arrows && event.isUp()) {
                this.panUp = true;
                return true;
            }
            if (key == InputConstants.KEY_S || arrows && event.isDown()) {
                this.panDown = true;
                return true;
            }
            if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
                pressZoom(1);
                return true;
            }
            if (key == InputConstants.KEY_MINUS) {
                pressZoom(0);
                return true;
            }
            if (key == InputConstants.KEY_F || key == InputConstants.KEY_HOME) {
                pressZoom(2);
                return true;
            }
            if ((key == InputConstants.KEY_PAGEUP || key == InputConstants.KEY_PAGEDOWN) && !this.chapters.isEmpty()) {
                int next = Mth.clamp(this.selectedChapter + (key == InputConstants.KEY_PAGEUP ? -1 : 1), 0,
                        this.chapters.size() - 1);
                if (next != this.selectedChapter) {
                    selectChapter(this.chapters.get(next).id());
                    this.tabScroll = Mth.clamp(this.tabScroll, Math.max(0, next - this.layout.tabRows() + 1),
                            Math.min(next, Math.max(0, this.chapters.size() - this.layout.tabRows())));
                    rebuildWidgets();
                }
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_A || event.isLeft()) {
            this.panLeft = false;
        }
        if (key == InputConstants.KEY_D || event.isRight()) {
            this.panRight = false;
        }
        if (key == InputConstants.KEY_W || event.isUp()) {
            this.panUp = false;
        }
        if (key == InputConstants.KEY_S || event.isDown()) {
            this.panDown = false;
        }
        return super.keyReleased(event);
    }

}
