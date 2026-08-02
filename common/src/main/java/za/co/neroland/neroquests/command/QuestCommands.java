package za.co.neroland.neroquests.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.data.QuestProgress;
import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.network.QuestSync;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.engine.QuestEngine;
import za.co.neroland.neroquests.telemetry.NeroQuestsTelemetry;

/**
 * The {@code /neroquests} admin command tree — the operator's view of, and lever on, the quest
 * engine. Registered identically from all three loaders (NeoForge/Forge {@code RegisterCommandsEvent},
 * Fabric {@code CommandRegistrationCallback}), so the tree itself lives here in common.
 *
 * <pre>
 * /neroquests grant   &lt;player&gt; &lt;quest&gt;
 * /neroquests revoke  &lt;player&gt; &lt;quest&gt;
 * /neroquests reset   &lt;player&gt; [&lt;quest&gt;]
 * /neroquests list    [&lt;player&gt;]
 * /neroquests reload-check
 * /neroquests export  &lt;player&gt;
 * </pre>
 *
 * <p>Everything here is server-authoritative and gated at Core's own admin level
 * ({@code Commands.LEVEL_GAMEMASTERS}, i.e. permission level 2), matching {@code /neroland}.
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

    private QuestCommands() {
    }

    // --- tree ---------------------------------------------------------------

    /** Builds {@code /neroquests …}. Called once per loader from its command-registration hook. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("neroquests")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("grant")
                        .then(playerArgument()
                                .then(questArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "grant",
                                                () -> grant(ctx))))))
                .then(Commands.literal("revoke")
                        .then(playerArgument()
                                .then(questArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "revoke",
                                                () -> revokeQuest(ctx))))))
                // `reset <player> <quest>` is deliberately the same implementation as `revoke`:
                // one behaviour, two names, so neither spelling surprises an operator.
                .then(Commands.literal("reset")
                        .then(playerArgument()
                                .executes(ctx -> runSafely(ctx.getSource(), "reset",
                                        () -> resetPlayer(ctx)))
                                .then(questArgument()
                                        .executes(ctx -> runSafely(ctx.getSource(), "reset quest",
                                                () -> revokeQuest(ctx))))))
                .then(Commands.literal("list")
                        .executes(ctx -> runSafely(ctx.getSource(), "list",
                                () -> listQuests(ctx.getSource())))
                        .then(playerArgument()
                                .executes(ctx -> runSafely(ctx.getSource(), "list player",
                                        () -> listForPlayer(ctx)))))
                .then(Commands.literal("reload-check")
                        .executes(ctx -> runSafely(ctx.getSource(), "reload-check",
                                () -> reloadCheck(ctx.getSource()))))
                .then(Commands.literal("export")
                        .then(playerArgument()
                                .executes(ctx -> runSafely(ctx.getSource(), "export",
                                        () -> export(ctx))))));
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
        if (!QuestEngine.adminComplete(target, quest)) {
            source.sendFailure(Component.translatable("command.neroquests.grant.already",
                    quest.id().toString()));
            return 0;
        }
        String questId = quest.id().toString();
        source.sendSuccess(() -> Component.translatable("command.neroquests.grant.success", questId), false);
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
     */
    private static int revokeQuest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        Quest quest = resolveQuest(source, server, ctx);
        if (quest == null) {
            return 0;
        }
        UUID player = resolvePlayer(source, server, ctx);
        if (player == null) {
            return 0;
        }
        QuestProgressState state = QuestProgressState.get(server);
        boolean serverScope = quest.scope() == QuestScope.SERVER;
        boolean changed = serverScope
                ? state.resetServerQuest(quest.id())
                : state.resetQuest(player, quest.id());
        String questId = quest.id().toString();
        if (!changed) {
            source.sendFailure(Component.translatable("command.neroquests.revoke.none", questId));
            return 0;
        }
        if (serverScope) {
            QuestSync.syncProgressAll(server);
        } else {
            ServerPlayer online = onlineByUuid(server, player);
            if (online != null) {
                QuestSync.syncProgressTo(online);
            }
        }
        source.sendSuccess(() -> Component.translatable("command.neroquests.revoke.success", questId), false);
        return Command.SINGLE_SUCCESS;
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
        QuestProgressState.get(server).forgetPlayer(player);
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
            String line = "  " + quest.id() + " §7[" + chapterLabel(server, quest.id()) + "]§r §8"
                    + quest.scope().name().toLowerCase(Locale.ROOT) + "§r";
            source.sendSuccess(() -> Component.literal(line), false);
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
            Set<Identifier> completed = serverScope ? serverCompleted : playerCompleted;
            QuestProgress progress = (serverScope
                    ? state.serverProgress(quest.id())
                    : state.progress(player, quest.id())).orElse(QuestProgress.EMPTY);
            String status;
            if (progress.isComplete()) {
                status = "§acomplete§r";
            } else if (!quest.isAvailable(completed, openGates)) {
                status = "§8locked§r";
            } else if (progress.counterCount() == 0) {
                status = "§eavailable§r";
            } else {
                status = "§ein progress §r" + counters(quest, progress);
            }
            String line = "  " + quest.id() + " — " + status;
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    // --- reload-check --------------------------------------------------------

    /**
     * Re-reads every definition from the current datapacks, reports what loaded and everything the
     * loader dropped or ignored, then re-syncs clients (which {@link QuestDefinitions#reload} on its
     * own does not do).
     */
    private static int reloadCheck(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return noServer(source);
        }
        QuestDefinitions.reload(server);
        int questCount = QuestDefinitions.quests().size();
        int chapterCount = QuestDefinitions.chapters().size();
        source.sendSuccess(() -> Component.translatable("command.neroquests.reload.header",
                questCount, chapterCount), false);

        List<QuestDefinitions.ValidationIssue> issues = QuestDefinitions.validationIssues();
        if (issues.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.neroquests.reload.clean"), false);
        } else {
            int issueCount = issues.size();
            source.sendSuccess(() -> Component.translatable("command.neroquests.reload.issues",
                    issueCount), false);
            for (QuestDefinitions.ValidationIssue issue : issues) {
                String line = "  " + (issue.severity() == QuestDefinitions.ValidationIssue.Severity.DROPPED
                        ? "§c[dropped]§r " : "§6[ignored]§r ") + issue.id() + " — " + issue.detail();
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        QuestSync.syncAll(server);
        source.sendSuccess(() -> Component.translatable("command.neroquests.reload.synced"), false);
        return Command.SINGLE_SUCCESS;
    }

    // --- export --------------------------------------------------------------

    /**
     * POPIA/GDPR data access: prints exactly one player's own quest rows as pretty JSON to the
     * invoker. Nobody else's rows and no shared server-scoped progress are included — that is a
     * property of {@link QuestProgressState#exportPlayer}, not of this presentation.
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
        String subject = player.toString();
        source.sendSuccess(() -> Component.translatable("command.neroquests.export.header", subject), false);

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

    // --- argument resolution --------------------------------------------------

    /** The {@code quest} argument as a loaded quest, or null after reporting why it is not one. */
    private static Quest resolveQuest(CommandSourceStack source, MinecraftServer server,
                                      CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "quest").trim();
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
    private static String chapterLabel(MinecraftServer server, Identifier questId) {
        for (Chapter chapter : QuestDefinitions.chaptersForServer(server).values()) {
            if (chapter.entry(questId).isPresent()) {
                return chapter.id().toString();
            }
        }
        return "no chapter";
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
