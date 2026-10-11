package za.co.neroland.neroquests.quest.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.event.CoreEvents;
import za.co.neroland.nerolandcore.event.ThresholdEvents.ThresholdCrossing;
import za.co.neroland.nerolandcore.progression.GateEvents.GateChange;
import za.co.neroland.nerolandcore.progression.GateScope;
import za.co.neroland.neroquests.command.QuestCommands;
import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.contract.ContractDefinitions;
import za.co.neroland.neroquests.custom.CustomQuests;
import za.co.neroland.neroquests.custom.EditorSync;
import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.link.QuestLinkModule;
import za.co.neroland.neroquests.network.QuestSync;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.reward.CurrencyPayouts;
import za.co.neroland.neroquests.quest.reward.OwedDelivery;
import za.co.neroland.neroquests.quest.reward.RewardLog;

/**
 * The loader-agnostic front door to {@link QuestEngine}: every source of "something happened"
 * funnels through a static method here, so the engine itself never sees a loader type and each
 * loader module only has to translate its own bus into one of these calls.
 *
 * <h2>Where the triggers come from</h2>
 *
 * <ul>
 *   <li><b>Progression gates and threshold crossings</b> — {@link #init()} subscribes to Neroland
 *       Core's own change buses, which are pure server-side Java and therefore need no per-loader
 *       wiring at all. This is also how another mod reaches a quest: Nerospace opening
 *       {@code reached_orbit}, or NeroColonies reporting that a colony's life support came back,
 *       lands here with zero dependency on either mod.</li>
 *   <li><b>Crafting</b> — one vanilla-targeting mixin in {@code common}
 *       ({@code ItemStackMixin} on {@code ItemStack#onCraftedBy}) rather than three loader
 *       subscriptions. NeoForge and Forge do have a crafting event, but Fabric does not, and one
 *       shared hook is both less code and impossible to double-count. The loader modules
 *       deliberately do <em>not</em> register a craft event.</li>
 *   <li><b>Kills and the periodic sweep</b> — per-loader events, because there is no vanilla seam
 *       that credits a kill or ticks the server without one. Each loader module registers them
 *       from its entry point.</li>
 *   <li><b>Dimensions and inventories</b> — measured, not evented. Both are recomputed by the
 *       sweep (and opportunistically by every other trigger), which is safe precisely because
 *       measuring is idempotent: firing more often only reduces latency. That choice also keeps
 *       behaviour identical on all three loaders, which a per-loader "changed world" event would
 *       not — the three buses do not agree on one.</li>
 * </ul>
 *
 * <p>Every method runs on the server thread and silently ignores anything that is not a
 * {@link ServerPlayer}, so a loader handing over a client-side callback cannot corrupt progress. The
 * handlers fed by other mods' buses — {@link #gatesChanged}, {@link #thresholdCrossed},
 * {@link #economyActivity} — hop onto the server thread themselves when called from elsewhere
 * (AUD-15), and when called from inside an engine pass (a gate reward firing {@code GateEvents}) they
 * queue their re-evaluation behind that pass instead of recursing into it (AUD-14).
 */
public final class QuestTriggers {

    /**
     * How often the periodic re-measure runs, in ticks. Once a second is plenty: measurement is
     * idempotent, so this is a latency knob, not a correctness one. Keeping it well above one tick
     * is what stops the engine from walking every online player's inventory 20 times a second.
     */
    private static final int SWEEP_INTERVAL_TICKS = 20;

    /**
     * The running server, captured from the tick trigger so the gate listener (whose payload
     * carries no server) can resolve players. Written on the server thread; {@code volatile} only
     * so an integrated-server restart inside one JVM is seen promptly.
     */
    private static volatile MinecraftServer currentServer;

    private static int ticksSinceSweep;

    /**
     * How often owed currency rewards of online players are retried, in ticks (five minutes). Each
     * retry reuses the reward's original request key, so it can never pay twice.
     */
    private static final int OWED_RETRY_INTERVAL_TICKS = 20 * 60 * 5;

    private static int ticksSinceOwedRetry;

    /**
     * How often the retention sweep runs while a server is up: once per in-game day (24,000 ticks).
     * Start-up runs it once as well ({@code QuestProgressState.get}); this keeps a long-running server
     * honest about {@code questDataRetentionDays} (AUD-06 retention half).
     */
    private static final int RETENTION_SWEEP_INTERVAL_TICKS = 24_000;

    private static int ticksSinceRetentionSweep;

    private QuestTriggers() {
    }

    /**
     * The running server, or {@code null} before the first server tick (and after shutdown, until
     * the next world starts ticking).
     *
     * <p>Exposed for the same reason the field exists at all: the NeroLink module's snapshot and
     * action callbacks are handed a player UUID and nothing else, so they need somewhere
     * loader-free to ask "which server am I on?".
     */
    public static MinecraftServer currentServer() {
        return currentServer;
    }

    /**
     * Subscribe to the loader-agnostic Neroland Core event buses. Called once from common init.
     *
     * <p><b>Both buses are add-only.</b> Core exposes no way to remove a listener from either
     * {@code GateEvents} or {@code ThresholdEvents}, so the only correct lifecycle is to subscribe
     * exactly once per JVM — which is what common init gives us, since it runs during mod
     * construction and not per world. Nothing is therefore leaked across an integrated-server world
     * switch, and there is nothing to unsubscribe on server stop. Per-<em>server</em> state is
     * dropped instead of unsubscribed: {@link #serverTick} resets it the moment a different
     * {@link MinecraftServer} instance appears, and both handlers do nothing at all before the first
     * tick of a world, when there is no server to resolve players against.
     *
     * <p>Neither handler can outlive a world in practice either: both buses are fired only from
     * server-side code, so a shutdown world publishes nothing for a stale subscription to see.
     */
    public static void init() {
        CoreEvents.onProgression(QuestTriggers::gatesChanged);
        CoreEvents.onThreshold(QuestTriggers::thresholdCrossed);
    }

    // --- triggers -----------------------------------------------------------

    /**
     * The periodic re-measure, driven by each loader's server-tick event. Runs every
     * {@value #SWEEP_INTERVAL_TICKS} ticks and does nothing at all when no quests are loaded.
     *
     * <p>Also carries the {@code /reload} hook. Neroland Core has no reload seam to copy (its own
     * gate definitions are documented as "re-reading after a {@code /reload} can be added when a
     * reload-listener seam lands"), and the three loaders each expose datapack reload through a
     * different API. {@link QuestDefinitions#refreshIfReloaded} instead detects a reload in pure
     * common code by watching the server's {@code ResourceManager} instance, which
     * {@code MinecraftServer.reloadResources} replaces — one implementation, identical on every
     * loader, and checked here every tick because the check is a reference comparison. When it
     * fires, the definitions have already been re-read and re-validated, so all that is left is to
     * push the new definitions and everyone's progress to the clients.
     */
    public static void serverTick(MinecraftServer server) {
        try {
            tick(server);
        } finally {
            // AUD-19/AUD-20: whatever this tick marked (gate-driven definition re-checks, progress) goes
            // out once, here, at the end of the tick.
            QuestSync.flush(server);
        }
    }

    private static void tick(MinecraftServer server) {
        if (currentServer != server) {
            // New server instance (world switch, integrated-server restart): start clean.
            currentServer = server;
            ticksSinceSweep = 0;
            ticksSinceOwedRetry = 0;
            ticksSinceRetentionSweep = 0;
            MissingContent.reset();
            RewardLog.reset();
            SeenChannels.reset();
        }
        boolean definitionsChanged = QuestDefinitions.refreshIfReloaded(server);
        // The typo pass of a fresh load (AUD-02) runs here, where registries, tags and levels are
        // bound; it may drop quests, which is another definitions change.
        definitionsChanged |= QuestDefinitions.runContentChecks(server);
        if (definitionsChanged) {
            // Definitions changed under everyone's feet: re-sync the whole picture. Warn-once
            // "missing content" state is keyed on the old definitions, so let it speak again.
            MissingContent.reset();
            QuestSync.syncAll(server);
        }
        ProgressReconciler.ensure(server);
        if (++ticksSinceRetentionSweep >= RETENTION_SWEEP_INTERVAL_TICKS) {
            ticksSinceRetentionSweep = 0;
            int days = NeroQuestsConfig.RETENTION_DAYS.get();
            if (days > 0) {
                List<UUID> online = new ArrayList<>();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    online.add(player.getUUID());
                }
                // Online players are stamped active first and never purged; only a count is logged.
                QuestProgressState state = QuestProgressState.get(server);
                if (state.pruneStale(days, online) > 0) {
                    state.backupNow(server); // the purge must reach the recovery backup too
                }
            }
        }
        if (++ticksSinceOwedRetry >= OWED_RETRY_INTERVAL_TICKS) {
            ticksSinceOwedRetry = 0;
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                CurrencyPayouts.retryOwed(player, false);
                // Kept items/experience (AUD-05): whatever now fits is handed over, silently if nothing does.
                OwedDelivery.deliver(player, null, false);
            }
        }
        if (++ticksSinceSweep < SWEEP_INTERVAL_TICKS) {
            return;
        }
        ticksSinceSweep = 0;
        if (QuestDefinitions.questsForServer(server).isEmpty()) {
            EditorSync.sweep(server);
            return;
        }
        // D2: a game-mode or permission change can flip who may author; re-send their editor block if so.
        EditorSync.sweep(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // D2: repeatable custom quests whose cooldown has passed are re-armed before evaluation.
            CustomQuests.rearmDue(server, player);
            QuestEngine.evaluate(player);
        }
    }

    /**
     * The server stopped (AUD-31). Drops every static reference to it — the running server, loaded
     * definitions and contracts, the progress store's sweep marker, the reconciler, the sync caches and
     * per-player sync bookkeeping, and the per-server warn-once/seen-channel sets — so a stopped
     * integrated server is not kept reachable and nothing leaks into the next world. Each loader wires
     * its own server-stopped event here (Fabric {@code ServerLifecycleEvents.SERVER_STOPPED},
     * NeoForge/Forge {@code ServerStoppedEvent}).
     */
    public static void serverStopped() {
        currentServer = null;
        ticksSinceSweep = 0;
        ticksSinceOwedRetry = 0;
        ticksSinceRetentionSweep = 0;
        QuestSync.serverStopped();
        QuestLinkModule.serverStopped();
        ProgressReconciler.serverStopped();
        QuestDefinitions.serverStopped();
        ContractDefinitions.serverStopped();
        QuestProgressState.serverStopped();
        CurrencyPayouts.serverStopped();
        CustomQuests.serverStopped();
        QuestCommands.serverStopped();
        MissingContent.reset();
        RewardLog.reset();
        SeenChannels.reset();
    }

    /**
     * A player has finished joining and can receive packets. Sends them the definition set and
     * their own progress, so the quest book has something to draw from the first moment it opens.
     *
     * <p>No evaluation happens here: the 1 Hz sweep picks the player up within a second, and
     * evaluating mid-join would re-measure an inventory the client has not finished receiving.
     *
     * <p>Each loader entry point wires its own join event to this — NeoForge/Forge
     * {@code PlayerEvent.PlayerLoggedInEvent}, Fabric {@code ServerPlayConnectionEvents.JOIN}.
     */
    public static void playerJoined(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            // Activity stamp for retention, and the end of any post-erasure suppression (AUD-01).
            QuestProgressState.get(server).playerJoined(player.getUUID());
        }
        QuestSync.syncTo(player);
        // Currency rewards an economy refused earlier (budget spent, Reserve short) are retried with
        // their original request key, so a retry can never pay twice.
        CurrencyPayouts.retryOwed(player, true);
        // Items and experience kept while they were offline, dead or full (AUD-05), oldest first.
        OwedDelivery.deliver(player, null, true);
    }

    /**
     * A player respawned (after death, or leaving the End). Hands over the items and experience kept
     * for them while they were dead (AUD-05). Each loader entry point wires its own respawn event to
     * this — NeoForge/Forge {@code PlayerEvent.PlayerRespawnEvent}, Fabric
     * {@code ServerPlayerEvents.AFTER_RESPAWN}.
     */
    public static void playerRespawned(ServerPlayer player) {
        if (player == null) {
            return;
        }
        OwedDelivery.deliver(player, null, true);
    }

    /**
     * An optional economy mod reported a sale, purchase or credits earned for {@code playerId}.
     * Feeds {@code neroquests:economy} objectives. Only an online player can be credited — the
     * engine needs a live player to resolve what they can currently work on — so an event for an
     * offline player is dropped, never stored. The id is never logged.
     */
    public static void economyActivity(UUID playerId, EconomyActivity activity) {
        MinecraftServer server = currentServer;
        if (server == null || playerId == null || activity == null) {
            return;
        }
        if (!server.isSameThread()) {
            // AUD-15: the economy may publish from another thread; engine state is server-thread only.
            server.execute(() -> economyActivity(playerId, activity));
            return;
        }
        // AUD-14: an economy event published from inside a reward waits for the running pass.
        QuestEngine.afterPass(new Object(), () -> economyActivityNow(server, playerId, activity));
    }

    private static void economyActivityNow(MinecraftServer server, UUID playerId, EconomyActivity activity) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null || QuestDefinitions.questsForServer(server).isEmpty()) {
            return;
        }
        QuestEngine.evaluate(player, (objective, context) -> objective.creditEconomy(activity, context));
    }

    /**
     * {@code count} copies of {@code crafted} just came out of a result slot for {@code player}.
     * Called from the shared {@code ItemStackMixin}; ignored off the server.
     */
    public static void itemCrafted(Player player, ItemStack crafted, int count) {
        if (!(player instanceof ServerPlayer serverPlayer) || crafted.isEmpty() || count <= 0) {
            return;
        }
        // The live stack is about to be mutated by the taking code; match against a snapshot.
        ItemStack snapshot = crafted.copy();
        QuestEngine.evaluate(serverPlayer,
                (objective, context) -> objective.creditCraft(snapshot, count, context));
    }

    /**
     * {@code victim} died. Credit goes to the player behind the blow — the direct attacker if that
     * is a player, otherwise whoever the game credits with the kill (which covers arrows, pets and
     * other indirect kills). A death with no player behind it credits nobody.
     */
    public static void entityKilled(LivingEntity victim, DamageSource source) {
        ServerPlayer killer = creditedPlayer(victim, source);
        if (killer == null) {
            return;
        }
        QuestEngine.evaluate(killer, (objective, context) -> objective.creditKill(victim, context));
    }

    /**
     * A Neroland Core progression gate changed. Either direction can change which quests a player may
     * see (AUD-19), so both re-check the affected players' synced definitions. For evaluation only
     * openings matter: {@code gate_open} objectives are sticky, so a closing gate can never complete
     * anything. Player-scope changes re-evaluate
     * just that player; server- and team-scope changes can affect anyone online.
     */
    public static void gatesChanged(GateChange change) {
        MinecraftServer server = currentServer;
        if (server == null || change == null) {
            return;
        }
        if (!server.isSameThread()) {
            // AUD-15: Core fires GateEvents on whichever thread opened the gate.
            server.execute(() -> gatesChanged(change));
            return;
        }
        // AUD-19: opening or closing a gate can change which quests a player may see; their visible
        // definitions are re-checked (and re-sent only if changed) at the end of the tick.
        if (change.scope() == GateScope.PLAYER) {
            UUID changed = parseUuid(change.target());
            if (changed != null) {
                QuestSync.markDefinitionsDirty(changed);
            }
        } else {
            QuestSync.markDefinitionsDirtyAll();
        }
        if (!change.open()) {
            return;
        }
        // AUD-14: a gate opened by a reward inside a pass re-evaluates after that pass, never recursively.
        if (change.scope() == GateScope.PLAYER) {
            UUID target = parseUuid(change.target());
            if (target == null) {
                return;
            }
            QuestEngine.afterPass(target, () -> {
                ServerPlayer player = server.getPlayerList().getPlayer(target);
                if (player != null) {
                    QuestEngine.evaluate(player);
                }
            });
            return;
        }
        QuestEngine.afterPass(EVERYONE, () -> {
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                QuestEngine.evaluate(player);
            }
        });
    }

    /** Dedupe key for "re-evaluate every online player" behind a running pass. */
    private static final Object EVERYONE = new Object();

    /**
     * Another mod reported that one of its tracked quantities crossed a threshold — a region's
     * pollution, a colony's life support, a boss's phase. Feeds {@code neroquests:custom_event}
     * objectives.
     *
     * <h2>Why this fans out</h2>
     *
     * <p>Core's {@code ThresholdCrossing} names a <b>place or system and never a person</b>
     * (POPIA/GDPR: publishers must not encode a player into a crossing), so unlike a craft or a kill
     * there is no player to hand the credit to. The trigger therefore offers the crossing to every
     * online player's evaluation pass and lets each objective decide who the news belongs to, which
     * is what the objective's {@code audience} field expresses.
     *
     * <h2>Why a shared counter still only moves once</h2>
     *
     * <p>A {@code scope: server} quest keeps one counter for the whole world, so N online players
     * fanning out over one crossing must not credit it N times. Each objective instance is claimed
     * by the first pass that credits it — by identity, because every objective in a definition file
     * decodes to its own instance — and later passes in the same fan-out see it taken. Personal
     * counters are untouched by that rule: an {@code audience: everyone} objective on a
     * {@code scope: player} quest legitimately credits each player's own row.
     *
     * <h2>Cost</h2>
     *
     * <p>One crossing costs at most one extra sweep of the online players, and publishers fire only
     * on an actual crossing (a colony that has been starving for an hour publishes nothing further),
     * so this is bounded by how often the world genuinely changes state rather than by tick rate. A
     * world with no quests loaded costs nothing at all.
     */
    public static void thresholdCrossed(ThresholdCrossing crossing) {
        MinecraftServer server = currentServer;
        if (server == null || crossing == null || crossing.channel() == null) {
            return;
        }
        if (!server.isSameThread()) {
            // AUD-15: a publisher may cross a threshold off the server thread.
            server.execute(() -> thresholdCrossed(crossing));
            return;
        }
        // AUD-14: each crossing is its own event (never deduped); inside a pass it waits for the pass.
        QuestEngine.afterPass(new Object(), () -> thresholdCrossedNow(server, crossing));
    }

    private static void thresholdCrossedNow(MinecraftServer server, ThresholdCrossing crossing) {
        // Channel id only (a crossing never names a player): makes a spine custom_event on this
        // channel "present" for the rest of the session (AUD-03).
        SeenChannels.record(crossing.channel());
        if (QuestDefinitions.questsForServer(server).isEmpty()) {
            return;
        }
        List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
        if (players.isEmpty()) {
            // Nobody to evaluate. Crossings are events, not state: a quest waiting on one simply
            // waits for the next, exactly as a kill nobody saw credits nobody.
            return;
        }
        Set<ObjectiveSpec> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerPlayer player : players) {
            QuestEngine.evaluate(player, (objective, context) -> {
                int amount = objective.creditThreshold(crossing, context);
                if (amount <= 0) {
                    return 0;
                }
                // Shared world progress moves once per crossing, however many people were online.
                if (context.scope() == QuestScope.SERVER && !claimed.add(objective)) {
                    return 0;
                }
                return amount;
            });
        }
    }

    // --- helpers ------------------------------------------------------------

    /** The player a kill is credited to, or {@code null} if no player was behind it. */
    private static ServerPlayer creditedPlayer(LivingEntity victim, DamageSource source) {
        if (source != null && source.getEntity() instanceof ServerPlayer direct) {
            return direct;
        }
        return victim.getKillCredit() instanceof ServerPlayer credited ? credited : null;
    }

    /** {@code null} for anything that is not a UUID (Core uses {@code ""} for non-player scopes). */
    private static UUID parseUuid(String target) {
        if (target == null || target.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(target);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
