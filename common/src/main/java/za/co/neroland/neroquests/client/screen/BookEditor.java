package za.co.neroland.neroquests.client.screen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import za.co.neroland.neroquests.client.ClientEditor;
import za.co.neroland.neroquests.client.ClientQuestDefinitions;
import za.co.neroland.neroquests.client.screen.BookLayout.Rect;
import za.co.neroland.neroquests.client.screen.EditorForms.ObjectiveDraft;
import za.co.neroland.neroquests.client.screen.EditorForms.ObjectiveKind;
import za.co.neroland.neroquests.client.screen.EditorForms.RewardDraft;
import za.co.neroland.neroquests.client.screen.EditorForms.RewardKind;
import za.co.neroland.neroquests.client.screen.EditorForms.Target;
import za.co.neroland.neroquests.network.EditorPayloads;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestSource;
import za.co.neroland.neroquests.quest.QuestText;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.reward.ItemReward;

/**
 * Edit mode of the quest book (E1–E6): the chapter toolbar, the quest panel (Details / Objectives /
 * Rewards / Preview), the three-step first-run flow, prompts, confirmations and the searchable picker.
 *
 * <p>Every change is a serverbound request through {@link ClientEditor}; nothing here applies a change
 * locally (the one exception is cosmetic: a dropped node stays where it was dropped until the server's
 * sync arrives, and goes back if the server refuses). Author-written text is shown with
 * {@code Component.literal} (via {@link QuestText}); every other string comes from the lang file.
 *
 * <p>Widgets are rebuilt from this object's state whenever the shape of the form changes, so the
 * state (drafts) — not the widgets — is the source of truth while editing.
 */
final class BookEditor {

    private static final String P = "gui.neroquests.editor.";

    enum Overlay {
        NONE,
        PROMPT,
        CONFIRM,
        PICKER,
        WIZARD
    }

    enum Tab {
        DETAILS("details"),
        OBJECTIVES("objectives"),
        REWARDS("rewards"),
        PREVIEW("preview");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private enum PromptKind {
        ADD_CHAPTER,
        RENAME_CHAPTER
    }

    enum PickerKind {
        ITEM,
        ENTITY,
        DIMENSION,
        QUEST,
        CHANNEL
    }

    /** One picker row. */
    private record Option(String value, Component label, ItemStack icon) {
    }

    /** A widget placed in the scrolling form at a base y relative to the form's top. */
    private record Placed(AbstractWidget widget, int baseY) {
    }

    /** A text line in the scrolling form. */
    private record FormText(Supplier<Component> text, int x, int baseY, int width, int colour, ItemStack icon,
                            Component tooltip) {
    }

    /** An objective form's live state. */
    private static final class ObjectiveForm {
        ObjectiveDraft draft = ObjectiveDraft.DEFAULT;
        String count = "1";

        void reset() {
            this.draft = ObjectiveDraft.DEFAULT;
            this.count = "1";
        }
    }

    /** A reward form's live state. */
    private static final class RewardForm {
        RewardDraft draft = RewardDraft.DEFAULT;
        String amount = "10";

        void reset() {
            this.draft = RewardDraft.DEFAULT;
            this.amount = "10";
        }
    }

    private final QuestBookScreen screen;

    private Overlay overlay = Overlay.NONE;
    private Tab tab = Tab.DETAILS;

    private PromptKind promptKind = PromptKind.ADD_CHAPTER;
    private String promptText = "";

    private Component confirmMessage = Component.empty();
    private Component confirmLabel = Component.empty();
    private Runnable confirmAction = () -> { };

    private PickerKind pickerKind = PickerKind.ITEM;
    private boolean pickerTags;
    private String pickerQuery = "";
    private Consumer<String> pickerTarget = value -> { };
    private Overlay pickerReturn = Overlay.NONE;
    private int pickerReturnScroll;
    private int pickerScroll;
    private List<Option> pickerAll = List.of();
    private List<Option> pickerShown = List.of();

    private Identifier draftsFor;
    private String draftTitle = "";
    private String draftDescription = "";
    private String draftIcon = "";
    private boolean draftRepeatable;
    private String draftCooldown = "3600";
    private final ObjectiveForm objectiveForm = new ObjectiveForm();
    private int objectiveEditing = -1;
    private final RewardForm rewardForm = new RewardForm();
    private String escrowCopies = "1";

    private int wizardStep = 1;
    private Identifier wizardChapter;
    private String wizardTitle = "";
    private final ObjectiveForm wizardObjective = new ObjectiveForm();
    private final List<JsonObject> wizardExtra = new ArrayList<>();
    private final RewardForm wizardReward = new RewardForm();
    private String wizardCopies = "1";
    private boolean wizardMore;
    private boolean wizardRepeatable;
    private String wizardCooldown = "3600";
    private Identifier wizardPrerequisite;
    private Identifier wizardCreated;
    private int wizardX;
    private int wizardY;
    private boolean busy;

    /** Ids the editor created that the next sync has not brought yet. */
    private final Set<Identifier> awaiting = new HashSet<>();

    private Rect formArea = Rect.EMPTY;
    private int formScroll;
    private int formHeight;
    private int cursor;
    private final List<Placed> placed = new ArrayList<>();
    private final List<FormText> texts = new ArrayList<>();

    private int infoGeneration = -1;
    private JsonObject info = new JsonObject();

    BookEditor(QuestBookScreen screen) {
        this.screen = screen;
    }

    // --- state ---------------------------------------------------------------------------------------

    boolean overlayOpen() {
        return this.overlay != Overlay.NONE;
    }

    boolean awaiting(Identifier id) {
        return this.awaiting.contains(id);
    }

    /** Leave edit mode (or enter it): drop overlays and drafts. */
    void reset() {
        this.overlay = Overlay.NONE;
        this.tab = Tab.DETAILS;
        this.draftsFor = null;
        this.objectiveEditing = -1;
        this.objectiveForm.reset();
        this.rewardForm.reset();
        this.formScroll = 0;
        this.busy = false;
    }

    /** The editor block of the last sync (cached per sync). */
    private JsonObject info() {
        int generation = ClientQuestDefinitions.generation();
        if (generation != this.infoGeneration) {
            this.infoGeneration = generation;
            this.info = ClientQuestDefinitions.editorInfo();
            this.awaiting.removeIf(id -> ClientQuestDefinitions.quest(id).isPresent()
                    || ClientQuestDefinitions.chapter(id).isPresent());
        }
        return this.info;
    }

    /** A cap from the editor block's {@code limits}, or {@code fallback}. */
    int limit(String key, int fallback) {
        try {
            JsonElement limits = info().get("limits");
            if (limits != null && limits.isJsonObject() && limits.getAsJsonObject().has(key)) {
                return limits.getAsJsonObject().get(key).getAsInt();
            }
        } catch (RuntimeException e) {
            return fallback;
        }
        return fallback;
    }

    private long limitLong(String key, long fallback) {
        try {
            JsonElement limits = info().get("limits");
            if (limits != null && limits.isJsonObject() && limits.getAsJsonObject().has(key)) {
                return limits.getAsJsonObject().get(key).getAsLong();
            }
        } catch (RuntimeException e) {
            return fallback;
        }
        return fallback;
    }

    private int count(String key) {
        try {
            JsonElement counts = info().get("counts");
            if (counts != null && counts.isJsonObject() && counts.getAsJsonObject().has(key)) {
                return counts.getAsJsonObject().get(key).getAsInt();
            }
        } catch (RuntimeException e) {
            return 0;
        }
        return 0;
    }

    private boolean escrow() {
        JsonElement flag = info().get("escrow");
        try {
            return flag != null && flag.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Quest currentQuest() {
        return this.screen.selectedQuest == null ? null
                : ClientQuestDefinitions.quest(this.screen.selectedQuest).orElse(null);
    }

    /** A quest was opened from the graph: load its drafts. */
    void questOpened(Identifier id) {
        if (!this.screen.editMode || QuestSource.of(id) != QuestSource.CUSTOM) {
            return;
        }
        this.tab = Tab.DETAILS;
        this.formScroll = 0;
        loadDrafts(id);
    }

    private void loadDrafts(Identifier id) {
        Quest quest = ClientQuestDefinitions.quest(id).orElse(null);
        this.draftsFor = id;
        this.objectiveEditing = -1;
        this.objectiveForm.reset();
        this.rewardForm.reset();
        if (quest == null) {
            return;
        }
        this.draftTitle = quest.title();
        this.draftDescription = quest.description();
        this.draftIcon = quest.icon().toString();
        JsonObject extra = ClientQuestDefinitions.customExtra(id);
        this.draftRepeatable = extra.has("repeatable") && extra.get("repeatable").getAsBoolean();
        this.draftCooldown = extra.has("cooldown_seconds") ? extra.get("cooldown_seconds").getAsString() : "3600";
    }

    // --- building widgets ------------------------------------------------------------------------------

    void init() {
        this.placed.clear();
        this.texts.clear();
        switch (this.overlay) {
            case PROMPT -> buildPrompt();
            case CONFIRM -> buildConfirm();
            case PICKER -> buildPicker();
            case WIZARD -> buildWizard();
            case NONE -> {
                if (this.screen.questPanelOpen()) {
                    if (!this.screen.selectedQuest.equals(this.draftsFor)) {
                        loadDrafts(this.screen.selectedQuest);
                    }
                    buildQuestPanel();
                } else if (this.screen.selectedQuest == null) {
                    buildToolbar();
                }
            }
        }
    }

    private Button button(Component label, Component tooltip, int x, int y, int w, Runnable action) {
        // Themed like the rest of the book (same Button behaviour, the book's frame language).
        return this.screen.add(BookButton.create(label, tooltip, x, y, Math.max(8, w), BookLayout.ROW, action));
    }

    private Button button(String key, int x, int y, int w, Runnable action) {
        return button(Component.translatable(P + key), Component.translatable(P + key + ".tooltip"), x, y, w, action);
    }

    private EditBox field(String key, int x, int y, int w, int max, String value, Consumer<String> onChange) {
        EditBox box = new EditBox(this.screen.font(), x, y, Math.max(8, w), BookLayout.ROW,
                Component.translatable(P + key));
        box.setMaxLength(Math.max(1, max));
        box.setValue(value == null ? "" : value);
        box.setResponder(onChange);
        box.setHint(Component.translatable(P + key + ".hint"));
        box.setTooltip(Tooltip.create(Component.translatable(P + key + ".tooltip")));
        return this.screen.add(box);
    }

    private void beginForm(Rect area) {
        this.formArea = area;
        this.placed.clear();
        this.texts.clear();
        this.cursor = 2;
    }

    private <T extends AbstractWidget> T place(T widget) {
        this.placed.add(new Placed(widget, this.cursor));
        return widget;
    }

    private void text(Component text, int colour) {
        textAt(() -> text, this.formArea.x() + 4, this.formArea.w() - 8, colour, ItemStack.EMPTY, null);
        this.cursor += 11;
    }

    private void textAt(Supplier<Component> text, int x, int width, int colour, ItemStack icon, Component tooltip) {
        this.texts.add(new FormText(text, x, this.cursor, width, colour, icon, tooltip));
    }

    /** Wrapped text, at most {@code maxLines} lines. */
    private void paragraph(Component text, int colour, int maxLines) {
        List<FormattedCharSequence> lines = this.screen.font().split(text, Math.max(20, this.formArea.w() - 8));
        int shown = Math.min(maxLines, lines.size());
        for (int index = 0; index < shown; index++) {
            FormattedCharSequence line = lines.get(index);
            textAt(() -> sequence(line), this.formArea.x() + 4, this.formArea.w() - 8, colour, ItemStack.EMPTY,
                    shown < lines.size() ? text : null);
            this.cursor += 10;
        }
        this.cursor += 1;
    }

    private static Component sequence(FormattedCharSequence line) {
        StringBuilder out = new StringBuilder();
        line.accept((index, style, codePoint) -> {
            out.appendCodePoint(codePoint);
            return true;
        });
        return Component.literal(out.toString());
    }

    private void endForm() {
        this.formHeight = this.cursor + 4;
        layoutForm();
    }

    /** Positions the form's widgets for the current scroll; those not fully inside are hidden. */
    private void layoutForm() {
        int max = Math.max(0, this.formHeight - this.formArea.h());
        this.formScroll = Mth.clamp(this.formScroll, 0, max);
        for (Placed entry : this.placed) {
            int y = this.formArea.y() + entry.baseY() - this.formScroll;
            entry.widget().setY(y);
            entry.widget().visible = y >= this.formArea.y() && y + entry.widget().getHeight() <= this.formArea.bottom();
        }
    }

    // --- toolbar (graph view) --------------------------------------------------------------------------

    private void buildToolbar() {
        Rect bar = this.screen.layout.toolbar();
        Chapter chapter = this.screen.currentChapter();
        boolean custom = chapter != null && chapter.source() == QuestSource.CUSTOM;
        int step = BookLayout.TOOL_BUTTON + BookLayout.TOOL_GAP;
        int x = bar.x();
        int chapterCap = limit("max_chapters", 20);
        button(Component.translatable(P + "chapter.add"),
                Component.translatable(P + "chapter.add.tooltip", count("chapters"), chapterCap),
                x, bar.y(), BookLayout.TOOL_BUTTON, () -> openPrompt(PromptKind.ADD_CHAPTER, ""));
        button("chapter.rename", x + step, bar.y(), BookLayout.TOOL_BUTTON, () -> {
            Chapter current = this.screen.currentChapter();
            if (current != null) {
                openPrompt(PromptKind.RENAME_CHAPTER, current.title());
            }
        }).active = custom;
        List<ChapterOrder.Row> customs = customRows();
        int index = indexOf(customs, chapter);
        button("chapter.up", x + 2 * step, bar.y(), BookLayout.TOOL_BUTTON, () -> reorder(-1)).active =
                custom && index > 0;
        button("chapter.down", x + 3 * step, bar.y(), BookLayout.TOOL_BUTTON, () -> reorder(1)).active =
                custom && index >= 0 && index < customs.size() - 1;
        button("chapter.delete", x + 4 * step, bar.y(), BookLayout.TOOL_BUTTON, this::confirmDeleteChapter).active =
                custom;
        int questCap = limit("max_quests", 500);
        button(Component.translatable(P + "quest.add"),
                Component.translatable(P + "quest.add.tooltip", count("quests"), questCap),
                bar.right() - BookLayout.ADD_QUEST_WIDTH, bar.y(), BookLayout.ADD_QUEST_WIDTH, this::startWizard)
                .active = custom;
    }

    void drawToolbar(GuiGraphicsExtractor graphics) {
        Rect bar = this.screen.layout.toolbar();
        Chapter chapter = this.screen.currentChapter();
        if (chapter == null || chapter.source() == QuestSource.CUSTOM) {
            return;
        }
        int x = bar.x() + 5 * (BookLayout.TOOL_BUTTON + BookLayout.TOOL_GAP) + 2;
        int width = bar.right() - BookLayout.ADD_QUEST_WIDTH - 4 - x;
        Glyphs.lock(graphics, x, bar.y() + 4, QuestBookScreen.SUBTLE);
        graphics.text(this.screen.font(), this.screen.trim(Component.translatable(P + "chapter.read_only"), width - 8),
                x + 8, bar.y() + 3, QuestBookScreen.SUBTLE, false);
    }

    private List<ChapterOrder.Row> customRows() {
        List<ChapterOrder.Row> rows = new ArrayList<>();
        for (Chapter chapter : this.screen.chapters()) {
            if (chapter.source() == QuestSource.CUSTOM) {
                rows.add(new ChapterOrder.Row(chapter.id(), chapter.source(), QuestBookScreen.order(chapter)));
            }
        }
        return rows;
    }

    private static int indexOf(List<ChapterOrder.Row> rows, Chapter chapter) {
        if (chapter == null) {
            return -1;
        }
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).id().equals(chapter.id())) {
                return index;
            }
        }
        return -1;
    }

    private void reorder(int direction) {
        Chapter chapter = this.screen.currentChapter();
        List<ChapterOrder.Row> rows = customRows();
        for (ChapterOrder.Change change : ChapterOrder.move(rows, indexOf(rows, chapter), direction)) {
            ClientQuestDefinitions.chapter(change.id()).ifPresent(target -> ClientEditor.send(
                    id -> new EditorPayloads.UpdateChapter(id, target.id(), target.title(), Optional.of(target.icon()),
                            change.order()), null));
        }
    }

    private void confirmDeleteChapter() {
        Chapter chapter = this.screen.currentChapter();
        if (chapter == null || chapter.source() != QuestSource.CUSTOM) {
            return;
        }
        Identifier id = chapter.id();
        openConfirm(Component.translatable(P + "confirm.delete_chapter", QuestText.title(chapter),
                        chapter.quests().size()), Component.translatable(P + "confirm.delete"),
                () -> ClientEditor.send(request -> new EditorPayloads.DeleteChapter(request, id), null));
    }

    // --- graph edits (called by the screen) --------------------------------------------------------------

    void moveNode(Identifier quest, int x, int y) {
        Chapter chapter = this.screen.currentChapter();
        if (chapter == null) {
            return;
        }
        Optional<Chapter.Entry> entry = chapter.entry(quest);
        if (entry.isEmpty() || entry.get().x() == x && entry.get().y() == y) {
            return;
        }
        this.screen.pendingMoves.put(quest, new int[] {x, y});
        ClientEditor.send(id -> new EditorPayloads.MoveNode(id, quest, x, y), result -> {
            if (!result.ok()) {
                this.screen.pendingMoves.remove(quest);
            }
        });
    }

    /** Make {@code prerequisite} required by {@code target} (drag from a handle onto a node). */
    void addPrerequisite(Identifier target, Identifier prerequisite) {
        Quest quest = ClientQuestDefinitions.quest(target).orElse(null);
        if (quest == null) {
            return;
        }
        if (quest.prerequisites().contains(prerequisite)) {
            this.screen.showToast(Component.translatable(P + "link.exists"));
            return;
        }
        List<Identifier> next = new ArrayList<>(quest.prerequisites());
        next.add(prerequisite);
        sendPrerequisites(target, next);
    }

    void removePrerequisite(Identifier target, Identifier prerequisite) {
        Quest quest = ClientQuestDefinitions.quest(target).orElse(null);
        if (quest == null) {
            return;
        }
        List<Identifier> next = new ArrayList<>(quest.prerequisites());
        next.remove(prerequisite);
        sendPrerequisites(target, next);
    }

    private void sendPrerequisites(Identifier target, List<Identifier> list) {
        if (list.size() > EditorPayloads.MAX_PREREQUISITES) {
            this.screen.showToast(Component.translatable(P + "link.cap", EditorPayloads.MAX_PREREQUISITES));
            return;
        }
        List<Identifier> copy = List.copyOf(list);
        ClientEditor.send(id -> new EditorPayloads.SetPrerequisites(id, target, copy), null);
    }

    // --- quest panel ---------------------------------------------------------------------------------------

    private void buildQuestPanel() {
        Rect tabs = this.screen.layout.tabs();
        int tabWidth = this.screen.layout.editorTabWidth();
        int x = tabs.x();
        for (Tab each : Tab.values()) {
            button(Component.translatable(P + "tab." + each.key), Component.translatable(P + "tab." + each.key
                    + ".tooltip"), x, tabs.y(), tabWidth, () -> {
                        this.tab = each;
                        this.formScroll = 0;
                        this.screen.rebuild();
                    });
            x += tabWidth + BookLayout.TOOL_GAP;
        }
        button("back", tabs.right() - BookLayout.BACK_WIDTH, tabs.y(), BookLayout.BACK_WIDTH, () -> {
            this.screen.selectedQuest = null;
            this.screen.rebuild();
        });
        Quest quest = currentQuest();
        beginForm(this.screen.layout.form());
        if (quest == null) {
            text(Component.translatable(P + "loading"), QuestBookScreen.SUBTLE);
            endForm();
            return;
        }
        switch (this.tab) {
            case DETAILS -> buildDetails(quest);
            case OBJECTIVES -> buildObjectives(quest);
            case REWARDS -> buildRewards(quest);
            case PREVIEW -> this.cursor = 2;
        }
        endForm();
    }

    private void buildDetails(Quest quest) {
        int x = this.formArea.x() + 4;
        int w = this.formArea.w() - 8;
        int titleMax = limit("title_max", 64);
        int descriptionMax = limit("description_max", 512);
        text(Component.translatable(P + "details.title_label", titleMax), QuestBookScreen.SUBTLE);
        place(field("details.title", x, 0, w, titleMax, this.draftTitle, value -> this.draftTitle = value));
        this.cursor += 16;
        text(Component.translatable(P + "details.description_label", descriptionMax), QuestBookScreen.SUBTLE);
        MultiLineEditBox description = MultiLineEditBox.builder().setX(x).setY(0)
                .setPlaceholder(Component.translatable(P + "details.description.hint"))
                .build(this.screen.font(), w, 40, Component.translatable(P + "details.description"));
        description.setCharacterLimit(descriptionMax);
        description.setValue(this.draftDescription);
        description.setValueListener(value -> this.draftDescription = value);
        description.setTooltip(Tooltip.create(Component.translatable(P + "details.description.tooltip")));
        place(this.screen.add(description));
        this.cursor += 44;
        text(Component.translatable(P + "details.icon_label"), QuestBookScreen.SUBTLE);
        int small = 34;
        place(field("details.icon", x, 0, w - 2 * (small + 2), 256, this.draftIcon, value -> this.draftIcon = value));
        place(button("pick", x + w - 2 * small - 2, 0, small, () -> openPicker(PickerKind.ITEM, false,
                value -> this.draftIcon = value)));
        place(button("held", x + w - small, 0, small, () -> held().ifPresent(stack -> {
            this.draftIcon = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            this.screen.rebuild();
        })));
        this.cursor += 18;
        int half = (w - 2) / 2;
        place(button(Component.translatable(P + (this.draftRepeatable ? "details.repeatable.on"
                        : "details.repeatable.off")), Component.translatable(P + "details.repeatable.tooltip"), x, 0,
                half, () -> {
                    this.draftRepeatable = !this.draftRepeatable;
                    this.screen.rebuild();
                }));
        EditBox cooldown = place(field("details.cooldown", x + half + 2, 0, w - half - 2, 8, this.draftCooldown,
                value -> this.draftCooldown = value));
        cooldown.setEditable(this.draftRepeatable);
        this.cursor += 16;
        textAt(this::cooldownText, x, w, QuestBookScreen.SUBTLE, ItemStack.EMPTY, null);
        this.cursor += 12;

        int prerequisiteCap = limit("prerequisites_max", 16);
        text(Component.translatable(P + "details.prerequisites", quest.prerequisites().size(), prerequisiteCap),
                QuestBookScreen.COLOUR_AVAILABLE);
        for (Identifier prerequisite : quest.prerequisites()) {
            Component name = QuestBookText.questName(prerequisite);
            textAt(() -> name, x + 4, w - 24, QuestBookScreen.TITLE, ItemStack.EMPTY, name);
            place(button(Component.translatable(P + "remove"), Component.translatable(P + "prerequisite.remove.tooltip"),
                    x + w - 16, 0, 16, () -> removePrerequisite(quest.id(), prerequisite)));
            this.cursor += 15;
        }
        place(button("prerequisite.add", x, 0, w, () -> openPicker(PickerKind.QUEST, false,
                value -> EditorForms.id(value).ifPresent(id -> addPrerequisite(quest.id(), id)))));
        this.cursor += 18;
        text(Component.translatable(ClientQuestDefinitions.authoredByMe(quest.id()) ? P + "author.you"
                : P + "author.other"), QuestBookScreen.SUBTLE);
        place(button("save", x, 0, half, this::saveDetails));
        place(button("quest.delete", x + half + 2, 0, w - half - 2, () -> openConfirm(
                Component.translatable(P + "confirm.delete_quest", QuestText.title(quest)),
                Component.translatable(P + "confirm.delete"), () -> {
                    Identifier id = quest.id();
                    ClientEditor.send(request -> new EditorPayloads.DeleteQuest(request, id), null);
                    this.screen.selectedQuest = null;
                })));
        this.cursor += 16;
    }

    private Component cooldownText() {
        Optional<Long> seconds = EditorForms.number(this.draftCooldown, 0L, Integer.MAX_VALUE);
        if (!this.draftRepeatable) {
            return Component.translatable(P + "details.not_repeatable");
        }
        if (seconds.isEmpty()) {
            return Component.translatable(P + "details.cooldown.invalid", limit("cooldown_min", 60),
                    limit("cooldown_max", 2_592_000));
        }
        long[] parts = EditorForms.splitDuration(seconds.get().longValue());
        return Component.translatable(P + "details.cooldown.human", parts[0], parts[1], parts[2]);
    }

    private void saveDetails() {
        Quest quest = currentQuest();
        if (quest == null) {
            return;
        }
        Identifier id = quest.id();
        boolean sent = false;
        Optional<Identifier> icon = EditorForms.id(this.draftIcon);
        if (!this.draftIcon.isBlank() && icon.isEmpty()) {
            this.screen.showToast(Component.translatable(P + "form.bad_id", this.draftIcon));
            return;
        }
        String title = clip(this.draftTitle, EditorPayloads.MAX_TITLE);
        String description = clip(this.draftDescription, EditorPayloads.MAX_DESCRIPTION);
        if (!title.equals(quest.title()) || !description.equals(quest.description())
                || !icon.orElse(quest.icon()).equals(quest.icon())) {
            ClientEditor.send(request -> new EditorPayloads.UpdateQuest(request, id, title, description, icon), null);
            sent = true;
        }
        JsonObject extra = ClientQuestDefinitions.customExtra(id);
        boolean wasRepeatable = extra.has("repeatable") && extra.get("repeatable").getAsBoolean();
        int wasCooldown = extra.has("cooldown_seconds") ? extra.get("cooldown_seconds").getAsInt() : 0;
        int cooldown = EditorForms.number(this.draftCooldown, 0L, Integer.MAX_VALUE).map(Long::intValue).orElse(0);
        if (this.draftRepeatable != wasRepeatable || this.draftRepeatable && cooldown != wasCooldown) {
            boolean repeatable = this.draftRepeatable;
            ClientEditor.send(request -> new EditorPayloads.SetRepeatable(request, id, repeatable, cooldown), null);
            sent = true;
        }
        if (!sent) {
            this.screen.showToast(Component.translatable(P + "nothing_to_save"));
        }
    }

    private void buildObjectives(Quest quest) {
        int x = this.formArea.x() + 4;
        int w = this.formArea.w() - 8;
        List<ObjectiveSpec> objectives = quest.objectives();
        text(Component.translatable(P + "objectives.count", objectives.size(), limit("max_objectives", 12)),
                QuestBookScreen.COLOUR_AVAILABLE);
        for (int index = 0; index < objectives.size(); index++) {
            int at = index;
            Component line = QuestBookText.objective(objectives.get(index), QuestSource.CUSTOM);
            textAt(() -> line, x + 2, w - 40, at == this.objectiveEditing ? QuestBookScreen.COLOUR_IN_PROGRESS
                    : QuestBookScreen.TITLE, ItemStack.EMPTY, line);
            place(button(Component.translatable(P + "edit"), Component.translatable(P + "objective.edit.tooltip"),
                    x + w - 34, 0, 16, () -> {
                        EditorForms.draftOf(objectives.get(at)).ifPresent(draft -> {
                            this.objectiveForm.draft = draft;
                            this.objectiveForm.count = Integer.toString(draft.count());
                            this.objectiveEditing = at;
                            this.screen.rebuild();
                        });
                    }));
            place(button(Component.translatable(P + "remove"), Component.translatable(P + "objective.remove.tooltip"),
                    x + w - 16, 0, 16, () -> sendObjectives(quest, EditorForms.without(
                            EditorForms.objectivesJson(objectives), at), false)));
            this.cursor += 15;
        }
        this.cursor += 2;
        text(this.objectiveEditing >= 0
                ? Component.translatable(P + "objective.editing", this.objectiveEditing + 1)
                : Component.translatable(P + "objective.adding"), QuestBookScreen.COLOUR_AVAILABLE);
        objectiveFormWidgets(this.objectiveForm, x, w);
        int half = (w - 2) / 2;
        place(button(this.objectiveEditing >= 0 ? "objective.update" : "objective.add", x, 0, half,
                () -> submitObjective(quest)));
        place(button("clear", x + half + 2, 0, w - half - 2, () -> {
            this.objectiveForm.reset();
            this.objectiveEditing = -1;
            this.screen.rebuild();
        }));
        this.cursor += 16;
    }

    private void submitObjective(Quest quest) {
        Optional<JsonObject> json = objectiveJson(this.objectiveForm);
        if (json.isEmpty()) {
            return;
        }
        JsonArray list = EditorForms.with(EditorForms.objectivesJson(quest.objectives()), this.objectiveEditing,
                json.get());
        sendObjectives(quest, list, true);
    }

    private void sendObjectives(Quest quest, JsonArray list, boolean resetForm) {
        String body = list.toString();
        if (body.length() > EditorPayloads.MAX_JSON) {
            this.screen.showToast(Component.translatable(P + "form.too_big"));
            return;
        }
        Identifier id = quest.id();
        ClientEditor.send(request -> new EditorPayloads.SetObjectives(request, id, body), result -> {
            if (result.ok() && resetForm) {
                this.objectiveForm.reset();
                this.objectiveEditing = -1;
                this.screen.rebuildIfOpen();
            }
        });
    }

    /** The objective form's JSON, or empty after showing why not. */
    private Optional<JsonObject> objectiveJson(ObjectiveForm form) {
        ObjectiveDraft draft = form.draft;
        if (draft.kind().counted()) {
            Optional<Long> count = EditorForms.number(form.count, EditorForms.MIN_COUNT, EditorForms.MAX_COUNT);
            if (count.isEmpty()) {
                this.screen.showToast(Component.translatable(P + "form.bad_count", EditorForms.MIN_COUNT,
                        EditorForms.MAX_COUNT));
                return Optional.empty();
            }
            draft = draft.withCount(count.get().intValue());
        }
        Optional<JsonObject> json = EditorForms.objectiveJson(draft);
        if (json.isEmpty()) {
            this.screen.showToast(Component.translatable(draft.kind().target() == Target.LABEL ? P + "form.no_label"
                    : P + "form.bad_target"));
        }
        return json;
    }

    /** The shared objective form (quest panel and first-run flow). */
    private void objectiveFormWidgets(ObjectiveForm form, int x, int w) {
        ObjectiveKind kind = form.draft.kind();
        place(button(Component.translatable(P + "objective.type", Component.translatable(kind.key())),
                Component.translatable(P + "objective.type.tooltip"), x, 0, w, () -> {
                    form.draft = form.draft.withKind(kind.next());
                    this.screen.rebuild();
                }));
        this.cursor += 16;
        Target target = kind.target();
        if (target == Target.LABEL) {
            place(field("objective.label", x, 0, w, limit("label_max", 64), form.draft.target(),
                    value -> form.draft = form.draft.withTarget(value)));
        } else {
            PickerKind picker = pickerFor(target);
            int buttons = (picker != null ? 36 : 0) + (target.taggable() ? 18 : 0);
            place(field("objective.target." + target.name().toLowerCase(Locale.ROOT), x, 0, w - buttons, 256,
                    form.draft.target(), value -> form.draft = form.draft.withTarget(value)));
            int bx = x + w - buttons;
            if (target.taggable()) {
                place(button("tag", bx, 0, 16, () -> {
                    String value = form.draft.target().trim();
                    form.draft = form.draft.withTarget(value.startsWith("#") ? value.substring(1) : "#" + value);
                    this.screen.rebuild();
                }));
                bx += 18;
            }
            if (picker != null) {
                place(button("pick", bx, 0, 34, () -> openPicker(picker, EditorForms.isTag(form.draft.target()),
                        value -> form.draft = form.draft.withTarget(value))));
            }
        }
        this.cursor += 16;
        if (kind.counted() || kind == ObjectiveKind.COLLECT_ITEM) {
            int half = (w - 2) / 2;
            place(field("objective.count", x, 0, kind == ObjectiveKind.COLLECT_ITEM ? half : w, 7, form.count,
                    value -> form.count = value));
            if (kind == ObjectiveKind.COLLECT_ITEM) {
                place(button(Component.translatable(P + (form.draft.consume() ? "objective.consume.on"
                                : "objective.consume.off")), Component.translatable(P + "objective.consume.tooltip"),
                        x + half + 2, 0, w - half - 2, () -> {
                            form.draft = form.draft.withConsume(!form.draft.consume());
                            this.screen.rebuild();
                        }));
            }
            this.cursor += 16;
        }
        paragraph(Component.translatable(P + "objective_hint." + kind.path()), QuestBookScreen.SUBTLE, 2);
    }

    private static PickerKind pickerFor(Target target) {
        return switch (target) {
            case ITEM -> PickerKind.ITEM;
            case ENTITY -> PickerKind.ENTITY;
            case DIMENSION -> PickerKind.DIMENSION;
            case QUEST -> PickerKind.QUEST;
            case CHANNEL -> PickerKind.CHANNEL;
            case STRUCTURE, GATE, LABEL -> null;
        };
    }

    private void buildRewards(Quest quest) {
        int x = this.formArea.x() + 4;
        int w = this.formArea.w() - 8;
        List<RewardSpec> rewards = quest.rewards();
        text(Component.translatable(P + "rewards.receives"), QuestBookScreen.COLOUR_AVAILABLE);
        if (rewards.isEmpty()) {
            text(Component.translatable(P + "rewards.none"), QuestBookScreen.SUBTLE);
        }
        for (int index = 0; index < rewards.size(); index++) {
            int at = index;
            RewardSpec reward = rewards.get(index);
            Component line = QuestBookText.reward(reward);
            ItemStack icon = reward instanceof ItemReward item ? QuestBookText.icon(item.item()) : ItemStack.EMPTY;
            textAt(() -> line, x + 2, w - 22, QuestBookScreen.TITLE, icon, line);
            place(button(Component.translatable(P + "remove"), Component.translatable(P + "reward.remove.tooltip"),
                    x + w - 16, 0, 16, () -> sendRewards(quest, EditorForms.without(
                            EditorForms.rewardsJson(rewards), at), this.escrowCopies, false)));
            this.cursor += rewards.get(index) instanceof ItemReward ? 18 : 15;
        }
        EditorForms.Totals totals = EditorForms.totals(rewards);
        int maxRewards = limit("max_rewards", 9);
        int maxStacks = limit("max_stacks", 9);
        long maxXp = limitLong("max_xp", 500L);
        long maxCurrency = limitLong("max_currency", 1000L);
        text(Component.translatable(P + "rewards.totals", totals.rewards(), maxRewards, totals.stacks(), maxStacks),
                totals.rewards() > maxRewards || totals.stacks() > maxStacks ? QuestBookScreen.WARN
                        : QuestBookScreen.SUBTLE);
        text(Component.translatable(P + "rewards.totals2", totals.xp(), maxXp, totals.currency(), maxCurrency),
                totals.xp() > maxXp || totals.currency() > maxCurrency ? QuestBookScreen.WARN : QuestBookScreen.SUBTLE);
        if (escrow()) {
            JsonObject extra = ClientQuestDefinitions.customExtra(quest.id());
            int held = extra.has("escrow_copies") ? extra.get("escrow_copies").getAsInt() : 0;
            paragraph(Component.translatable(P + "rewards.escrow", held), QuestBookScreen.COLOUR_IN_PROGRESS, 3);
            int half = (w - 2) / 2;
            place(field("rewards.copies", x, 0, half, 3, this.escrowCopies, value -> this.escrowCopies = value));
            place(button("rewards.deposit", x + half + 2, 0, w - half - 2, () -> sendRewards(quest,
                    EditorForms.rewardsJson(rewards), this.escrowCopies, false)));
            this.cursor += 18;
        }
        text(Component.translatable(P + "reward.adding"), QuestBookScreen.COLOUR_AVAILABLE);
        rewardFormWidgets(this.rewardForm, x, w);
        place(button("reward.add", x, 0, w, () -> rewardJson(this.rewardForm).ifPresent(json -> sendRewards(quest,
                EditorForms.with(EditorForms.rewardsJson(rewards), -1, json), this.escrowCopies, true))));
        this.cursor += 16;
    }

    private void sendRewards(Quest quest, JsonArray list, String copiesText, boolean resetForm) {
        String body = list.toString();
        if (body.length() > EditorPayloads.MAX_JSON) {
            this.screen.showToast(Component.translatable(P + "form.too_big"));
            return;
        }
        int copies = EditorForms.number(copiesText, 0L, 64L).map(Long::intValue).orElse(0);
        Identifier id = quest.id();
        ClientEditor.send(request -> new EditorPayloads.SetRewards(request, id, body, copies), result -> {
            if (result.ok() && resetForm) {
                this.rewardForm.reset();
                this.screen.rebuildIfOpen();
            }
        });
    }

    private Optional<JsonObject> rewardJson(RewardForm form) {
        RewardDraft draft = form.draft;
        if (draft.kind().hasAmount()) {
            long max = switch (draft.kind()) {
                case ITEM -> 64L;
                case XP -> limitLong("max_xp", 500L);
                case CURRENCY -> limitLong("max_currency", 1000L);
                case LOOT_TABLE -> 1L;
            };
            Optional<Long> amount = EditorForms.number(form.amount, 1L, Math.max(1L, max));
            if (amount.isEmpty()) {
                this.screen.showToast(Component.translatable(P + "form.bad_amount", 1, max));
                return Optional.empty();
            }
            draft = draft.withAmount(amount.get().longValue());
        }
        Optional<JsonObject> json = EditorForms.rewardJson(draft);
        if (json.isEmpty()) {
            this.screen.showToast(Component.translatable(P + "form.bad_target"));
        }
        return json;
    }

    /** The shared reward form (quest panel and first-run flow). */
    private void rewardFormWidgets(RewardForm form, int x, int w) {
        RewardKind kind = form.draft.kind();
        place(button(Component.translatable(P + "reward.type", Component.translatable(kind.key())),
                Component.translatable(P + "reward.type.tooltip"), x, 0, w, () -> {
                    form.draft = form.draft.withKind(kind.next());
                    form.amount = Long.toString(form.draft.amount());
                    this.screen.rebuild();
                }));
        this.cursor += 16;
        if (kind.hasId()) {
            boolean item = kind == RewardKind.ITEM;
            int buttons = item ? 72 : 0;
            place(field("reward.id." + kind.path(), x, 0, w - buttons, 256, form.draft.id(),
                    value -> form.draft = form.draft.withId(value)));
            if (item) {
                place(button("pick", x + w - 70, 0, 34, () -> openPicker(PickerKind.ITEM, false,
                        value -> form.draft = form.draft.withId(value))));
                place(button("held", x + w - 34, 0, 34, () -> held().ifPresent(stack -> {
                    form.draft = form.draft.withId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    form.amount = Integer.toString(Math.min(64, stack.getCount()));
                    this.screen.rebuild();
                })));
            }
            this.cursor += 16;
        }
        if (kind.hasAmount()) {
            place(field("reward.amount." + kind.path(), x, 0, w, 7, form.amount, value -> form.amount = value));
            this.cursor += 16;
        }
        paragraph(Component.translatable(P + "reward_hint." + kind.path()), QuestBookScreen.SUBTLE, 2);
    }

    private Optional<ItemStack> held() {
        var player = this.screen.minecraft().player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getMainHandItem();
        if (stack.isEmpty()) {
            this.screen.showToast(Component.translatable(P + "held.empty"));
            return Optional.empty();
        }
        return Optional.of(stack);
    }

    void drawQuestPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Rect tabs = this.screen.layout.tabs();
        int tabWidth = this.screen.layout.editorTabWidth();
        int x = tabs.x() + this.tab.ordinal() * (tabWidth + BookLayout.TOOL_GAP);
        graphics.fill(x, tabs.bottom(), x + tabWidth, tabs.bottom() + 2, QuestBookScreen.COLOUR_AVAILABLE);
        Rect form = this.screen.layout.form();
        QuestBookScreen.frame(graphics, form);
        Quest quest = currentQuest();
        if (this.tab == Tab.PREVIEW && quest != null) {
            this.formHeight = this.screen.drawDetail(graphics, quest, form, this.formScroll, false) + 12;
            return;
        }
        drawTexts(graphics);
    }

    private void drawTexts(GuiGraphicsExtractor graphics) {
        Rect area = this.formArea;
        graphics.enableScissor(area.x(), area.y(), area.right(), area.bottom());
        for (FormText line : this.texts) {
            int y = area.y() + line.baseY() - this.formScroll;
            if (y + 9 < area.y() || y > area.bottom()) {
                continue;
            }
            int x = line.x();
            int width = line.width();
            if (!line.icon().isEmpty()) {
                graphics.item(line.icon(), x, y);
                x += 18;
                width -= 18;
            }
            graphics.text(this.screen.font(), this.screen.trim(line.text().get(), width), x,
                    line.icon().isEmpty() ? y + 2 : y + 5, line.colour(), false);
        }
        graphics.disableScissor();
    }

    /** Tooltips for trimmed form lines (drawn after the widgets). */
    void drawAfterWidgets(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!this.screen.questPanelOpen() || this.tab == Tab.PREVIEW || !this.formArea.contains(mouseX, mouseY)) {
            return;
        }
        for (FormText line : this.texts) {
            int y = this.formArea.y() + line.baseY() - this.formScroll;
            if (line.tooltip() != null && mouseX >= line.x() && mouseX < line.x() + line.width()
                    && mouseY >= y && mouseY < y + 12) {
                graphics.setTooltipForNextFrame(this.screen.font(),
                        this.screen.font().split(line.tooltip(), 220), mouseX, mouseY);
                return;
            }
        }
    }

    // --- prompt / confirm ---------------------------------------------------------------------------------

    private void openPrompt(PromptKind kind, String initial) {
        this.promptKind = kind;
        this.promptText = initial == null ? "" : initial;
        this.overlay = Overlay.PROMPT;
        this.screen.rebuild();
    }

    private void buildPrompt() {
        Rect modal = this.screen.layout.modal();
        EditBox box = field("prompt.chapter_title", modal.x() + 4, modal.y() + 16, modal.w() - 8,
                limit("title_max", 64), this.promptText, value -> this.promptText = value);
        int half = (modal.w() - 10) / 2;
        button("ok", modal.x() + 4, modal.bottom() - 18, half, this::submitPrompt);
        button("cancel", modal.x() + 6 + half, modal.bottom() - 18, half, this::closeOverlay);
        this.screen.focus(box);
    }

    private void submitPrompt() {
        String title = clip(this.promptText.trim(), EditorPayloads.MAX_TITLE);
        if (this.promptKind == PromptKind.ADD_CHAPTER) {
            ClientEditor.send(request -> new EditorPayloads.CreateChapter(request, title, Optional.empty()), result -> {
                if (result.ok() && result.id().isPresent()) {
                    this.awaiting.add(result.id().get());
                    this.screen.selectChapter(result.id().get());
                    this.screen.rebuildIfOpen();
                }
            });
        } else {
            Chapter chapter = this.screen.currentChapter();
            if (chapter != null) {
                ClientEditor.send(request -> new EditorPayloads.UpdateChapter(request, chapter.id(), title,
                        Optional.of(chapter.icon()), QuestBookScreen.order(chapter)), null);
            }
        }
        closeOverlay();
    }

    private void openConfirm(Component message, Component label, Runnable action) {
        this.confirmMessage = message;
        this.confirmLabel = label;
        this.confirmAction = action;
        this.overlay = Overlay.CONFIRM;
        this.screen.rebuild();
    }

    private void buildConfirm() {
        Rect modal = this.screen.layout.modal();
        int half = (modal.w() - 10) / 2;
        Button yes = button(this.confirmLabel, Component.translatable(P + "confirm.tooltip"), modal.x() + 4,
                modal.bottom() - 18, half, () -> {
                    Runnable action = this.confirmAction;
                    this.overlay = Overlay.NONE;
                    action.run();
                    this.screen.rebuild();
                });
        button("cancel", modal.x() + 6 + half, modal.bottom() - 18, half, this::closeOverlay);
        this.screen.focus(yes);
    }

    private void closeOverlay() {
        this.overlay = Overlay.NONE;
        this.screen.rebuild();
    }

    // --- picker ----------------------------------------------------------------------------------------------

    private void openPicker(PickerKind kind, boolean tags, Consumer<String> target) {
        this.pickerReturn = this.overlay;
        this.pickerReturnScroll = this.formScroll;
        this.pickerKind = kind;
        this.pickerTags = tags && (kind == PickerKind.ITEM || kind == PickerKind.ENTITY);
        this.pickerTarget = target;
        this.pickerQuery = "";
        this.pickerScroll = 0;
        this.pickerAll = options(kind, this.pickerTags);
        filterPicker();
        this.overlay = Overlay.PICKER;
        this.screen.rebuild();
    }

    private Rect pickerList() {
        Rect area = this.screen.layout.content();
        return new Rect(area.x() + 2, area.y() + 32, area.w() - 4, Math.max(0, area.h() - 34));
    }

    private int pickerRowHeight() {
        return this.pickerKind == PickerKind.ITEM && !this.pickerTags || this.pickerKind == PickerKind.QUEST ? 18 : 11;
    }

    private void buildPicker() {
        Rect area = this.screen.layout.content();
        boolean taggable = this.pickerKind == PickerKind.ITEM || this.pickerKind == PickerKind.ENTITY;
        int buttons = 18 + 36 + (taggable ? 18 : 0);
        EditBox search = field("picker.search", area.x() + 2, area.y() + 14, area.w() - 4 - buttons, 128,
                this.pickerQuery, value -> {
                    this.pickerQuery = value;
                    this.pickerScroll = 0;
                    filterPicker();
                });
        int bx = area.right() - 2 - buttons;
        if (taggable) {
            button(Component.translatable(P + "tag"), Component.translatable(P + "picker.tags.tooltip"), bx + 2,
                    area.y() + 14, 16, () -> {
                        this.pickerTags = !this.pickerTags;
                        this.pickerAll = options(this.pickerKind, this.pickerTags);
                        this.pickerScroll = 0;
                        filterPicker();
                        this.screen.rebuild();
                    });
            bx += 18;
        }
        button("picker.use", bx + 2, area.y() + 14, 34, this::pickTyped);
        button(Component.translatable(P + "remove"), Component.translatable(P + "picker.close.tooltip"), bx + 38,
                area.y() + 14, 16, this::closePicker);
        this.screen.focus(search);
    }

    private void filterPicker() {
        String query = this.pickerQuery.trim().toLowerCase(Locale.ROOT);
        List<Option> shown = new ArrayList<>();
        for (Option option : this.pickerAll) {
            if (query.isEmpty() || option.value().toLowerCase(Locale.ROOT).contains(query)
                    || option.label().getString().toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(option);
                if (shown.size() >= 500) {
                    break;
                }
            }
        }
        this.pickerShown = shown;
    }

    private List<Option> options(PickerKind kind, boolean tags) {
        List<Option> out = new ArrayList<>();
        switch (kind) {
            case ITEM -> {
                if (tags) {
                    BuiltInRegistries.ITEM.listTagIds().map(tag -> "#" + tag.location()).sorted()
                            .forEach(value -> out.add(new Option(value, Component.literal(value), ItemStack.EMPTY)));
                } else {
                    BuiltInRegistries.ITEM.keySet().stream().sorted((a, b) -> a.toString().compareTo(b.toString()))
                            .filter(id -> !BuiltInRegistries.ITEM.getValue(id).equals(Items.AIR))
                            .forEach(id -> out.add(new Option(id.toString(), QuestBookText.itemName(id),
                                    QuestBookText.icon(id))));
                }
            }
            case ENTITY -> {
                if (tags) {
                    BuiltInRegistries.ENTITY_TYPE.listTagIds().map(tag -> "#" + tag.location()).sorted()
                            .forEach(value -> out.add(new Option(value, Component.literal(value), ItemStack.EMPTY)));
                } else {
                    BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(Identifier::toString).sorted()
                            .forEach(value -> out.add(new Option(value, Component.literal(value), ItemStack.EMPTY)));
                }
            }
            case DIMENSION -> {
                ClientPacketListener connection = this.screen.minecraft().getConnection();
                if (connection != null) {
                    connection.levels().stream().map(key -> key.identifier().toString()).sorted()
                            .forEach(value -> out.add(new Option(value, Component.literal(value), ItemStack.EMPTY)));
                }
            }
            case QUEST -> {
                for (Quest quest : ClientQuestDefinitions.quests().values()) {
                    if (quest.id().equals(this.screen.selectedQuest) || QuestBookScreen.visibleQuest(quest.id()) == null) {
                        continue;
                    }
                    out.add(new Option(quest.id().toString(), QuestText.title(quest), QuestBookText.icon(quest.icon())));
                }
            }
            case CHANNEL -> ClientQuestDefinitions.seenChannels()
                    .forEach(id -> out.add(new Option(id.toString(), Component.literal(id.toString()), ItemStack.EMPTY)));
        }
        return out;
    }

    private void choose(String value) {
        Consumer<String> target = this.pickerTarget;
        closePicker();
        target.accept(value);
        this.screen.rebuild();
    }

    private void pickTyped() {
        String typed = this.pickerQuery.trim();
        if (EditorForms.id(typed).isEmpty()) {
            this.screen.showToast(Component.translatable(P + "form.bad_id", typed));
            return;
        }
        choose(this.pickerTags && !typed.startsWith("#") ? "#" + typed : typed);
    }

    private void closePicker() {
        this.overlay = this.pickerReturn;
        this.formScroll = this.pickerReturnScroll;
        this.screen.rebuild();
    }

    // --- first-run flow (E5) -----------------------------------------------------------------------------------

    private void startWizard() {
        Chapter chapter = this.screen.currentChapter();
        if (chapter == null || chapter.source() != QuestSource.CUSTOM) {
            return;
        }
        this.wizardChapter = chapter.id();
        this.wizardStep = 1;
        this.wizardTitle = "";
        this.wizardObjective.reset();
        this.wizardExtra.clear();
        this.wizardReward.reset();
        this.wizardCopies = "1";
        this.wizardMore = false;
        this.wizardRepeatable = false;
        this.wizardCooldown = "3600";
        this.wizardPrerequisite = null;
        this.wizardCreated = null;
        this.busy = false;
        int[] slot = GraphGeometry.freeSlot(this.screen.occupiedCells());
        this.wizardX = slot[0];
        this.wizardY = slot[1];
        this.formScroll = 0;
        this.overlay = Overlay.WIZARD;
        this.screen.rebuild();
    }

    private int wizardHeader() {
        return this.screen.layout.compact() ? 24 : 36;
    }

    private void buildWizard() {
        Rect area = this.screen.layout.content();
        int header = wizardHeader();
        Rect form = new Rect(area.x(), area.y() + header, area.w(), Math.max(0, area.h() - header - 18));
        beginForm(form);
        int x = form.x() + 4;
        int w = form.w() - 8;
        AbstractWidget first = null;
        switch (this.wizardStep) {
            case 1 -> {
                first = place(field("wizard.title", x, 0, w, limit("title_max", 64), this.wizardTitle,
                        value -> this.wizardTitle = value));
                this.cursor += 18;
            }
            case 2 -> {
                objectiveFormWidgets(this.wizardObjective, x, w);
                if (this.wizardMore) {
                    text(Component.translatable(P + "wizard.extra", this.wizardExtra.size()), QuestBookScreen.SUBTLE);
                    place(button("wizard.another", x, 0, w, () -> objectiveJson(this.wizardObjective).ifPresent(json -> {
                        if (this.wizardExtra.size() + 1 >= limit("max_objectives", 12)) {
                            this.screen.showToast(Component.translatable(P + "wizard.extra_cap"));
                            return;
                        }
                        this.wizardExtra.add(json);
                        this.wizardObjective.reset();
                        this.screen.rebuild();
                    })));
                    this.cursor += 16;
                }
            }
            default -> {
                rewardFormWidgets(this.wizardReward, x, w);
                if (escrow()) {
                    paragraph(Component.translatable(P + "wizard.escrow"), QuestBookScreen.COLOUR_IN_PROGRESS, 3);
                    place(field("rewards.copies", x, 0, w, 3, this.wizardCopies, value -> this.wizardCopies = value));
                    this.cursor += 16;
                }
                if (this.wizardMore) {
                    int half = (w - 2) / 2;
                    place(button(Component.translatable(P + (this.wizardRepeatable ? "details.repeatable.on"
                                    : "details.repeatable.off")), Component.translatable(P + "details.repeatable.tooltip"),
                            x, 0, half, () -> {
                                this.wizardRepeatable = !this.wizardRepeatable;
                                this.screen.rebuild();
                            }));
                    EditBox cooldown = place(field("details.cooldown", x + half + 2, 0, w - half - 2, 8,
                            this.wizardCooldown, value -> this.wizardCooldown = value));
                    cooldown.setEditable(this.wizardRepeatable);
                    this.cursor += 16;
                    Component requires = this.wizardPrerequisite == null
                            ? Component.translatable(P + "wizard.requires.none")
                            : QuestBookText.questName(this.wizardPrerequisite);
                    place(button(Component.translatable(P + "wizard.requires", requires),
                            Component.translatable(P + "wizard.requires.tooltip"), x, 0, w,
                            () -> openPicker(PickerKind.QUEST, false,
                                    value -> this.wizardPrerequisite = EditorForms.id(value).orElse(null))));
                    this.cursor += 16;
                }
            }
        }
        endForm();

        int y = area.bottom() - 16;
        int third = (area.w() - 12) / 3;
        button(this.wizardStep == 1 ? "cancel" : this.wizardStep == 2 ? "wizard.back" : "wizard.skip",
                area.x() + 4, y, third, this::wizardBack);
        if (this.wizardStep > 1) {
            button(Component.translatable(P + (this.wizardMore ? "wizard.less" : "wizard.more")),
                    Component.translatable(P + "wizard.more.tooltip"), area.x() + 6 + third, y, third, () -> {
                        this.wizardMore = !this.wizardMore;
                        this.screen.rebuild();
                    });
        }
        String next = this.wizardStep == 1 ? "wizard.next" : this.wizardStep == 2 ? "wizard.create" : "wizard.finish";
        button(next, area.right() - 4 - third, y, third, this::wizardNext).active = !this.busy;
        if (first != null) {
            this.screen.focus(first);
        }
    }

    private void drawWizard(GuiGraphicsExtractor graphics) {
        Rect area = this.screen.layout.content();
        QuestBookScreen.frame(graphics, area);
        String step = switch (this.wizardStep) {
            case 1 -> "name";
            case 2 -> "objective";
            default -> "reward";
        };
        graphics.text(this.screen.font(), this.screen.trim(Component.translatable(P + "wizard.step", this.wizardStep,
                        Component.translatable(P + "wizard.step." + step)), area.w() - 8), area.x() + 4, area.y() + 4,
                QuestBookScreen.TITLE, false);
        int lines = this.screen.layout.compact() ? 1 : 2;
        List<FormattedCharSequence> hint = this.screen.font().split(
                Component.translatable(P + "wizard.hint." + step), area.w() - 8);
        for (int index = 0; index < Math.min(lines, hint.size()); index++) {
            graphics.text(this.screen.font(), hint.get(index), area.x() + 4, area.y() + 15 + index * 10,
                    QuestBookScreen.SUBTLE, false);
        }
        drawTexts(graphics);
    }

    private void wizardBack() {
        if (this.wizardStep == 2 && this.wizardCreated == null) {
            this.wizardStep = 1;
            this.formScroll = 0;
            this.screen.rebuild();
            return;
        }
        if (this.wizardStep == 3) {
            finishWizard(false);
            return;
        }
        closeOverlay();
    }

    private void wizardNext() {
        if (this.busy) {
            return;
        }
        switch (this.wizardStep) {
            case 1 -> {
                if (this.wizardTitle.isBlank()) {
                    this.screen.showToast(Component.translatable(P + "wizard.need_title"));
                    return;
                }
                this.wizardStep = 2;
                this.formScroll = 0;
                this.screen.rebuild();
            }
            case 2 -> createWizardQuest();
            default -> finishWizard(true);
        }
    }

    private void createWizardQuest() {
        Optional<JsonObject> objective = objectiveJson(this.wizardObjective);
        if (objective.isEmpty()) {
            return;
        }
        JsonArray list = new JsonArray();
        this.wizardExtra.forEach(list::add);
        list.add(objective.get());
        String body = list.toString();
        String title = clip(this.wizardTitle.trim(), EditorPayloads.MAX_TITLE);
        Identifier chapter = this.wizardChapter;
        int x = this.wizardX;
        int y = this.wizardY;
        this.busy = true;
        this.screen.rebuild();
        ClientEditor.send(request -> new EditorPayloads.CreateQuest(request, chapter, title, body, x, y), result -> {
            this.busy = false;
            if (result.ok() && result.id().isPresent()) {
                this.wizardCreated = result.id().get();
                this.awaiting.add(this.wizardCreated);
                this.wizardStep = 3;
                this.formScroll = 0;
            }
            this.screen.rebuildIfOpen();
        });
    }

    /** Step 3: send the reward (if any) and the "More" settings, then return to the canvas. */
    private void finishWizard(boolean withReward) {
        Identifier quest = this.wizardCreated;
        if (quest == null) {
            closeOverlay();
            return;
        }
        if (withReward) {
            boolean empty = this.wizardReward.draft.kind().hasId() && this.wizardReward.draft.id().isBlank();
            if (!empty) {
                Optional<JsonObject> reward = rewardJson(this.wizardReward);
                if (reward.isEmpty()) {
                    return;
                }
                JsonArray list = new JsonArray();
                list.add(reward.get());
                String body = list.toString();
                int copies = EditorForms.number(this.wizardCopies, 0L, 64L).map(Long::intValue).orElse(0);
                ClientEditor.send(request -> new EditorPayloads.SetRewards(request, quest, body, copies), null);
            }
        }
        if (this.wizardMore && this.wizardRepeatable) {
            int cooldown = EditorForms.number(this.wizardCooldown, 0L, Integer.MAX_VALUE).map(Long::intValue).orElse(0);
            ClientEditor.send(request -> new EditorPayloads.SetRepeatable(request, quest, true, cooldown), null);
        }
        if (this.wizardMore && this.wizardPrerequisite != null) {
            List<Identifier> prerequisites = List.of(this.wizardPrerequisite);
            ClientEditor.send(request -> new EditorPayloads.SetPrerequisites(request, quest, prerequisites), null);
        }
        this.wizardCreated = null;
        closeOverlay();
    }

    // --- overlay drawing and input ---------------------------------------------------------------------------

    void drawOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Rect panel = this.screen.layout.panel();
        graphics.fill(panel.x(), panel.y() + this.screen.layout.header().h(), panel.right(), panel.bottom(), 0xC0000000);
        switch (this.overlay) {
            case PROMPT -> {
                Rect modal = this.screen.layout.modal();
                QuestBookScreen.frame(graphics, modal);
                graphics.text(this.screen.font(), this.screen.trim(Component.translatable(
                                this.promptKind == PromptKind.ADD_CHAPTER ? P + "prompt.add_chapter"
                                        : P + "prompt.rename_chapter"), modal.w() - 8), modal.x() + 4, modal.y() + 4,
                        QuestBookScreen.TITLE, false);
            }
            case CONFIRM -> {
                Rect modal = this.screen.layout.modal();
                QuestBookScreen.frame(graphics, modal);
                List<FormattedCharSequence> lines = this.screen.font().split(this.confirmMessage, modal.w() - 8);
                int max = Math.max(1, (modal.h() - 24) / 10);
                for (int index = 0; index < Math.min(max, lines.size()); index++) {
                    graphics.text(this.screen.font(), lines.get(index), modal.x() + 4, modal.y() + 4 + index * 10,
                            QuestBookScreen.TITLE, false);
                }
            }
            case PICKER -> drawPicker(graphics, mouseX, mouseY);
            case WIZARD -> drawWizard(graphics);
            case NONE -> {
                // nothing open
            }
        }
    }

    private void drawPicker(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Rect area = this.screen.layout.content();
        QuestBookScreen.frame(graphics, area);
        Component title = Component.translatable(P + "picker." + this.pickerKind.name().toLowerCase(Locale.ROOT)
                + (this.pickerTags ? ".tags" : ""), this.pickerShown.size());
        graphics.text(this.screen.font(), this.screen.trim(title, area.w() - 8), area.x() + 4, area.y() + 3,
                QuestBookScreen.TITLE, false);
        Rect list = pickerList();
        int row = pickerRowHeight();
        graphics.enableScissor(list.x(), list.y(), list.right(), list.bottom());
        if (this.pickerShown.isEmpty()) {
            graphics.text(this.screen.font(), this.screen.trim(Component.translatable(P + "picker.empty"), list.w() - 4),
                    list.x() + 2, list.y() + 2, QuestBookScreen.SUBTLE, false);
        }
        for (int index = 0; index < this.pickerShown.size(); index++) {
            int y = list.y() + index * row - this.pickerScroll;
            if (y + row < list.y()) {
                continue;
            }
            if (y > list.bottom()) {
                break;
            }
            Option option = this.pickerShown.get(index);
            boolean hovered = mouseX >= list.x() && mouseX < list.right() && mouseY >= y && mouseY < y + row
                    && list.contains(mouseX, mouseY);
            if (hovered) {
                graphics.fill(list.x(), y, list.right(), y + row, 0x40B388FF);
            }
            int x = list.x() + 2;
            if (!option.icon().isEmpty()) {
                graphics.item(option.icon(), x, y + 1);
                x += 20;
            }
            Component label = option.label().getString().equals(option.value()) ? option.label()
                    : Component.translatable(P + "picker.row", option.label(), option.value());
            graphics.text(this.screen.font(), this.screen.trim(label, list.right() - x - 2), x,
                    y + (row - 8) / 2, QuestBookScreen.TITLE, false);
        }
        graphics.disableScissor();
    }

    /** A click that no widget took while an overlay is open (picker rows). */
    void overlayClicked(double mouseX, double mouseY, int button) {
        if (this.overlay != Overlay.PICKER || button != 0) {
            return;
        }
        Rect list = pickerList();
        if (!list.contains(mouseX, mouseY)) {
            return;
        }
        int index = (int) ((mouseY - list.y() + this.pickerScroll) / pickerRowHeight());
        if (index >= 0 && index < this.pickerShown.size()) {
            choose(this.pickerShown.get(index).value());
        }
    }

    /** Wheel over the picker list or the form. */
    boolean scrolled(double mouseX, double mouseY, int step) {
        if (this.overlay == Overlay.PICKER) {
            Rect list = pickerList();
            int max = Math.max(0, this.pickerShown.size() * pickerRowHeight() - list.h());
            this.pickerScroll = Mth.clamp(this.pickerScroll - step * pickerRowHeight() * 3, 0, max);
            return true;
        }
        if (this.formArea.contains(mouseX, mouseY) || this.screen.questPanelOpen() && this.tab == Tab.PREVIEW) {
            int max = Math.max(0, this.formHeight - this.formArea.h());
            this.formScroll = Mth.clamp(this.formScroll - step * 12, 0, max);
            layoutForm();
            return true;
        }
        return false;
    }

    /** Escape: close the innermost thing. Returns false when nothing of the editor was open. */
    boolean escape() {
        switch (this.overlay) {
            case PICKER -> {
                closePicker();
                return true;
            }
            case PROMPT, CONFIRM -> {
                closeOverlay();
                return true;
            }
            case WIZARD -> {
                wizardBack();
                return true;
            }
            case NONE -> {
                if (this.screen.questPanelOpen()) {
                    this.screen.selectedQuest = null;
                    this.screen.rebuild();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /** Enter in a text field: confirm the current form. */
    boolean confirm() {
        switch (this.overlay) {
            case PROMPT -> {
                submitPrompt();
                return true;
            }
            case PICKER -> {
                if (!this.pickerShown.isEmpty() && !this.pickerQuery.isBlank()) {
                    choose(this.pickerShown.get(0).value());
                } else {
                    pickTyped();
                }
                return true;
            }
            case WIZARD -> {
                wizardNext();
                return true;
            }
            case CONFIRM -> {
                return false;
            }
            case NONE -> {
                Quest quest = currentQuest();
                if (!this.screen.questPanelOpen() || quest == null) {
                    return false;
                }
                switch (this.tab) {
                    case DETAILS -> saveDetails();
                    case OBJECTIVES -> submitObjective(quest);
                    case REWARDS -> rewardJson(this.rewardForm).ifPresent(json -> sendRewards(quest,
                            EditorForms.with(EditorForms.rewardsJson(quest.rewards()), -1, json), this.escrowCopies,
                            true));
                    case PREVIEW -> {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static String clip(String text, int max) {
        String value = text == null ? "" : text;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
