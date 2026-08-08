package za.co.neroland.neroquests.quest.engine;

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
import za.co.neroland.neroquests.network.QuestSync;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;
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
 * <p>Every method is server-thread only and silently ignores anything that is not a
 * {@link ServerPlayer}, so a loader handing over a client-side callback cannot corrupt progress.
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
        if (currentServer != server) {
            // New server instance (world switch, integrated-server restart): start clean.
            currentServer = server;
            ticksSinceSweep = 0;
            MissingContent.reset();
            RewardLog.reset();
        }
        if (QuestDefinitions.refreshIfReloaded(server)) {
            // Definitions changed under everyone's feet: re-sync the whole picture. Warn-once
            // "missing content" state is keyed on the old definitions, so let it speak again.
            MissingContent.reset();
            QuestSync.syncAll(server);
        }
        if (++ticksSinceSweep < SWEEP_INTERVAL_TICKS) {
            return;
        }
        ticksSinceSweep = 0;
        if (QuestDefinitions.questsForServer(server).isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            QuestEngine.evaluate(player);
        }
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
        QuestSync.syncTo(player);
    }

    /**
     * The player's carried items may have changed — re-measure their collect objectives. Cheap and
     * idempotent; safe to call from anywhere on the server thread.
     */
    public static void itemsChanged(ServerPlayer player) {
        QuestEngine.evaluate(player);
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
     * The player arrived in a (possibly different) dimension — re-measure right away.
     *
     * <p>Nothing calls this today: {@code reach_dimension} is measured by {@link #serverTick}, so
     * arriving somewhere new registers within a second on every loader with no per-loader event to
     * keep in step. It stays part of the facade because it is the correct hook for anything that
     * wants the change reflected immediately — a teleport command, a rocket landing — and because
     * a loader gaining a suitable event later only has to call this.
     */
    public static void dimensionEntered(ServerPlayer player) {
        QuestEngine.evaluate(player);
    }

    /**
     * A Neroland Core progression gate changed. Only openings matter: {@code gate_open} objectives
     * are sticky, so a closing gate can never complete anything. Player-scope changes re-evaluate
     * just that player; server- and team-scope changes can affect anyone online.
     */
    public static void gatesChanged(GateChange change) {
        MinecraftServer server = currentServer;
        if (server == null || !change.open()) {
            return;
        }
        if (change.scope() == GateScope.PLAYER) {
            UUID target = parseUuid(change.target());
            ServerPlayer player = target == null ? null : server.getPlayerList().getPlayer(target);
            if (player != null) {
                QuestEngine.evaluate(player);
            }
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            QuestEngine.evaluate(player);
        }
    }

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
