package za.co.neroland.neroquests.command;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.contract.ContractDefinitions;
import za.co.neroland.neroquests.contract.DailyContract;
import za.co.neroland.neroquests.contract.DailyContracts;
import za.co.neroland.neroquests.custom.CustomChapter;
import za.co.neroland.neroquests.custom.CustomPackIO;
import za.co.neroland.neroquests.custom.CustomQuestState;
import za.co.neroland.neroquests.custom.CustomQuests;
import za.co.neroland.neroquests.custom.EditResult;
import za.co.neroland.neroquests.custom.RateLimiter;
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
import za.co.neroland.neroquests.quest.QuestStates;
import za.co.neroland.neroquests.quest.engine.MissingContent;
import za.co.neroland.neroquests.quest.engine.QuestEngine;
import za.co.neroland.neroquests.quest.objective.ManualObjective;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;
import za.co.neroland.neroquests.telemetry.NeroQuestsTelemetry;

/**
 * The {@code /neroquests} admin command tree — the operator's view of, and lever on, the quest
 * engine. Registered identically from all three loaders (NeoForge/Forge {@code RegisterCommandsEvent},
 * Fabric {@code CommandRegistrationCallback}), so the tree itself lives here in common.
 *
 * <pre>
 * /neroquests grant   &lt;player&gt; &lt;quest&gt;
 * /neroquests revoke  &lt;player&gt; &lt;quest&gt; [--repay]
 * /neroquests reset   &lt;player&gt; [&lt;quest&gt;]
 * /neroquests list    [&lt;player&gt;]
 * /neroquests reload-check
 * /neroquests export  &lt;player&gt;
 * /neroquests manual complete &lt;quest&gt; &lt;player&gt;
 * /neroquests editor allow|deny|check &lt;player&gt;
 * /neroquests editor list
 * /neroquests editor lock|unlock
 * /neroquests custom export [&lt;chapter&gt;]
 * /neroquests custom import &lt;folder&gt; [--dry-run]
 * /neroquests custom delete &lt;id&gt; [confirm]
 * </pre>
 *
 * <p>Everything here is server-authoritative. {@code list} and {@code reload-check} are gated at
 * Core's own admin level ({@code Commands.LEVEL_GAMEMASTERS}, permission level 2), matching
 * {@code /neroland}. The commands that change or reveal one player's data — {@code grant},
 * {@code revoke}, {@code reset} and {@code export} — need permission level 3 <em>and</em> a player or
 * the server console as the source (AUD-25, {@link #trustedAdmin}): never a command block, a
 * command-block minecart or a function. The editor commands follow the same rule: {@code editor
 * allow|deny|check|list}, {@code custom export} and {@code custom delete} at level 3, {@code editor lock|unlock}
 * and {@code custom import} at level 4 ({@link #trustedOwner}).
 *
 * <p><b>Arguments.</b> Quests and players are plain strings with live suggestion providers rather
 * than registry/entity argument types: a quest id is a datapack id (never a registry entry) and the
 * privacy commands must reach <em>offline</em> players, which an entity selector cannot. A player
 * is therefore given as an online player's name <em>or</em> as a raw UUID — the same shape Core's
 * {@code /neroland data erase &lt;uuid&gt;} takes. A quest id without a namespace is read as
 * {@code neroquests:}, mirroring Core's {@code parseGateId}.
 *
 * <p><b>Privacy (POPIA/GDPR).</b> Command output goes to the invoker only — every
 * {@code sendSuccess} passes {@code false} for "broadcast to ops", which also keeps player-scoped
 * results out of {@code latest.log} under the {@code logAdminCommands} game rule. Nothing here logs
 * player identity. {@code export} is the data-access surface promised in {@code PRIVACY.md}: it
 * shows one player's own rows and nobody else's.
 *
 * <p>Server thread only.
 */
public final class QuestCommands {

    /** Chat is not a file transfer: an export longer than this is cut off with a note. */
    private static final int EXPORT_CHAR_LIMIT = 32_000;

    /**
     * Optional trailing flag of {@code revoke}/{@code reset <player> <quest>}: also remove the quest
     * from the paid ledger, so its next completion pays again. Read off the end of the greedy quest
     * argument, because Brigadier cannot place a literal after a greedy string.
     */
    static final String REPAY_FLAG = "--repay";

    private QuestCommands() {
    }

    /**
     * AUD-25: may this source run {@code grant}/{@code revoke}/{@code reset}/{@code export}? Permission
     * level 3 ({@code LEVEL_ADMINS}) and either a player, or the server console / RCON.
     *
     * <p>26.x keeps a source's underlying {@code CommandSource} private, so the console is recognised
     * by what only it has together: no entity, owner permission level (4), output not suppressed, and
     * the console's ("Server") or RCON's ("Rcon") name. A command block runs at level 2 (refused by the
     * level alone even if renamed "Server"), a command-block minecart has a non-player entity, and a
     * function runs with suppressed output — all refused.
     */
    static boolean trustedAdmin(CommandSourceStack source) {
        if (!Commands.LEVEL_ADMINS.check(source.permissions())) {
            return false;
        }
        if (source.getEntity() instanceof ServerPlayer) {
            return true;
        }
        return source.getEntity() == null
                && !source.isSilent()
                && Commands.LEVEL_OWNERS.check(source.permissions())
                && ("Server".equals(source.getTextName()) || "Rcon".equals(source.getTextName()));
    }

    /**
     * {@link #trustedAdmin} at permission level 4: {@code editor lock|unlock} and {@code custom import}.
     */
    static boolean trustedOwner(CommandSourceStack source) {
        return trustedAdmin(source) && Commands.LEVEL_OWNERS.check(source.permissions());
    }

    /** Manual completions per invoker: 10 a minute (§13). */
    private static final RateLimiter MANUAL_LIMIT = new RateLimiter(10, 60_000L);

    /** How long a {@code custom delete} waits for its {@code confirm}. */
    static final long DELETE_CONFIRM_MILLIS = 30_000L;

    /** Per invoker (player UUID or the console marker): the id awaiting confirmation and when it expires. */
    private static final Map<String, PendingDelete> PENDING_DELETES = new HashMap<>();

    private record PendingDelete(Identifier id, long expiresAt) {
    }

    /** The trailing flag of {@code custom import}: report only. */
    static final String DRY_RUN_FLAG = "--dry-run";

    /**
     * C5/D2: may this source tick {@code manual} objectives? A player who may author quests (the full
     * {@link EditorPermissions#canAuthor} rule: creative / operator level / allow-list, minus the lock), or the
     * console under the same rule as the other mutating commands. Re-checked when the command runs.
     */
    static boolean mayTickManual(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return EditorPermissions.canAuthor(player);
        }
        return trustedAdmin(source);
    }

    /** Whether {@code quest} has at least one {@code manual} objective. */
    static boolean hasManualObjective(Quest quest) {
        for (ObjectiveSpec objective : quest.objectives()) {
            if (objective instanceof ManualObjective) {
                return true;
            }
        }
        return false;
    }

    // --- tree ---------------------------------------------------------------

    /** Builds {@code /neroquests …}. Called once per loader from its command-registration hook. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Admin subcommands carry the permission check each; `contracts` is open to every player.
        dispatcher.register(Commands.literal("neroquests")
                .then(Commands.literal("contracts")
                        .executes(ctx -> runSafely(ctx.getSource(), "contracts",
                                () -> listContracts(ctx.getSource())))
                        .then(Commands.literal("submit")
                                .then(Commands.argument("contract", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> {
                                            MinecraftServer server = ctx.getSource().getServer();
                                            if (server != null && DailyContracts.available()) {
                                                for (DailyContract contract : DailyContracts.drawn(server)) {
                                                    builder.suggest(contract.id().toString());
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> runSafely(ctx.getSource(), "contracts submit",
                                                () -> submitContract(ctx))))))
                .then(Commands.literal("grant")
                        .requires(QuestCommands::trustedAdmin)
                        .then(playerArgument()
                                .then(questArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "grant",
                                                () -> grant(ctx))))))
                .then(Commands.literal("revoke")
                        .requires(QuestCommands::trustedAdmin)
                        .then(playerArgument()
                                .then(questArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "revoke",
                                                () -> revokeQuest(ctx))))))
                // `reset <player> <quest>` is deliberately the same implementation as `revoke`:
                // one behaviour, two names, so neither spelling surprises an operator.
                .then(Commands.literal("reset")
                        .requires(QuestCommands::trustedAdmin)
                        .then(playerArgument()
                                .executes(ctx -> runSafely(ctx.getSource(), "reset",
                                        () -> resetPlayer(ctx)))
                                .then(questArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "reset quest",
                                                () -> revokeQuest(ctx))))))
                .then(Commands.literal("list")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> runSafely(ctx.getSource(), "list",
                                () -> listQuests(ctx.getSource())))
                        .then(playerArgument()
                                .executes(ctx -> runSafely(ctx.getSource(), "list player",
                                        () -> listForPlayer(ctx)))))
                .then(Commands.literal("reload-check")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> runSafely(ctx.getSource(), "reload-check",
                                () -> reloadCheck(ctx.getSource()))))
                .then(Commands.literal("export")
                        .requires(QuestCommands::trustedAdmin)
                        .then(playerArgument()
                                .executes(ctx -> runSafely(ctx.getSource(), "export",
                                        () -> export(ctx)))))
                // C5: tick a quest's manual objectives for an online player.
                .then(Commands.literal("manual")
                        .requires(QuestCommands::mayTickManual)
                        .then(Commands.literal("complete")
                                .then(Commands.argument("quest", IdentifierArgument.id())
                                        .suggests((ctx, builder) -> {
                                            MinecraftServer server = ctx.getSource().getServer();
                                            if (server != null) {
                                                for (Quest quest : QuestDefinitions.questsForServer(server).values()) {
                                                    if (hasManualObjective(quest)) {
                                                        builder.suggest(quest.id().toString());
                                                    }
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .then(playerArgument()
                                                .executes(ctx -> runSafely(ctx.getSource(), "manual complete",
                                                        () -> manualComplete(ctx)))))))
                // D2/F1: the editor allow-list and lock.
                .then(Commands.literal("editor")
                        .then(Commands.literal("allow")
                                .requires(QuestCommands::trustedAdmin)
                                .then(playerArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "editor allow",
                                                () -> editorAllow(ctx, true)))))
                        .then(Commands.literal("deny")
                                .requires(QuestCommands::trustedAdmin)
                                .then(playerArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "editor deny",
                                                () -> editorAllow(ctx, false)))))
                        .then(Commands.literal("check")
                                .requires(QuestCommands::trustedAdmin)
                                .then(playerArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "editor check",
                                                () -> editorCheck(ctx)))))
                        .then(Commands.literal("list")
                                .requires(QuestCommands::trustedAdmin)
                                .executes(ctx -> runSafely(ctx.getSource(), "editor list",
                                        () -> editorList(ctx.getSource()))))
                        .then(Commands.literal("lock")
                                .requires(QuestCommands::trustedOwner)
                                .executes(ctx -> runSafely(ctx.getSource(), "editor lock",
                                        () -> editorLock(ctx.getSource(), true))))
                        .then(Commands.literal("unlock")
                                .requires(QuestCommands::trustedOwner)
                                .executes(ctx -> runSafely(ctx.getSource(), "editor unlock",
                                        () -> editorLock(ctx.getSource(), false)))))
                // F2: custom quests in and out of datapacks, and deletion with confirmation.
                .then(Commands.literal("custom")
                        .then(Commands.literal("export")
                                .requires(QuestCommands::trustedAdmin)
                                .executes(ctx -> runSafely(ctx.getSource(), "custom export",
                                        () -> customExport(ctx.getSource(), null)))
                                .then(Commands.argument("chapter", IdentifierArgument.id())
                                        .suggests((ctx, builder) -> {
                                            MinecraftServer server = ctx.getSource().getServer();
                                            if (server != null) {
                                                for (CustomChapter chapter : CustomQuestState.get(server).chapters()
                                                        .values()) {
                                                    builder.suggest(chapter.id().toString());
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> runSafely(ctx.getSource(), "custom export",
                                                () -> customExport(ctx.getSource(),
                                                        IdentifierArgument.getId(ctx, "chapter"))))))
                        .then(Commands.literal("import")
                                .requires(QuestCommands::trustedOwner)
                                .then(Commands.argument("folder", StringArgumentType.greedyString())
                                        .executes(ctx -> runSafely(ctx.getSource(), "custom import",
                                                () -> customImport(ctx)))))
                        .then(Commands.literal("delete")
                                .requires(QuestCommands::trustedAdmin)
                                .then(Commands.argument("id", IdentifierArgument.id())
                                        .suggests((ctx, builder) -> {
                                            MinecraftServer server = ctx.getSource().getServer();
                                            if (server != null) {
                                                CustomQuestState state = CustomQuestState.get(server);
                                                state.chapters().values().forEach(c -> builder.suggest(c.id().toString()));
                                                state.quests().keySet().forEach(id -> builder.suggest(id.toString()));
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> runSafely(ctx.getSource(), "custom delete",
                                                () -> customDelete(ctx, false)))
                                        .then(Commands.literal("confirm")
                                                .executes(ctx -> runSafely(ctx.getSource(), "custom delete",
                                                        () -> customDelete(ctx, true))))))));
    }

    /**
     * {@code <player>} — an online player's name or a raw UUID, suggesting the names of everyone
     * currently online.
     */
    private static RequiredArgumentBuilder<CommandSourceStack, String> playerArgument() {
        return Commands.argument("player", StringArgumentType.string())
                .suggests((ctx, builder) -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    if (server != null) {
                        String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
                        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                            String name = online.getName().getString();
                            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                                builder.suggest(name);
                            }
                        }
                    }
                    return builder.buildFuture();
                });
    }

    /**
     * {@code <quest>} — a loaded quest id, suggesting the whole loaded set. Greedy because a quest
     * id contains {@code :} and {@code /}, which Brigadier will not read as a bare word, and because
     * a quest is always the last argument of its subcommand.
     */
    private static RequiredArgumentBuilder<CommandSourceStack, String> questArgument() {
        return Commands.argument("quest", StringArgumentType.greedyString())
                .suggests((ctx, builder) -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    if (server != null) {
                        String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
                        for (Identifier id : QuestDefinitions.questsForServer(server).keySet()) {
                            String text = id.toString();
                            if (text.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                                builder.suggest(text);
                            }
                        }
                    }
                    return builder.buildFuture();
                });
    }

    // --- grant --------------------------------------------------------------

    /**
     * Completes a quest for a player <em>through the normal completion path</em>: the store is
     * marked, {@code QuestCompletionListeners} fires (so Stage-5 rewards pay out exactly as if the
     * player had finished it), the cascade re-settles any {@code quest_complete} objective that was
     * waiting on it, and the client is re-synced — all inside
     * {@link QuestEngine#adminComplete(ServerPlayer, Quest)}.
     *
     * <p>Rewards target the player, so this one needs them online.
     */
    private static int grant(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Quest quest = resolveQuest(source, server, ctx);
        if (quest == null) {
            return 0;
        }
        String raw = StringArgumentType.getString(ctx, "player").trim();
        ServerPlayer target = onlinePlayer(server, raw);
        if (target == null) {
            source.sendFailure(resolvePlayerId(raw) == null
                    ? Component.translatable("command.neroquests.player.unknown")
                    : Component.translatable("command.neroquests.player.offline"));
            return 0;
        }
        if (quest.modAbsent()) {
            // B3: a pack quest shown only because of show_when_absent can never be completed.
            source.sendFailure(Component.translatable("command.neroquests.grant.mod_absent", quest.id().toString()));
            return 0;
        }
        if (!QuestEngine.adminComplete(target, quest)) {
            source.sendFailure(Component.translatable("command.neroquests.grant.already",
                    quest.id().toString()));
            return 0;
        }
        AdminActions.record(AdminActions.Kind.GRANT);
        String questId = quest.id().toString();
        source.sendSuccess(() -> Component.translatable("command.neroquests.grant.success", questId), false);
        return Command.SINGLE_SUCCESS;
    }

    // --- manual complete <quest> <player> -----------------------------------

    /**
     * Tick every {@code manual} objective of a quest for an online player (C5). The quest must be
     * available to them (visible, prerequisites met) and not complete; it then completes through the
     * engine's normal path if its other objectives are done too. Counted as an admin mutation, never named.
     */
    private static int manualComplete(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        if (!mayTickManual(source)) {
            // Re-derived now: a player who lost creative since the command tree was sent is refused.
            source.sendFailure(Component.translatable("command.neroquests.manual.denied"));
            return 0;
        }
        if (!MANUAL_LIMIT.tryAcquire(invokerKey(source), System.currentTimeMillis())) {
            source.sendFailure(Component.translatable("command.neroquests.manual.rate_limited"));
            return 0;
        }
        Identifier id = IdentifierArgument.getId(ctx, "quest");
        Quest quest = QuestDefinitions.questsForServer(server).get(id);
        if (quest == null) {
            source.sendFailure(Component.translatable("command.neroquests.quest.unknown", id.toString()));
            return 0;
        }
        String questId = quest.id().toString();
        if (!hasManualObjective(quest)) {
            source.sendFailure(Component.translatable("command.neroquests.manual.none", questId));
            return 0;
        }
        String raw = StringArgumentType.getString(ctx, "player").trim();
        ServerPlayer target = onlinePlayer(server, raw);
        if (target == null) {
            source.sendFailure(resolvePlayerId(raw) == null
                    ? Component.translatable("command.neroquests.player.unknown")
                    : Component.translatable("command.neroquests.player.offline"));
            return 0;
        }
        QuestProgressState state = QuestProgressState.get(server);
        Set<Identifier> playerCompleted = state.completedQuests(target.getUUID());
        Set<Identifier> serverCompleted = state.completedServerQuests();
        boolean done = quest.scope() == QuestScope.SERVER
                ? serverCompleted.contains(quest.id())
                : playerCompleted.contains(quest.id());
        if (done) {
            source.sendFailure(Component.translatable("command.neroquests.grant.already", questId));
            return 0;
        }
        if (!QuestStates.isAvailable(quest, QuestDefinitions.questsForServer(server), playerCompleted,
                serverCompleted, QuestEngine.openGatesFor(target))) {
            source.sendFailure(Component.translatable("command.neroquests.manual.locked", questId));
            return 0;
        }
        boolean completed = QuestEngine.manualComplete(target, quest);
        AdminActions.record(AdminActions.Kind.MANUAL_COMPLETE);
        source.sendSuccess(() -> Component.translatable(completed
                ? "command.neroquests.manual.completed"
                : "command.neroquests.manual.ticked", questId), false);
        return Command.SINGLE_SUCCESS;
    }

    // --- revoke / reset <player> <quest> -------------------------------------

    /**
     * Drops a player's completion <em>and</em> objective counters for one quest, then re-syncs them
     * if they are online. Rewards already paid out are not clawed back — the reward types write into
     * inventories, XP, Core gates, currency and reputation, none of which this mod owns.
     *
     * <p>A {@code scope: server} quest is world-shared, so revoking it clears the shared row (the
     * named player only selects which section of the store is meant) and everyone is re-synced.
     *
     * <p><b>Reward integrity (AUD-01).</b> The quest is marked revoked-until-changed: the engine will
     * not re-complete it until one of its objective counters differs from the snapshot taken here,
     * so the next sweep cannot simply measure it back. Its id stays in the paid ledger — a later
     * re-completion pays nothing — unless the operator adds {@code --repay}. Completed dependents are
     * not cascaded; the operator is told how many stay complete (AUD-37).
     */
    private static int revokeQuest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        String rawQuest = StringArgumentType.getString(ctx, "quest").trim();
        boolean repay = hasRepayFlag(rawQuest);
        Quest quest = resolveQuest(source, server, repay ? stripRepayFlag(rawQuest) : rawQuest);
        if (quest == null) {
            return 0;
        }
        UUID player = resolvePlayer(source, server, ctx);
        if (player == null) {
            return 0;
        }
        QuestProgressState state = QuestProgressState.get(server);
        boolean serverScope = quest.scope() == QuestScope.SERVER;
        QuestProgress before = (serverScope
                ? state.serverProgress(quest.id())
                : state.progress(player, quest.id())).orElse(QuestProgress.EMPTY);
        List<Integer> targets = new ArrayList<>(quest.objectives().size());
        for (ObjectiveSpec objective : quest.objectives()) {
            targets.add(Integer.valueOf(Math.max(1, objective.target())));
        }
        List<Integer> snapshot = QuestProgressState.revocationSnapshot(before, targets);
        boolean changed = serverScope
                ? state.revokeServerQuest(quest.id(), snapshot, repay)
                : state.revokeQuest(player, quest.id(), snapshot, repay);
        String questId = quest.id().toString();
        if (!changed) {
            source.sendFailure(Component.translatable("command.neroquests.revoke.none", questId));
            return 0;
        }
        AdminActions.record(repay ? AdminActions.Kind.REVOKE_REPAY : AdminActions.Kind.REVOKE);
        if (serverScope) {
            QuestSync.syncProgressAll(server);
        } else {
            ServerPlayer online = onlineByUuid(server, player);
            if (online != null) {
                QuestSync.syncProgressTo(online);
            }
        }
        source.sendSuccess(() -> Component.translatable("command.neroquests.revoke.success", questId), false);
        source.sendSuccess(() -> Component.translatable(repay
                ? "command.neroquests.revoke.repay"
                : "command.neroquests.revoke.held", questId), false);
        int dependents = completedDependents(state, player, quest.id(), QuestDefinitions.questsForServer(server));
        if (dependents > 0) {
            source.sendSuccess(() -> Component.translatable("command.neroquests.revoke.dependents",
                    Integer.valueOf(dependents)), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Whether the greedy quest argument ends with the {@value #REPAY_FLAG} flag. */
    static boolean hasRepayFlag(String raw) {
        String trimmed = raw.trim();
        return trimmed.equals(REPAY_FLAG) || trimmed.endsWith(" " + REPAY_FLAG);
    }

    /** The quest argument without its trailing {@value #REPAY_FLAG} flag. */
    static String stripRepayFlag(String raw) {
        String trimmed = raw.trim();
        return trimmed.substring(0, trimmed.length() - REPAY_FLAG.length()).trim();
    }

    /**
     * How many quests that depend on {@code revoked} — through a prerequisite or a
     * {@code quest_complete} objective, directly or transitively — are complete for the same holder
     * (AUD-37). They are deliberately left complete; the operator is only told.
     */
    static int completedDependents(QuestProgressState state, UUID player, Identifier revoked,
                                   Map<Identifier, Quest> quests) {
        Set<Identifier> dependents = new LinkedHashSet<>();
        Deque<Identifier> pending = new ArrayDeque<>();
        pending.add(revoked);
        while (!pending.isEmpty()) {
            Identifier current = pending.poll();
            for (Quest candidate : quests.values()) {
                if (!dependents.contains(candidate.id()) && dependsOn(candidate, current)) {
                    dependents.add(candidate.id());
                    pending.add(candidate.id());
                }
            }
        }
        int complete = 0;
        for (Identifier id : dependents) {
            Quest dependent = quests.get(id);
            boolean done = dependent != null && (dependent.scope() == QuestScope.SERVER
                    ? state.isServerComplete(id)
                    : state.isComplete(player, id));
            if (done) {
                complete++;
            }
        }
        return complete;
    }

    private static boolean dependsOn(Quest candidate, Identifier quest) {
        if (candidate.prerequisites().contains(quest)) {
            return true;
        }
        for (ObjectiveSpec objective : candidate.objectives()) {
            if (objective instanceof QuestCompleteObjective complete && complete.quest().equals(quest)) {
                return true;
            }
        }
        return false;
    }

    // --- reset <player> ------------------------------------------------------

    /** Full POPIA/GDPR-shaped wipe of one player's stored quest progress; shared rows are untouched. */
    private static int resetPlayer(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        UUID player = resolvePlayer(source, server, ctx);
        if (player == null) {
            return 0;
        }
        QuestProgressState resetState = QuestProgressState.get(server);
        resetState.forgetPlayer(player);
        resetState.backupNow(server); // the recovery backup holds the same rows (AUD-16)
        AdminActions.record(AdminActions.Kind.RESET_PLAYER);
        ServerPlayer resetTarget = onlineByUuid(server, player);
        if (resetTarget != null) {
            QuestSync.syncProgressTo(resetTarget);
        }
        source.sendSuccess(() -> Component.translatable("command.neroquests.reset.success"), false);
        return Command.SINGLE_SUCCESS;
    }

    // --- list ----------------------------------------------------------------

    /** Every loaded quest with the chapter that owns it and its scope. */
    private static int listQuests(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Map<Identifier, Quest> quests = QuestDefinitions.questsForServer(server);
        if (quests.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.neroquests.list.empty"), false);
            return Command.SINGLE_SUCCESS;
        }
        int count = quests.size();
        source.sendSuccess(() -> Component.translatable("command.neroquests.list.header", count), false);
        for (Quest quest : quests.values()) {
            // AUD-33: ids stay ids; every word around them is a translation key, styled without '§'.
            Component line = Component.translatable("command.neroquests.list.line",
                    Component.literal(quest.id().toString()),
                    chapterLabel(server, quest.id()).withStyle(ChatFormatting.GRAY),
                    Component.translatable("command.neroquests.scope."
                            + quest.scope().name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.DARK_GRAY));
            source.sendSuccess(() -> line, false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Per-quest status for one player: complete, in progress (with counters), available or locked. */
    private static int listForPlayer(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        UUID player = resolvePlayer(source, server, ctx);
        if (player == null) {
            return 0;
        }
        Map<Identifier, Quest> quests = QuestDefinitions.questsForServer(server);
        if (quests.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.neroquests.list.empty"), false);
            return Command.SINGLE_SUCCESS;
        }
        QuestProgressState state = QuestProgressState.get(server);
        ServerPlayer online = onlineByUuid(server, player);
        // Gate-derived visibility can only be resolved for an online player; for an offline one the
        // gate set is empty, so a gated quest reads as "locked" rather than being guessed at.
        Set<Identifier> openGates = online == null ? Set.of() : QuestEngine.openGatesFor(online);
        Set<Identifier> playerCompleted = state.completedQuests(player);
        Set<Identifier> serverCompleted = state.completedServerQuests();

        int count = quests.size();
        source.sendSuccess(() -> Component.translatable("command.neroquests.list.player_header", count), false);
        for (Quest quest : quests.values()) {
            boolean serverScope = quest.scope() == QuestScope.SERVER;
            QuestProgress progress = (serverScope
                    ? state.serverProgress(quest.id())
                    : state.progress(player, quest.id())).orElse(QuestProgress.EMPTY);
            // AUD-18: the same verdict the engine, the book and the link reach.
            Component status = switch (QuestStates.stateOf(quest, quests, playerCompleted, serverCompleted,
                    progress, openGates)) {
                case COMPLETED -> Component.translatable("command.neroquests.list.status.complete")
                        .withStyle(ChatFormatting.GREEN);
                case LOCKED -> Component.translatable("command.neroquests.list.status.locked")
                        .withStyle(ChatFormatting.DARK_GRAY);
                case AVAILABLE -> Component.translatable("command.neroquests.list.status.available")
                        .withStyle(ChatFormatting.YELLOW);
                case IN_PROGRESS -> Component.translatable("command.neroquests.list.status.in_progress",
                        counters(quest, progress)).withStyle(ChatFormatting.YELLOW);
            };
            Component line = Component.translatable("command.neroquests.list.player_line",
                    Component.literal(quest.id().toString()), status);
            source.sendSuccess(() -> line, false);
        }
        return Command.SINGLE_SUCCESS;
    }

    // --- reload-check --------------------------------------------------------

    /**
     * Reports what the running definitions hold and everything the last load (world start or
     * {@code /reload}) dropped or ignored, per source (quests/chapters, then contracts). Does not
     * re-read pack files and syncs nothing (AUD-29); resets {@link MissingContent}'s warn-once state.
     */
    private static int reloadCheck(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        // AUD-29: report on the definitions the server is actually running — the last real load or
        // /reload — instead of re-reading live pack files (which could show, and sync, content no
        // /reload has applied). Warn-once missing-content state is reset so the next pass reports again.
        MissingContent.reset();
        int questCount = QuestDefinitions.questsForServer(server).size();
        int chapterCount = QuestDefinitions.chaptersForServer(server).size();
        source.sendSuccess(() -> Component.translatable("command.neroquests.reload.header",
                questCount, chapterCount), false);
        // B5: per source — spine N / pack <ns> N … / custom N — then every pack skipped for an absent mod.
        Map<Identifier, Quest> loaded = QuestDefinitions.questsForServer(server);
        String sources = String.join(" / ", QuestSources.summary(loaded.values()));
        source.sendSuccess(() -> Component.translatable("command.neroquests.reload.sources",
                Component.literal(sources)), false);
        QuestDefinitions.absentPacksForServer(server).forEach((namespace, skipped) -> {
            long shown = loaded.values().stream()
                    .filter(quest -> quest.modAbsent() && quest.id().getNamespace().equals(namespace)).count();
            Component line = shown > 0
                    ? Component.translatable("command.neroquests.reload.absent_shown", Component.literal(namespace),
                            Long.valueOf(shown), skipped)
                    : Component.translatable("command.neroquests.reload.absent", Component.literal(namespace), skipped);
            source.sendSuccess(() -> line.copy().withStyle(ChatFormatting.GRAY), false);
        });

        List<QuestDefinitions.ValidationIssue> issues = new ArrayList<>(QuestDefinitions.issuesForServer(server));
        // Daily-contract files are loaded separately; their drops belong in the same report (AUD-04).
        ContractDefinitions.forServer(server);
        issues.addAll(ContractDefinitions.validationIssues());
        if (issues.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.neroquests.reload.clean"), false);
        } else {
            int issueCount = issues.size();
            source.sendSuccess(() -> Component.translatable("command.neroquests.reload.issues",
                    issueCount), false);
            for (QuestDefinitions.ValidationIssue issue : issues) {
                boolean dropped = issue.severity() == QuestDefinitions.ValidationIssue.Severity.DROPPED;
                Component severity = Component.translatable(dropped
                        ? "command.neroquests.reload.severity.dropped"
                        : "command.neroquests.reload.severity.ignored")
                        .withStyle(dropped ? ChatFormatting.RED : ChatFormatting.GOLD);
                // The detail is a codec/validation message about pack content (ids, field names).
                Component line = Component.translatable("command.neroquests.reload.issue_line", severity,
                        Component.literal(issue.id().toString()), Component.literal(issue.detail()));
                source.sendSuccess(() -> line, false);
            }
        }
        // Later stages (the editor's lock state, Stage D) add their lines here.
        for (Component extra : ReloadCheckSections.lines(server)) {
            source.sendSuccess(() -> extra, false);
        }
        return Command.SINGLE_SUCCESS;
    }

    // --- export --------------------------------------------------------------

    /**
     * POPIA/GDPR data access: prints exactly one player's own quest rows as pretty JSON to the
     * invoker — progress, owed rewards, the paid ledger, repeat timestamps, and (F3) the custom quests they
     * authored plus their editor allow-list membership. Nobody else's rows and no shared server-scoped
     * progress are included — a property of {@link QuestProgressState#exportPlayer} and
     * {@link CustomQuestState#export}, not of this presentation.
     */
    private static int export(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        UUID player = resolvePlayer(source, server, ctx);
        if (player == null) {
            return 0;
        }
        JsonObject json = QuestProgressState.exportPlayer(server, player);
        // F3: custom quests this player authored (their own text) and their editor allow-list membership.
        json.add("custom_quests", CustomQuestState.get(server).export(player));
        AdminActions.record(AdminActions.Kind.EXPORT);
        // AUD-25: the console's output *is* the server log, so a console export never echoes the UUID;
        // the rows themselves hold no UUID or name, and nothing here goes through LOGGER.
        if (source.getEntity() instanceof ServerPlayer) {
            String subject = player.toString();
            source.sendSuccess(() -> Component.translatable("command.neroquests.export.header", subject), false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.neroquests.export.header_console"), false);
        }

        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(json);
        boolean truncated = pretty.length() > EXPORT_CHAR_LIMIT;
        if (truncated) {
            pretty = pretty.substring(0, EXPORT_CHAR_LIMIT);
        }
        for (String line : pretty.split("\n", -1)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        if (truncated) {
            source.sendSuccess(() -> Component.translatable("command.neroquests.export.truncated",
                    EXPORT_CHAR_LIMIT), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    // --- editor allow-list and lock (F1) -------------------------------------------

    /** {@code editor allow|deny <player>}: an online name or a UUID; the output never names anyone. */
    private static int editorAllow(CommandContext<CommandSourceStack> ctx, boolean allow) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        UUID player = resolvePlayer(source, server, ctx);
        if (player == null) {
            return 0;
        }
        boolean changed = allow ? CustomQuests.allow(server, player) : CustomQuests.deny(server, player);
        if (changed) {
            AdminActions.record(allow ? AdminActions.Kind.EDITOR_ALLOW : AdminActions.Kind.EDITOR_DENY);
        }
        String key = "command.neroquests.editor." + (allow ? "allow" : "deny") + (changed ? ".done" : ".unchanged");
        source.sendSuccess(() -> Component.translatable(key), false);
        return changed ? Command.SINGLE_SUCCESS : 0;
    }

    /** {@code editor check <player>}: whether that player is on the allow-list (yes/no, no name echoed). */
    private static int editorCheck(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        UUID player = resolvePlayer(source, server, ctx);
        if (player == null) {
            return 0;
        }
        boolean listed = CustomQuestState.get(server).isAllowed(player);
        source.sendSuccess(() -> Component.translatable(listed
                ? "command.neroquests.editor.check.yes"
                : "command.neroquests.editor.check.no"), false);
        return Command.SINGLE_SUCCESS;
    }

    /** {@code editor list}: the allow-list's size and whether the invoker is on it — never its entries. */
    private static int editorList(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        CustomQuestState state = CustomQuestState.get(server);
        int size = state.allowListSize();
        source.sendSuccess(() -> Component.translatable("command.neroquests.editor.list", Integer.valueOf(size),
                Component.translatable(state.isLocked()
                        ? "command.neroquests.reload.editor.locked"
                        : "command.neroquests.reload.editor.unlocked")), false);
        if (source.getEntity() instanceof ServerPlayer self) {
            boolean listed = state.isAllowed(self.getUUID());
            source.sendSuccess(() -> Component.translatable(listed
                    ? "command.neroquests.editor.list.you_are"
                    : "command.neroquests.editor.list.you_are_not"), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** {@code editor lock|unlock} (level 4): freeze or reopen authoring for everyone below level 4. */
    private static int editorLock(CommandSourceStack source, boolean lock) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        boolean changed = CustomQuests.setLocked(server, lock);
        if (changed) {
            AdminActions.record(lock ? AdminActions.Kind.EDITOR_LOCK : AdminActions.Kind.EDITOR_UNLOCK);
        }
        String key = "command.neroquests.editor." + (lock ? "lock" : "unlock") + (changed ? ".done" : ".unchanged");
        source.sendSuccess(() -> Component.translatable(key), false);
        return changed ? Command.SINGLE_SUCCESS : 0;
    }

    // --- custom export / import / delete (F2) -----------------------------------------

    /** {@code custom export [chapter]}: write the custom quests as a datapack folder (no author ids). */
    private static int customExport(CommandSourceStack source, Identifier chapter) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Optional<String> slug = Optional.empty();
        if (chapter != null) {
            if (!QuestSource.CUSTOM_NAMESPACE.equals(chapter.getNamespace())
                    || CustomQuestState.get(server).chapter(chapter.getPath()).isEmpty()) {
                source.sendFailure(Component.translatable(EditResult.UNKNOWN_CHAPTER, chapter.toString()));
                return 0;
            }
            slug = Optional.of(chapter.getPath());
        }
        CustomPackIO.ExportResult result;
        try {
            result = CustomPackIO.export(server, slug);
        } catch (java.io.IOException e) {
            // The exception text can carry a filesystem path: report the type only, log the trace.
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Custom quest export failed.", e);
            source.sendFailure(Component.translatable("command.neroquests.custom.export.failed",
                    e.getClass().getSimpleName()));
            return 0;
        }
        AdminActions.record(AdminActions.Kind.CUSTOM_EXPORT);
        source.sendSuccess(() -> Component.translatable("command.neroquests.custom.export.done",
                Integer.valueOf(result.chapters()), Integer.valueOf(result.quests()),
                CustomPackIO.EXPORT_FOLDER), false);
        if (result.externalReferences() > 0) {
            source.sendSuccess(() -> Component.translatable("command.neroquests.custom.export.external",
                    Integer.valueOf(result.externalReferences())).withStyle(ChatFormatting.GOLD), false);
        }
        if (result.repeatable() > 0) {
            source.sendSuccess(() -> Component.translatable("command.neroquests.custom.export.repeatable",
                    Integer.valueOf(result.repeatable())).withStyle(ChatFormatting.GOLD), false);
        }
        source.sendSuccess(() -> Component.translatable("command.neroquests.custom.export.next")
                .withStyle(ChatFormatting.GRAY), false);
        return Command.SINGLE_SUCCESS;
    }

    /** {@code custom import <folder> [--dry-run]} (level 4): validated, all-or-nothing, within the caps. */
    private static int customImport(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        String raw = StringArgumentType.getString(ctx, "folder").trim();
        boolean dryRun = raw.equals(DRY_RUN_FLAG) || raw.endsWith(" " + DRY_RUN_FLAG);
        String folder = dryRun ? raw.substring(0, raw.length() - DRY_RUN_FLAG.length()).trim() : raw;
        CustomPackIO.ImportResult result;
        try {
            result = CustomPackIO.importFolder(server, folder, dryRun,
                    CustomQuests.Actor.admin(server, source.getEntity() instanceof ServerPlayer p ? p : null));
        } catch (java.io.IOException e) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Custom quest import failed.", e);
            source.sendFailure(Component.translatable("command.neroquests.custom.import.failed",
                    e.getClass().getSimpleName()));
            return 0;
        }
        String key = result.applied() ? "command.neroquests.custom.import.done"
                : dryRun ? "command.neroquests.custom.import.dry_run"
                : "command.neroquests.custom.import.refused";
        source.sendSuccess(() -> Component.translatable(key, Integer.valueOf(result.chapters()),
                Integer.valueOf(result.quests()), Integer.valueOf(result.problems().size())), false);
        int shown = 0;
        for (String problem : result.problems()) {
            if (shown++ >= 20) {
                break;
            }
            source.sendSuccess(() -> Component.translatable("command.neroquests.custom.import.problem",
                    Component.literal(problem)).withStyle(ChatFormatting.RED), false);
        }
        for (String note : result.notes()) {
            if (shown++ >= 30) {
                break;
            }
            source.sendSuccess(() -> Component.translatable("command.neroquests.custom.import.note",
                    Component.literal(note)).withStyle(ChatFormatting.GRAY), false);
        }
        if (result.applied()) {
            AdminActions.record(AdminActions.Kind.CUSTOM_IMPORT);
        }
        return result.applied() || (dryRun && result.problems().isEmpty()) ? Command.SINGLE_SUCCESS : 0;
    }

    /**
     * {@code custom delete <id> [confirm]} (level 3): a chapter id deletes the chapter and all its quests, a
     * quest id one quest. The first call only arms it; {@code confirm} within 30 seconds does it.
     */
    private static int customDelete(CommandContext<CommandSourceStack> ctx, boolean confirm) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Identifier id = IdentifierArgument.getId(ctx, "id");
        Optional<String> locked = QuestSources.lockKey(id);
        if (locked.isPresent()) {
            source.sendFailure(Component.translatable(locked.get(), id.toString()));
            return 0;
        }
        CustomQuestState state = CustomQuestState.get(server);
        boolean chapter = id.getPath().indexOf('/') < 0;
        if (chapter ? state.chapter(id.getPath()).isEmpty() : state.quest(id).isEmpty()) {
            source.sendFailure(Component.translatable(chapter ? EditResult.UNKNOWN_CHAPTER : EditResult.UNKNOWN_QUEST,
                    id.toString()));
            return 0;
        }
        String invoker = invokerKey(source);
        long now = System.currentTimeMillis();
        synchronized (PENDING_DELETES) {
            PENDING_DELETES.values().removeIf(pending -> pending.expiresAt() < now);
            PendingDelete pending = PENDING_DELETES.get(invoker);
            if (!confirm || pending == null || !pending.id().equals(id)) {
                PENDING_DELETES.put(invoker, new PendingDelete(id, now + DELETE_CONFIRM_MILLIS));
                int quests = chapter ? state.questsOf(id.getPath()).size() : 1;
                source.sendSuccess(() -> Component.translatable(chapter
                        ? "command.neroquests.custom.delete.confirm_chapter"
                        : "command.neroquests.custom.delete.confirm_quest", id.toString(), Integer.valueOf(quests),
                        Long.valueOf(DELETE_CONFIRM_MILLIS / 1000L)).withStyle(ChatFormatting.GOLD), false);
                return 0;
            }
            PENDING_DELETES.remove(invoker);
        }
        CustomQuests.Actor actor = CustomQuests.Actor.admin(server,
                source.getEntity() instanceof ServerPlayer player ? player : null);
        EditResult result = chapter ? CustomQuests.deleteChapter(actor, id) : CustomQuests.deleteQuest(actor, id);
        if (!result.ok()) {
            source.sendFailure(result.message());
            return 0;
        }
        AdminActions.record(AdminActions.Kind.CUSTOM_DELETE);
        source.sendSuccess(() -> Component.translatable("command.neroquests.custom.delete.done", id.toString()), false);
        return Command.SINGLE_SUCCESS;
    }

    /** The per-invoker key for confirmations and rate limits: the player's UUID, or one marker for the console. */
    private static String invokerKey(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player.getUUID().toString() : "console";
    }

    /** Drop pending confirmations (server stop). */
    public static void serverStopped() {
        synchronized (PENDING_DELETES) {
            PENDING_DELETES.clear();
        }
        MANUAL_LIMIT.clear();
    }

    // --- argument resolution --------------------------------------------------

    /** The {@code quest} argument as a loaded quest, or null after reporting why it is not one. */
    private static Quest resolveQuest(CommandSourceStack source, MinecraftServer server,
                                      CommandContext<CommandSourceStack> ctx) {
        return resolveQuest(source, server, StringArgumentType.getString(ctx, "quest").trim());
    }

    /** {@link #resolveQuest(CommandSourceStack, MinecraftServer, CommandContext)} for an already-read argument. */
    private static Quest resolveQuest(CommandSourceStack source, MinecraftServer server, String raw) {
        Identifier id = parseQuestId(raw);
        Quest quest = id == null ? null : QuestDefinitions.questsForServer(server).get(id);
        if (quest == null) {
            source.sendFailure(Component.translatable("command.neroquests.quest.unknown", raw));
            return null;
        }
        return quest;
    }

    /** Accept a bare path ("intro/first_step") as neroquests-namespaced, or a full "ns:path". */
    private static Identifier parseQuestId(String raw) {
        return Identifier.tryParse(raw.indexOf(':') < 0
                ? NeroQuestsCommon.MOD_ID + ":" + raw
                : raw);
    }

    /**
     * The {@code player} argument as a UUID — an online player's name or a raw UUID — or null after
     * reporting that it named nobody. Offline players are reachable by UUID on purpose: erasure,
     * reset and export must work for someone who has left.
     */
    private static UUID resolvePlayer(CommandSourceStack source, MinecraftServer server,
                                      CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "player").trim();
        ServerPlayer online = onlinePlayer(server, raw);
        if (online != null) {
            return online.getUUID();
        }
        UUID parsed = resolvePlayerId(raw);
        if (parsed == null) {
            source.sendFailure(Component.translatable("command.neroquests.player.unknown"));
        }
        return parsed;
    }

    /** The online player with this name (case-insensitive) or UUID, or null. */
    private static ServerPlayer onlinePlayer(MinecraftServer server, String raw) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.getName().getString().equalsIgnoreCase(raw)) {
                return online;
            }
        }
        UUID parsed = resolvePlayerId(raw);
        return parsed == null ? null : onlineByUuid(server, parsed);
    }

    /** The online player with this UUID, or null if they are not connected. */
    private static ServerPlayer onlineByUuid(MinecraftServer server, UUID player) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.getUUID().equals(player)) {
                return online;
            }
        }
        return null;
    }

    /** {@code raw} as a UUID, or null if it is not one. */
    private static UUID resolvePlayerId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- small helpers --------------------------------------------------------

    /** "chapter id" for a quest a chapter lists, or a placeholder for an unlisted one. */
    private static net.minecraft.network.chat.MutableComponent chapterLabel(MinecraftServer server,
                                                                          Identifier questId) {
        for (Chapter chapter : QuestDefinitions.chaptersForServer(server).values()) {
            if (chapter.entry(questId).isPresent()) {
                return Component.literal(chapter.id().toString());
            }
        }
        return Component.translatable("command.neroquests.list.no_chapter");
    }

    /** {@code 2/10, 0/1} — one {@code stored/target} pair per objective, in definition order. */
    private static String counters(Quest quest, QuestProgress progress) {
        List<ObjectiveSpec> objectives = quest.objectives();
        StringBuilder out = new StringBuilder(objectives.size() * 6);
        for (int index = 0; index < objectives.size(); index++) {
            if (index > 0) {
                out.append(", ");
            }
            out.append(progress.counter(index)).append('/').append(Math.max(1, objectives.get(index).target()));
        }
        return out.toString();
    }

    // --- daily contracts (any player) ------------------------------------------

    /** {@code /neroquests contracts} — today's contracts for the invoking player. */
    private static int listContracts(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.neroquests.contracts.players_only"));
            return 0;
        }
        if (!DailyContracts.available()) {
            source.sendFailure(Component.translatable("command.neroquests.contracts.unavailable"));
            return 0;
        }
        List<DailyContracts.Offer> offers = DailyContracts.offerFor(player);
        if (offers.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.neroquests.contracts.none"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.neroquests.contracts.header"), false);
        for (DailyContracts.Offer offer : offers) {
            DailyContract contract = offer.contract();
            String status = offer.done() ? "done" : offer.payable() ? "open" : "unfunded";
            Component target = contract.target().item().isPresent()
                    ? Component.translatable(net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getValue(contract.target().item().get()).getDescriptionId())
                    : contract.target().tag().isPresent()
                            ? Component.translatable("gui.neroquests.target.tag",
                                    Component.literal(contract.target().tag().get().toString()))
                            : Component.translatable("gui.neroquests.target.none");
            source.sendSuccess(() -> Component.translatable("command.neroquests.contracts.line",
                    Component.translatable(contract.title()), Integer.valueOf(contract.count()), target,
                    Long.valueOf(contract.reward()), Component.literal(contract.id().toString()),
                    Component.translatable("command.neroquests.contracts.status." + status)), false);
        }
        return offers.size();
    }

    /** {@code /neroquests contracts submit <contract>} — hand in one of today's contracts. */
    private static int submitContract(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.neroquests.contracts.players_only"));
            return 0;
        }
        Identifier id = Identifier.tryParse(StringArgumentType.getString(ctx, "contract").trim());
        if (id == null) {
            source.sendFailure(Component.translatable("command.neroquests.contracts.result.not_offered"));
            return 0;
        }
        DailyContracts.Result result = DailyContracts.submit(player, id);
        String key = "command.neroquests.contracts.result." + result.name().toLowerCase(Locale.ROOT);
        if (result == DailyContracts.Result.PAID || result == DailyContracts.Result.OWED) {
            source.sendSuccess(() -> Component.translatable(key), false);
            return 1;
        }
        source.sendFailure(Component.translatable(key));
        return 0;
    }

    private static int noServer(CommandSourceStack source) {
        source.sendFailure(Component.translatable("command.neroquests.no_server"));
        return 0;
    }

    /**
     * Runs one subcommand body, turning an unexpected failure into a polite message plus an
     * anonymous telemetry event instead of a Brigadier stack trace in chat. The captured context is
     * the subcommand name only — never its arguments, which name a player.
     */
    private static int runSafely(CommandSourceStack source, String subcommand, CommandBody body) {
        try {
            return body.run();
        } catch (RuntimeException e) {
            NeroQuestsTelemetry.captureHandledException(e, "command", "/neroquests " + subcommand);
            NeroQuestsCommon.LOGGER.error("[NeroQuests] /neroquests {} failed", subcommand, e);
            source.sendFailure(Component.translatable("command.neroquests.failed", subcommand));
            return 0;
        }
    }

    @FunctionalInterface
    private interface CommandBody {

        int run();
    }
}
