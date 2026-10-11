package za.co.neroland.neroquests.custom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import com.google.gson.JsonObject;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.command.AdminActions;
import za.co.neroland.neroquests.command.ReloadCheckSections;
import za.co.neroland.neroquests.data.QuestProgress;
import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.network.QuestSync;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.EditorPermissions;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestSource;
import za.co.neroland.neroquests.quest.QuestSources;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.engine.QuestEngine;
import za.co.neroland.neroquests.quest.objective.ManualObjective;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.LootTableReward;
import za.co.neroland.neroquests.quest.reward.OwedDelivery;
import za.co.neroland.neroquests.quest.reward.RewardContext;
import za.co.neroland.neroquests.quest.reward.XpReward;

/**
 * The custom-quest mutation API (D2) — the <b>single writer</b> of {@link CustomQuestState}. Server thread
 * only. Every method maps 1:1 to one editor request (Stage E's serverbound packets) or admin command, and
 * every mutation runs the same steps:
 *
 * <ol>
 *   <li><b>authorise</b> — {@link EditorPermissions#canAuthor} re-derived now, plus a per-player rate limit
 *       (20 per second burst, 200 per minute); admin commands have passed their own check;</li>
 *   <li><b>immutability</b> — {@link QuestSources#requireMutable}: spine and pack ids are refused;</li>
 *   <li><b>validate</b> — sanitised text and limits, caps, registry ids, cycles; then a <em>preview</em> of
 *       the whole definitions pipeline with the change applied ({@link QuestDefinitions#preview}): nothing
 *       that loads today may stop loading;</li>
 *   <li><b>apply</b> — the previewed store replaces the live one; side effects (escrow deposit/refund,
 *       pruning progress) run only now;</li>
 *   <li><b>sync</b> — {@link QuestDefinitions#customChanged} re-validates (no pack file is re-read) and the
 *       definitions go to every client at the end of the tick, coalesced; in-flight progress is re-resolved
 *       by the reconciler exactly as after {@code /reload};</li>
 *   <li><b>count</b> — {@link AdminActions} (a count only, never who or what).</li>
 * </ol>
 *
 * <p>Results are {@link EditResult}s with translatable refusal keys. Nothing here logs a player or any
 * author text.
 */
public final class CustomQuests {

    private static final RateLimiter BURST = EditGate.editorBurst();
    private static final RateLimiter MINUTE = EditGate.editorMinute();

    /** Most prerequisites one custom quest may list. */
    public static final int MAX_PREREQUISITES = 16;

    /** Lowest and highest chapter sort key. */
    public static final int MIN_ORDER = -1000;
    public static final int MAX_ORDER = 1000;

    /** Quest id → repeat cooldown (ms) of every repeatable custom quest, rebuilt with the definitions. */
    private static volatile Map<Identifier, Long> repeats = Map.of();

    private CustomQuests() {
    }

    /** Wire the store into the definitions pipeline, the engine and reload-check. Called once from common init. */
    public static void init() {
        QuestDefinitions.setCustomSource(server -> {
            CustomQuestState state = CustomQuestState.get(server);
            repeats = repeatCooldowns(state);
            return CustomContent.build(state);
        });
        QuestEngine.setRepeatPolicy(quest -> {
            Long cooldown = repeats.get(quest);
            return cooldown == null ? 0L : cooldown.longValue();
        });
        ReloadCheckSections.register(CustomQuests::reloadCheckLines);
    }

    /** The server stopped (AUD-31): drop per-server state. */
    public static void serverStopped() {
        BURST.clear();
        MINUTE.clear();
        repeats = Map.of();
        CustomContent.reset();
        EditorSync.serverStopped();
    }

    // --- who is acting -------------------------------------------------------

    /**
     * Who asked for a mutation: an editor-authorised player (checked and rate-limited per call), or an admin
     * command that has already passed its own trusted-source check ({@code player} may then be null for the
     * console).
     */
    public record Actor(MinecraftServer server, ServerPlayer player, boolean admin) {

        /** A player using the editor. */
        public static Actor of(ServerPlayer player) {
            return new Actor(player.level().getServer(), player, false);
        }

        /** An admin command (console or a trusted operator). */
        public static Actor admin(MinecraftServer server, ServerPlayer player) {
            return new Actor(server, player, true);
        }

        Optional<UUID> id() {
            return player == null ? Optional.empty() : Optional.of(player.getUUID());
        }
    }

    /** A validated change: what to report, and side effects to run only once the change is applied. */
    private record Change(EditResult result, Runnable onCommit) {

        static Change of(EditResult result) {
            return new Change(result, () -> { });
        }
    }

    // --- chapters ------------------------------------------------------------------

    /** Create a custom chapter. */
    public static EditResult createChapter(ServerPlayer player, String title, Identifier icon) {
        return createChapter(Actor.of(player), title, icon);
    }

    public static EditResult createChapter(Actor actor, String title, Identifier icon) {
        return edit(actor, null, preview -> {
            CustomLimits limits = CustomLimits.current();
            if (preview.chapters().size() >= limits.maxChapters()) {
                return Change.of(EditResult.error(EditResult.CAP_CHAPTERS, Integer.toString(limits.maxChapters())));
            }
            Optional<EditResult> bad = checkTitle(title);
            if (bad.isEmpty()) {
                bad = checkIcon(icon);
            }
            if (bad.isPresent()) {
                return Change.of(bad.get());
            }
            String slug = SlugGenerator.unique(candidate -> preview.chapters().containsKey(candidate));
            int order = 0;
            for (CustomChapter existing : preview.chapters().values()) {
                order = Math.max(order, existing.order() + 1);
            }
            CustomChapter chapter = new CustomChapter(slug, TextSanitizer.clean(title), iconOr(icon, true),
                    Math.min(order, MAX_ORDER), System.currentTimeMillis());
            preview.putChapter(chapter);
            return Change.of(EditResult.ok(chapter.id()));
        });
    }

    /** Rename / re-icon / re-order a custom chapter. */
    public static EditResult updateChapter(ServerPlayer player, Identifier chapter, String title, Identifier icon,
                                           int order) {
        return updateChapter(Actor.of(player), chapter, title, icon, order);
    }

    public static EditResult updateChapter(Actor actor, Identifier chapterId, String title, Identifier icon,
                                           int order) {
        return edit(actor, chapterId, preview -> {
            Optional<CustomChapter> chapter = chapterOf(preview, chapterId);
            if (chapter.isEmpty()) {
                return Change.of(EditResult.error(EditResult.UNKNOWN_CHAPTER, String.valueOf(chapterId)));
            }
            Optional<EditResult> bad = checkTitle(title);
            if (bad.isEmpty()) {
                bad = checkIcon(icon);
            }
            if (bad.isPresent()) {
                return Change.of(bad.get());
            }
            preview.putChapter(chapter.get().withTitle(TextSanitizer.clean(title), iconOr(icon, true),
                    Math.max(MIN_ORDER, Math.min(MAX_ORDER, order))));
            return Change.of(EditResult.ok(chapterId));
        });
    }

    /** Delete a custom chapter and every quest in it (their progress is pruned, escrow refunded). */
    public static EditResult deleteChapter(ServerPlayer player, Identifier chapter) {
        return deleteChapter(Actor.of(player), chapter);
    }

    public static EditResult deleteChapter(Actor actor, Identifier chapterId) {
        return edit(actor, chapterId, preview -> {
            Optional<CustomChapter> chapter = chapterOf(preview, chapterId);
            if (chapter.isEmpty()) {
                return Change.of(EditResult.error(EditResult.UNKNOWN_CHAPTER, String.valueOf(chapterId)));
            }
            List<CustomQuest> doomed = preview.questsOf(chapter.get().slug());
            Set<Identifier> ids = new LinkedHashSet<>();
            doomed.forEach(quest -> ids.add(quest.id()));
            Optional<EditResult> referenced = referencedFromOutside(preview, ids);
            if (referenced.isPresent()) {
                return Change.of(referenced.get());
            }
            for (CustomQuest quest : doomed) {
                preview.removeQuest(quest.id());
            }
            dropPrerequisites(preview, ids);
            preview.removeChapter(chapter.get().slug());
            return new Change(EditResult.ok(chapterId), () -> doomed.forEach(quest -> afterDelete(actor, quest)));
        });
    }

    // --- quests --------------------------------------------------------------------

    /**
     * Create a custom quest in a chapter. A quest needs at least one objective to load, so the first
     * objective list is part of the request (the three-step flow posts "name + first objective" together,
     * then adds rewards with {@link #setRewards}).
     */
    public static EditResult createQuest(ServerPlayer player, Identifier chapter, String title,
                                         List<ObjectiveSpec> objectives, int x, int y) {
        return createQuest(Actor.of(player), chapter, title, objectives, x, y);
    }

    public static EditResult createQuest(Actor actor, Identifier chapterId, String title,
                                         List<ObjectiveSpec> objectives, int x, int y) {
        return edit(actor, chapterId, preview -> {
            CustomLimits limits = CustomLimits.current();
            Optional<CustomChapter> chapter = chapterOf(preview, chapterId);
            if (chapter.isEmpty()) {
                return Change.of(EditResult.error(EditResult.UNKNOWN_CHAPTER, String.valueOf(chapterId)));
            }
            if (preview.quests().size() >= limits.maxQuests()) {
                return Change.of(EditResult.error(EditResult.CAP_QUESTS, Integer.toString(limits.maxQuests())));
            }
            Optional<EditResult> bad = checkTitle(title);
            if (bad.isEmpty() && !(CustomLimits.coordinateOk(x) && CustomLimits.coordinateOk(y))) {
                bad = Optional.of(coordinatesError());
            }
            if (bad.isPresent()) {
                return Change.of(bad.get());
            }
            List<ObjectiveSpec> cleaned = new ArrayList<>();
            bad = checkObjectives(actor.server(), gateView(actor), objectives, cleaned);
            if (bad.isPresent()) {
                return Change.of(bad.get());
            }
            String chapterSlug = chapter.get().slug();
            String slug = SlugGenerator.unique(candidate -> preview.questSlugTaken(chapterSlug, candidate));
            Identifier id = Identifier.fromNamespaceAndPath(QuestSource.CUSTOM_NAMESPACE, chapterSlug + "/" + slug);
            // A brand-new quest has no dependants and its quest_complete targets must already exist, so it
            // cannot close a cycle.
            preview.putQuest(CustomQuest.create(id, chapterSlug, TextSanitizer.clean(title), Quest.DEFAULT_ICON, x, y,
                    cleaned, actor.id(), System.currentTimeMillis()));
            return Change.of(EditResult.ok(id));
        });
    }

    /** Change a custom quest's title, description and icon. */
    public static EditResult updateQuest(ServerPlayer player, Identifier quest, String title, String description,
                                         Identifier icon) {
        return updateQuest(Actor.of(player), quest, title, description, icon);
    }

    public static EditResult updateQuest(Actor actor, Identifier questId, String title, String description,
                                         Identifier icon) {
        return edit(actor, questId, preview -> {
            Optional<CustomQuest> quest = preview.quest(questId);
            if (quest.isEmpty()) {
                return Change.of(EditResult.error(EditResult.UNKNOWN_QUEST, String.valueOf(questId)));
            }
            Optional<EditResult> bad = checkTitle(title);
            if (bad.isEmpty() && TextSanitizer.cleanWithin(description, TextSanitizer.DESCRIPTION_MAX).isEmpty()) {
                bad = Optional.of(EditResult.error(EditResult.TEXT_TOO_LONG,
                        Integer.toString(TextSanitizer.DESCRIPTION_MAX)));
            }
            if (bad.isEmpty()) {
                bad = checkIcon(icon);
            }
            if (bad.isPresent()) {
                return Change.of(bad.get());
            }
            preview.putQuest(quest.get().withText(TextSanitizer.clean(title), TextSanitizer.clean(description),
                    iconOr(icon, false), System.currentTimeMillis()));
            return Change.of(EditResult.ok(questId));
        });
    }

    /** Delete a custom quest: its progress (every player) and ledger entries are pruned, escrow refunded. */
    public static EditResult deleteQuest(ServerPlayer player, Identifier quest) {
        return deleteQuest(Actor.of(player), quest);
    }

    public static EditResult deleteQuest(Actor actor, Identifier questId) {
        return edit(actor, questId, preview -> {
            Optional<CustomQuest> quest = preview.quest(questId);
            if (quest.isEmpty()) {
                return Change.of(EditResult.error(EditResult.UNKNOWN_QUEST, String.valueOf(questId)));
            }
            Optional<EditResult> referenced = referencedFromOutside(preview, Set.of(questId));
            if (referenced.isPresent()) {
                return Change.of(referenced.get());
            }
            preview.removeQuest(questId);
            dropPrerequisites(preview, Set.of(questId));
            CustomQuest doomed = quest.get();
            return new Change(EditResult.ok(questId), () -> afterDelete(actor, doomed));
        });
    }

    /** Move a quest's node on its chapter page (grid units, -512..512 on both axes). */
    public static EditResult moveNode(ServerPlayer player, Identifier quest, int x, int y) {
        return moveNode(Actor.of(player), quest, x, y);
    }

    public static EditResult moveNode(Actor actor, Identifier questId, int x, int y) {
        return edit(actor, questId, preview -> {
            Optional<CustomQuest> quest = preview.quest(questId);
            if (quest.isEmpty()) {
                return Change.of(EditResult.error(EditResult.UNKNOWN_QUEST, String.valueOf(questId)));
            }
            if (!(CustomLimits.coordinateOk(x) && CustomLimits.coordinateOk(y))) {
                return Change.of(coordinatesError());
            }
            preview.putQuest(quest.get().withNode(x, y, System.currentTimeMillis()));
            return Change.of(EditResult.ok(questId));
        });
    }

    /**
     * Replace a custom quest's prerequisites. Each must be a loaded quest (any source) other than itself; a
     * link that would close a cycle is refused.
     */
    public static EditResult setPrerequisites(ServerPlayer player, Identifier quest, List<Identifier> prerequisites) {
        return setPrerequisites(Actor.of(player), quest, prerequisites);
    }

    public static EditResult setPrerequisites(Actor actor, Identifier questId, List<Identifier> prerequisites) {
        return edit(actor, questId, preview -> {
            Optional<CustomQuest> quest = preview.quest(questId);
            if (quest.isEmpty()) {
                return Change.of(EditResult.error(EditResult.UNKNOWN_QUEST, String.valueOf(questId)));
            }
            List<Identifier> unique = new ArrayList<>(new LinkedHashSet<>(prerequisites));
            if (unique.size() > MAX_PREREQUISITES) {
                return Change.of(EditResult.error(EditResult.BAD_REQUEST));
            }
            Map<Identifier, Quest> loaded = QuestDefinitions.questsForServer(actor.server());
            Predicate<Identifier> gateOpen = gateView(actor);
            for (Identifier prerequisite : unique) {
                if (prerequisite.equals(questId)) {
                    return Change.of(EditResult.error(EditResult.SELF_PREREQUISITE, questId.toString()));
                }
                // J1: a quest hidden from this author answers exactly like one that does not exist.
                if (!AuthorVisibility.known(prerequisite, loaded, id -> preview.quest(id).isPresent(), gateOpen)) {
                    return Change.of(EditResult.error(EditResult.PREREQUISITE_UNKNOWN, prerequisite.toString()));
                }
            }
            if (CustomGraph.createsCycle(graph(actor.server(), preview, questId), questId, unique)) {
                return Change.of(EditResult.error(EditResult.CYCLE, questId.toString()));
            }
            preview.putQuest(quest.get().withPrerequisites(unique, System.currentTimeMillis()));
            return Change.of(EditResult.ok(questId));
        });
    }

    /** Replace a custom quest's objectives (1..12; every id validated against the running game). */
    public static EditResult setObjectives(ServerPlayer player, Identifier quest, List<ObjectiveSpec> objectives) {
        return setObjectives(Actor.of(player), quest, objectives);
    }

    public static EditResult setObjectives(Actor actor, Identifier questId, List<ObjectiveSpec> objectives) {
        return edit(actor, questId, preview -> {
            Optional<CustomQuest> quest = preview.quest(questId);
            if (quest.isEmpty()) {
                return Change.of(EditResult.error(EditResult.UNKNOWN_QUEST, String.valueOf(questId)));
            }
            List<ObjectiveSpec> cleaned = new ArrayList<>();
            Optional<EditResult> bad = checkObjectives(actor.server(), gateView(actor), objectives, cleaned);
            if (bad.isPresent()) {
                return Change.of(bad.get());
            }
            Set<Identifier> deps = new LinkedHashSet<>(quest.get().prerequisites());
            Quest probe = new Quest(questId, "", "", Quest.DEFAULT_ICON, List.of(), cleaned, List.of(),
                    QuestScope.PLAYER, Optional.empty());
            deps.addAll(CustomGraph.dependenciesOf(probe));
            if (CustomGraph.createsCycle(graph(actor.server(), preview, questId), questId, deps)) {
                return Change.of(EditResult.error(EditResult.CYCLE, questId.toString()));
            }
            preview.putQuest(quest.get().withObjectives(cleaned, System.currentTimeMillis()));
            return Change.of(EditResult.ok(questId));
        });
    }

    /**
     * Replace a custom quest's rewards: {@code item} (one stack each, at most {@code customRewardMaxStacks}),
     * {@code loot_table} (a loaded table), {@code xp} (≤ {@code customRewardMaxXp} in total) and
     * {@code currency} (credits, ≤ {@code customRewardMaxCurrency} in total). Never gate, reputation or
     * mod_hook; at most 9 rewards.
     *
     * <p><b>Escrow</b> ({@code customRewardsEscrowed=true}): when the item rewards change, the author's
     * remaining deposit is refunded and {@code escrowCopies} (1..64) copies of every item reward are taken
     * from the acting player's inventory (plain stacks, no components); unchanged item rewards keep their
     * deposit, and {@code escrowCopies > 0} then tops it up. Loot-table rewards are refused in escrow mode.
     * Without escrow {@code escrowCopies} is ignored.
     */
    public static EditResult setRewards(ServerPlayer player, Identifier quest, List<RewardSpec> rewards,
                                        int escrowCopies) {
        return setRewards(Actor.of(player), quest, rewards, escrowCopies);
    }

    public static EditResult setRewards(Actor actor, Identifier questId, List<RewardSpec> rewards, int escrowCopies) {
        return edit(actor, questId, preview -> {
            Optional<CustomQuest> found = preview.quest(questId);
            if (found.isEmpty()) {
                return Change.of(EditResult.error(EditResult.UNKNOWN_QUEST, String.valueOf(questId)));
            }
            CustomQuest quest = found.get();
            CustomLimits limits = CustomLimits.current();
            Optional<EditResult> bad = checkRewards(actor.server(), rewards, limits);
            if (bad.isPresent()) {
                return Change.of(bad.get());
            }
            List<RewardSpec> previous = quest.rewards().orElse(List.of());
            boolean itemsChanged = !itemRewards(previous).equals(itemRewards(rewards));
            long now = System.currentTimeMillis();
            if (!limits.escrow()) {
                if (quest.escrowed() && itemsChanged) {
                    CustomQuest before = quest;
                    preview.putQuest(quest.withRewards(rewards, List.of(), now));
                    return new Change(EditResult.ok(questId), () -> refund(actor, before, previous));
                }
                preview.putQuest(quest.withRewards(rewards, quest.escrowed() ? quest.escrow() : List.of(), now));
                return Change.of(EditResult.ok(questId));
            }
            // Escrow mode.
            for (int index = 0; index < rewards.size(); index++) {
                if (rewards.get(index) instanceof LootTableReward) {
                    return Change.of(EditResult.error(EditResult.ESCROW_LOOT_TABLE, Integer.toString(index + 1)));
                }
            }
            boolean keep = quest.escrowed() && !itemsChanged;
            int copies = keep ? Math.max(0, escrowCopies) : escrowCopies;
            boolean needsItems = !itemRewards(rewards).isEmpty();
            if (needsItems && !keep && (copies < 1 || copies > CustomLimits.MAX_ESCROW_COPIES)) {
                return Change.of(EditResult.error(EditResult.ESCROW_COPIES,
                        Integer.toString(CustomLimits.MAX_ESCROW_COPIES)));
            }
            if (keep && copies > CustomLimits.MAX_ESCROW_COPIES) {
                return Change.of(EditResult.error(EditResult.ESCROW_COPIES,
                        Integer.toString(CustomLimits.MAX_ESCROW_COPIES)));
            }
            Map<Item, Integer> deposit = needsItems && copies > 0 ? depositOf(rewards, copies) : Map.of();
            if (!deposit.isEmpty()) {
                if (actor.player() == null) {
                    return Change.of(EditResult.error(EditResult.ESCROW_NO_PLAYER));
                }
                Optional<Item> missing = firstMissing(actor.player().getInventory(), deposit);
                if (missing.isPresent()) {
                    return Change.of(EditResult.error(EditResult.ESCROW_MISSING,
                            BuiltInRegistries.ITEM.getKey(missing.get()).toString()));
                }
            }
            List<Integer> escrow = new ArrayList<>(rewards.size());
            if (needsItems) {
                for (int index = 0; index < rewards.size(); index++) {
                    boolean item = rewards.get(index) instanceof ItemReward;
                    int held = keep ? quest.escrowAt(index) : 0;
                    escrow.add(Integer.valueOf(item ? held + copies : 0));
                }
            }
            CustomQuest before = quest;
            preview.putQuest(quest.withRewards(rewards, escrow, now));
            return new Change(EditResult.ok(questId), () -> {
                if (!keep && before.escrowed()) {
                    refund(actor, before, previous);
                }
                if (!deposit.isEmpty() && actor.player() != null) {
                    take(actor.player().getInventory(), deposit);
                }
            });
        });
    }

    /** Make a custom quest repeatable (cooldown 60 s .. 30 days) or not (the default). */
    public static EditResult setRepeatable(ServerPlayer player, Identifier quest, boolean repeatable,
                                           int cooldownSeconds) {
        return setRepeatable(Actor.of(player), quest, repeatable, cooldownSeconds);
    }

    public static EditResult setRepeatable(Actor actor, Identifier questId, boolean repeatable, int cooldownSeconds) {
        return edit(actor, questId, preview -> {
            Optional<CustomQuest> quest = preview.quest(questId);
            if (quest.isEmpty()) {
                return Change.of(EditResult.error(EditResult.UNKNOWN_QUEST, String.valueOf(questId)));
            }
            if (repeatable && !CustomLimits.cooldownOk(cooldownSeconds)) {
                return Change.of(EditResult.error(EditResult.COOLDOWN,
                        Integer.toString(CustomLimits.MIN_COOLDOWN_SECONDS),
                        Integer.toString(CustomLimits.MAX_COOLDOWN_SECONDS)));
            }
            preview.putQuest(quest.get().withRepeat(repeatable, cooldownSeconds, System.currentTimeMillis()));
            return Change.of(EditResult.ok(questId));
        });
    }

    // --- the pipeline ----------------------------------------------------------------

    private static EditResult edit(Actor actor, Identifier subject, Function<CustomQuestState, Change> mutation) {
        MinecraftServer server = actor.server();
        if (server == null) {
            return EditResult.error(EditResult.DENIED);
        }
        Optional<EditResult> denied = authorise(actor, subject);
        if (denied.isPresent()) {
            return denied.get();
        }
        CustomQuestState state = CustomQuestState.get(server);
        CustomQuestState preview = state.copy();
        Change change = mutation.apply(preview);
        if (!change.result().ok()) {
            return change.result();
        }
        Optional<EditResult> invalid = previewRejects(server, preview, change.result().id().orElse(null));
        if (invalid.isPresent()) {
            return invalid.get();
        }
        state.adopt(preview);
        try {
            change.onCommit().run();
        } finally {
            changed(server);
            AdminActions.recordQuiet(AdminActions.Kind.EDITOR_MUTATION);
        }
        return change.result();
    }

    /** The store changed: re-validate the definitions and re-sync everyone (at the end of the tick). */
    static void changed(MinecraftServer server) {
        QuestDefinitions.customChanged(server);
        QuestSync.markDefinitionsDirtyAll();
        QuestSync.markAllProgressDirty();
    }

    /**
     * Authorisation (re-derived now from the server-side player), the rate limit, then immutability of the
     * subject — in that order, through the pure {@link EditGate}.
     */
    private static Optional<EditResult> authorise(Actor actor, Identifier subject) {
        ServerPlayer player = actor.player();
        boolean authorised = !actor.admin() && player != null && EditorPermissions.canAuthor(player);
        Object key = player == null ? null : player.getUUID();
        Optional<String> refusal = EditGate.refusal(actor.admin(), authorised, key, System.currentTimeMillis(),
                BURST, MINUTE, subject);
        if (refusal.isEmpty()) {
            return Optional.empty();
        }
        String reason = refusal.get();
        if (reason.equals(EditResult.DENIED) || reason.equals(EditResult.RATE_LIMITED)) {
            return Optional.of(EditResult.error(reason));
        }
        return Optional.of(EditResult.error(reason, subject.toString()));
    }

    /** The open gates of the acting player ({@link AuthorVisibility#EVERYTHING} for the console). */
    private static Predicate<Identifier> gateView(Actor actor) {
        if (actor.player() == null) {
            return AuthorVisibility.EVERYTHING;
        }
        Set<Identifier> open = QuestEngine.openGatesFor(actor.player());
        return open::contains;
    }

    /**
     * Run the definitions pipeline on the previewed store: every custom quest that loads now (and the subject)
     * must still load. The first casualty's validation reason is returned (ids and plain words only).
     */
    static Optional<EditResult> previewRejects(MinecraftServer server, CustomQuestState preview, Identifier subject) {
        QuestDefinitions.Validated validated = QuestDefinitions.preview(server, CustomContent.build(preview));
        Set<Identifier> mustLoad = new LinkedHashSet<>();
        Map<Identifier, Quest> now = QuestDefinitions.questsForServer(server);
        for (Identifier id : preview.quests().keySet()) {
            if (now.containsKey(id) || id.equals(subject)) {
                mustLoad.add(id);
            }
        }
        for (Identifier id : mustLoad) {
            if (!validated.quests().containsKey(id)) {
                String reason = "dropped";
                for (QuestDefinitions.ValidationIssue issue : validated.issues()) {
                    if (issue.id().equals(id)) {
                        reason = issue.detail();
                        break;
                    }
                }
                return Optional.of(EditResult.error(EditResult.INVALID, id.toString(), reason));
            }
        }
        return Optional.empty();
    }

    // --- checks ------------------------------------------------------------------------

    private static Optional<EditResult> checkTitle(String title) {
        Optional<String> cleaned = TextSanitizer.cleanWithin(title, TextSanitizer.TITLE_MAX);
        if (cleaned.isEmpty()) {
            return Optional.of(EditResult.error(EditResult.TEXT_TOO_LONG, Integer.toString(TextSanitizer.TITLE_MAX)));
        }
        return cleaned.get().isEmpty() ? Optional.of(EditResult.error(EditResult.TITLE_EMPTY)) : Optional.empty();
    }

    private static Optional<EditResult> checkIcon(Identifier icon) {
        if (icon == null || BuiltInRegistries.ITEM.containsKey(icon)) {
            return Optional.empty();
        }
        return Optional.of(EditResult.error(EditResult.ICON_UNKNOWN, icon.toString()));
    }

    private static Identifier iconOr(Identifier icon, boolean chapter) {
        if (icon != null) {
            return icon;
        }
        return chapter ? Chapter.DEFAULT_ICON : Quest.DEFAULT_ICON;
    }

    private static EditResult coordinatesError() {
        return EditResult.error(EditResult.COORDINATES, Integer.toString(CustomLimits.MIN_COORD),
                Integer.toString(CustomLimits.MAX_COORD));
    }

    /**
     * The objective rules with translatable keys, cleaning {@code manual} labels into {@code out}: 1..12
     * objectives of the custom types, counts 1..1,000,000, labels sanitised (1..64), every id resolving.
     */
    static Optional<EditResult> checkObjectives(MinecraftServer server, List<ObjectiveSpec> objectives,
                                                List<ObjectiveSpec> out) {
        return checkObjectives(server, AuthorVisibility.EVERYTHING, objectives, out);
    }

    /**
     * {@link #checkObjectives(MinecraftServer, List, List)} as seen by one author: a {@code quest_complete}
     * target hidden from them is refused like an unknown id (J1).
     */
    static Optional<EditResult> checkObjectives(MinecraftServer server, Predicate<Identifier> gateOpen,
                                                List<ObjectiveSpec> objectives, List<ObjectiveSpec> out) {
        if (objectives == null || objectives.isEmpty()) {
            return Optional.of(EditResult.error(EditResult.OBJECTIVE_NONE));
        }
        if (objectives.size() > CustomLimits.MAX_OBJECTIVES) {
            return Optional.of(EditResult.error(EditResult.CAP_OBJECTIVES, Integer.toString(CustomLimits.MAX_OBJECTIVES)));
        }
        for (int index = 0; index < objectives.size(); index++) {
            ObjectiveSpec objective = objectives.get(index);
            String position = Integer.toString(index + 1);
            if (objective == null || !CustomRules.OBJECTIVE_TYPES.contains(objective.typeId())) {
                return Optional.of(EditResult.error(EditResult.OBJECTIVE_TYPE, position,
                        objective == null ? "?" : objective.typeId().toString()));
            }
            int count = objective.target();
            if (count < CustomLimits.MIN_COUNT || count > CustomLimits.MAX_COUNT) {
                return Optional.of(EditResult.error(EditResult.OBJECTIVE_COUNT, position,
                        Integer.toString(CustomLimits.MAX_COUNT)));
            }
            if (objective instanceof ManualObjective manual) {
                Optional<String> label = TextSanitizer.cleanWithin(manual.label(), TextSanitizer.LABEL_MAX);
                if (label.isEmpty() || label.get().isEmpty()) {
                    return Optional.of(EditResult.error(EditResult.OBJECTIVE_LABEL, position,
                            Integer.toString(TextSanitizer.LABEL_MAX)));
                }
                out.add(new ManualObjective(label.get()));
            } else {
                out.add(objective);
            }
        }
        Optional<String> shape = CustomRules.objectives(out);
        if (shape.isPresent()) {
            return Optional.of(EditResult.error(EditResult.BAD_REQUEST));
        }
        return CustomContentRules.objectives(out, new ServerContentLookup(server, gateOpen));
    }

    /** The reward rules with translatable keys (types, count, caps, ids). */
    static Optional<EditResult> checkRewards(MinecraftServer server, List<RewardSpec> rewards, CustomLimits limits) {
        if (rewards == null) {
            return Optional.of(EditResult.error(EditResult.BAD_REQUEST));
        }
        if (rewards.size() > CustomLimits.MAX_REWARDS) {
            return Optional.of(EditResult.error(EditResult.CAP_REWARDS, Integer.toString(CustomLimits.MAX_REWARDS)));
        }
        int stacks = 0;
        long xp = 0L;
        long currency = 0L;
        for (int index = 0; index < rewards.size(); index++) {
            RewardSpec reward = rewards.get(index);
            if (reward == null || !CustomRules.REWARD_TYPES.contains(reward.typeId())) {
                return Optional.of(EditResult.error(EditResult.REWARD_TYPE, Integer.toString(index + 1),
                        reward == null ? "?" : reward.typeId().toString()));
            }
            if (reward instanceof ItemReward) {
                stacks++;
            } else if (reward instanceof XpReward experience) {
                xp += experience.amount();
            } else if (reward instanceof CurrencyReward money) {
                currency += money.amount();
            }
        }
        if (stacks > limits.maxStacks()) {
            return Optional.of(EditResult.error(EditResult.CAP_STACKS, Integer.toString(limits.maxStacks())));
        }
        if (xp > limits.maxXp()) {
            return Optional.of(EditResult.error(EditResult.CAP_XP, Integer.toString(limits.maxXp())));
        }
        if (currency > limits.maxCurrency()) {
            return Optional.of(EditResult.error(EditResult.CAP_CURRENCY, Long.toString(limits.maxCurrency())));
        }
        return CustomContentRules.rewards(rewards, new ServerContentLookup(server));
    }

    /** The dependency graph of everything loaded, with the previewed custom quests overriding (minus the subject). */
    private static Map<Identifier, Set<Identifier>> graph(MinecraftServer server, CustomQuestState preview,
                                                          Identifier subject) {
        Map<Identifier, Set<Identifier>> graph =
                new LinkedHashMap<>(CustomGraph.dependencies(QuestDefinitions.questsForServer(server).values()));
        for (CustomQuest quest : preview.quests().values()) {
            quest.toQuest().ifPresent(q -> graph.put(q.id(), CustomGraph.dependenciesOf(q)));
        }
        graph.remove(subject);
        return graph;
    }

    /** A custom quest outside {@code ids} that names one of them in a {@code quest_complete} objective. */
    private static Optional<EditResult> referencedFromOutside(CustomQuestState preview, Set<Identifier> ids) {
        for (CustomQuest other : preview.quests().values()) {
            if (ids.contains(other.id())) {
                continue;
            }
            Optional<Quest> quest = other.toQuest();
            if (quest.isEmpty()) {
                continue;
            }
            for (ObjectiveSpec objective : quest.get().objectives()) {
                if (objective instanceof QuestCompleteObjective complete
                        && ids.contains(complete.quest())) {
                    return Optional.of(EditResult.error(EditResult.REFERENCED, complete.quest().toString(),
                            other.id().toString()));
                }
            }
        }
        return Optional.empty();
    }

    /** Remove {@code ids} from every remaining custom quest's prerequisites. */
    private static void dropPrerequisites(CustomQuestState preview, Set<Identifier> ids) {
        long now = System.currentTimeMillis();
        for (CustomQuest other : List.copyOf(preview.quests().values())) {
            if (other.prerequisites().stream().anyMatch(ids::contains)) {
                List<Identifier> kept = new ArrayList<>(other.prerequisites());
                kept.removeIf(ids::contains);
                preview.putQuest(other.withPrerequisites(kept, now));
            }
        }
    }

    /** After a quest is gone: refund its escrow and prune its progress and ledger rows (every player). */
    private static void afterDelete(Actor actor, CustomQuest quest) {
        if (quest.escrowed()) {
            refund(actor, quest, quest.rewards().orElse(List.of()));
        }
        QuestProgressState.get(actor.server()).pruneQuest(quest.id());
    }

    private static Optional<CustomChapter> chapterOf(CustomQuestState state, Identifier chapterId) {
        if (chapterId == null || !QuestSource.CUSTOM_NAMESPACE.equals(chapterId.getNamespace())
                || chapterId.getPath().indexOf('/') >= 0) {
            return Optional.empty();
        }
        return state.chapter(chapterId.getPath());
    }

    // --- escrow ----------------------------------------------------------------------------

    /** The item rewards of a list, by index (the escrow identity of a reward set). */
    private static Map<Integer, ItemReward> itemRewards(List<RewardSpec> rewards) {
        Map<Integer, ItemReward> out = new LinkedHashMap<>();
        for (int index = 0; index < rewards.size(); index++) {
            if (rewards.get(index) instanceof ItemReward item) {
                out.put(Integer.valueOf(index), item);
            }
        }
        return out;
    }

    private static Map<Item, Integer> depositOf(List<RewardSpec> rewards, int copies) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (ItemReward reward : itemRewards(rewards).values()) {
            Item item = BuiltInRegistries.ITEM.getValue(reward.item());
            out.merge(item, Integer.valueOf(reward.count() * copies), Integer::sum);
        }
        return out;
    }

    private static boolean plain(ItemStack stack, Item item) {
        return !stack.isEmpty() && stack.is(item) && stack.getComponentsPatch().isEmpty();
    }

    private static Optional<Item> firstMissing(Inventory inventory, Map<Item, Integer> needed) {
        Map<Item, Integer> held = new HashMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            for (Item item : needed.keySet()) {
                if (plain(stack, item)) {
                    held.merge(item, Integer.valueOf(stack.getCount()), Integer::sum);
                }
            }
        }
        for (Map.Entry<Item, Integer> need : needed.entrySet()) {
            if (held.getOrDefault(need.getKey(), Integer.valueOf(0)).intValue() < need.getValue().intValue()) {
                return Optional.of(need.getKey());
            }
        }
        return Optional.empty();
    }

    private static void take(Inventory inventory, Map<Item, Integer> needed) {
        for (Map.Entry<Item, Integer> need : needed.entrySet()) {
            int remaining = need.getValue().intValue();
            for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (plain(stack, need.getKey())) {
                    int taken = Math.min(remaining, stack.getCount());
                    stack.shrink(taken);
                    remaining -= taken;
                }
            }
        }
        inventory.setChanged();
    }

    /**
     * Return a quest's remaining escrow: to its author (inventory, the rest through the owed queue — also when
     * they are offline), or, when the author is unknown (erased), to the acting player. With neither (a console
     * change to an author-less quest) the deposit is discarded and only that count is logged.
     */
    private static void refund(Actor actor, CustomQuest quest, List<RewardSpec> rewards) {
        MinecraftServer server = actor.server();
        Optional<UUID> recipient = quest.createdBy().isPresent() ? quest.createdBy() : actor.id();
        int discarded = 0;
        for (Map.Entry<Integer, ItemReward> entry : itemRewards(rewards).entrySet()) {
            int copies = quest.escrowAt(entry.getKey().intValue());
            ItemReward reward = entry.getValue();
            if (copies <= 0 || !BuiltInRegistries.ITEM.containsKey(reward.item())) {
                continue;
            }
            int total = copies * reward.count();
            if (recipient.isEmpty()) {
                discarded += total;
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getValue(reward.item());
            ServerPlayer online = server.getPlayerList().getPlayer(recipient.get());
            int remaining = total;
            if (OwedDelivery.canReceive(online)) {
                remaining = OwedDelivery.insert(online, item, total, DataComponentPatch.EMPTY);
            }
            if (remaining > 0) {
                OwedDelivery.keepItem(server, recipient.get(), online, item, remaining, DataComponentPatch.EMPTY,
                        quest.id());
            }
        }
        if (discarded > 0) {
            NeroQuestsCommon.LOGGER.info("[NeroQuests] Escrow of custom quest {} had no known author to refund; "
                    + "{} item(s) discarded.", quest.id(), Integer.valueOf(discarded));
        }
    }

    /**
     * Called by {@link ItemReward#grant} for a custom quest, once per item reward per paid completion: may this
     * reward be paid now? An escrowed quest pays from its deposit (one copy consumed per completion; an empty
     * deposit pays nothing and tells the recipient, and the author if online). A quest that is not escrowed
     * spawns its items — unless the server is in escrow mode, where it pays nothing until re-saved with a
     * deposit.
     */
    public static boolean takeEscrow(RewardContext context) {
        MinecraftServer server = context.server();
        CustomQuestState state = CustomQuestState.get(server);
        Optional<CustomQuest> found = state.quest(context.quest().id());
        if (found.isEmpty()) {
            return false;
        }
        CustomQuest quest = found.get();
        if (!quest.escrowed()) {
            if (!CustomLimits.current().escrow()) {
                return true;
            }
            tell(context.player(), "message.neroquests.custom.escrow_missing");
            return false;
        }
        int index = context.rewardIndex();
        int held = quest.escrowAt(index);
        if (held <= 0) {
            tell(context.player(), "message.neroquests.custom.escrow_empty");
            quest.createdBy().ifPresent(author -> {
                ServerPlayer online = server.getPlayerList().getPlayer(author);
                if (online != null && !author.equals(context.playerId())) {
                    online.sendSystemMessage(Component.translatable("message.neroquests.custom.escrow_empty_author",
                            quest.id().toString()).withStyle(ChatFormatting.GOLD));
                }
            });
            return false;
        }
        List<Integer> escrow = new ArrayList<>(quest.escrow());
        escrow.set(index, Integer.valueOf(held - 1));
        // The synced escrow_copies figure refreshes with the next definitions change (not per payout).
        state.putQuest(quest.withEscrow(escrow));
        return true;
    }

    private static void tell(ServerPlayer player, String key) {
        if (player != null) {
            player.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.GOLD));
        }
    }

    // --- repeatables ------------------------------------------------------------------------------

    private static Map<Identifier, Long> repeatCooldowns(CustomQuestState state) {
        Map<Identifier, Long> out = new HashMap<>();
        for (CustomQuest quest : state.quests().values()) {
            if (quest.repeatable() && quest.cooldownSeconds() > 0) {
                out.put(quest.id(), Long.valueOf(quest.cooldownSeconds() * 1000L));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Re-arm every repeatable custom quest {@code player} completed at least one cooldown ago (called from the
     * 1 Hz sweep before evaluation). Cheap: walks only the repeatable quests.
     */
    public static void rearmDue(MinecraftServer server, ServerPlayer player) {
        Map<Identifier, Long> due = repeats;
        if (due.isEmpty()) {
            return;
        }
        QuestProgressState state = QuestProgressState.get(server);
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<Identifier, Long> entry : due.entrySet()) {
            Optional<QuestProgress> progress = state.progress(id, entry.getKey());
            if (progress.isPresent() && progress.get().isComplete()
                    && now - progress.get().completedAt() >= entry.getValue().longValue()) {
                changed |= state.rearmRepeatable(id, entry.getKey());
            }
        }
        if (changed) {
            QuestSync.markProgressDirty(id);
        }
    }

    // --- sync extras (server-resolved fields on custom entries) -----------------------------------

    /** Extra synced fields of a custom quest ({@code repeatable}, {@code cooldown_seconds}, escrow), or null. */
    public static JsonObject questSyncExtra(MinecraftServer server, Identifier questId) {
        if (QuestSource.of(questId) != QuestSource.CUSTOM) {
            return null;
        }
        Optional<CustomQuest> found = CustomQuestState.get(server).quest(questId);
        if (found.isEmpty()) {
            return null;
        }
        CustomQuest quest = found.get();
        JsonObject extra = new JsonObject();
        extra.addProperty("repeatable", quest.repeatable());
        if (quest.repeatable()) {
            extra.addProperty("cooldown_seconds", quest.cooldownSeconds());
        }
        if (quest.escrowed()) {
            int copies = Integer.MAX_VALUE;
            for (Integer index : itemRewards(quest.rewards().orElse(List.of())).keySet()) {
                copies = Math.min(copies, quest.escrowAt(index.intValue()));
            }
            extra.addProperty("escrowed", true);
            extra.addProperty("escrow_copies", copies == Integer.MAX_VALUE ? 0 : copies);
        }
        return extra;
    }

    /** Extra synced fields of a custom chapter ({@code order}), or null. */
    public static JsonObject chapterSyncExtra(MinecraftServer server, Identifier chapterId) {
        if (QuestSource.of(chapterId) != QuestSource.CUSTOM) {
            return null;
        }
        Optional<CustomChapter> chapter = CustomQuestState.get(server).chapter(chapterId.getPath());
        if (chapter.isEmpty()) {
            return null;
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("order", chapter.get().order());
        return extra;
    }

    // --- admin -----------------------------------------------------------------------------

    /** Add a player to the editor allow-list (command, op 3). */
    public static boolean allow(MinecraftServer server, UUID player) {
        boolean changed = CustomQuestState.get(server).allow(Objects.requireNonNull(player));
        if (changed) {
            EditorSync.markDirty(player);
        }
        return changed;
    }

    /** Remove a player from the editor allow-list (command, op 3). */
    public static boolean deny(MinecraftServer server, UUID player) {
        boolean changed = CustomQuestState.get(server).deny(Objects.requireNonNull(player));
        if (changed) {
            EditorSync.markDirty(player);
        }
        return changed;
    }

    /** Lock or unlock the editor (command, op 4). */
    public static boolean setLocked(MinecraftServer server, boolean locked) {
        boolean changed = CustomQuestState.get(server).setLocked(locked);
        if (changed) {
            EditorSync.markAllDirty();
        }
        return changed;
    }

    /** Lines for {@code /neroquests reload-check}: editor lock, allow-list size, store counts vs caps. */
    private static List<Component> reloadCheckLines(MinecraftServer server) {
        CustomQuestState state = CustomQuestState.get(server);
        CustomLimits limits = CustomLimits.current();
        List<Component> out = new ArrayList<>(3);
        out.add(Component.translatable("command.neroquests.reload.editor",
                Component.translatable(state.isLocked()
                        ? "command.neroquests.reload.editor.locked"
                        : "command.neroquests.reload.editor.unlocked"),
                Integer.valueOf(state.allowListSize()),
                Component.translatable(limits.escrow()
                        ? "command.neroquests.reload.editor.escrow_on"
                        : "command.neroquests.reload.editor.escrow_off")));
        out.add(Component.translatable("command.neroquests.reload.custom",
                Integer.valueOf(state.chapters().size()), Integer.valueOf(limits.maxChapters()),
                Integer.valueOf(state.quests().size()), Integer.valueOf(limits.maxQuests()),
                Integer.valueOf(AdminActions.count(AdminActions.Kind.EDITOR_MUTATION))));
        return out;
    }
}
