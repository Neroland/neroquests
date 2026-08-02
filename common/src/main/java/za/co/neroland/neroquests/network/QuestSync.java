package za.co.neroland.neroquests.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.platform.Services;
import za.co.neroland.neroquests.quest.QuestDefinitions;

/**
 * The one server-side entry point for pushing quest state to clients, so every call site — the
 * loaders' join hooks, the reload hook, the engine — stays free of loader types and of payload
 * construction.
 *
 * <p>Three moments produce a sync:
 *
 * <ul>
 *   <li><b>Join</b> — {@link #syncTo(ServerPlayer)} sends the joining player the definition set
 *       and their own progress. Each loader entry point wires its own join event to this.</li>
 *   <li><b>Reload</b> — {@link #syncAll(MinecraftServer)} after a datapack reload has re-read and
 *       re-validated the definitions, because both halves may have changed meaning.</li>
 *   <li><b>Change</b> — {@link #syncProgressTo(ServerPlayer)} (or
 *       {@link #syncProgressAll(MinecraftServer)} when a {@code scope: server} quest moved) at the
 *       end of an engine evaluation pass that actually mutated something.</li>
 * </ul>
 *
 * <p>The definition snapshot is expensive to build (every quest re-encoded to JSON) and identical
 * for every recipient, so it is cached and rebuilt only when
 * {@link QuestDefinitions#generation()} shows the definitions have been re-read. Progress
 * snapshots are per-player and cheap, and are always built fresh.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> a progress payload is only ever addressed to the player whose
 * rows it contains. Nothing here broadcasts one player's progress to another — a server-scope
 * change fans out one <em>individually built</em> snapshot per player, each carrying that player's
 * own rows plus the identifier-free shared section.
 *
 * <p>Server thread only.
 */
public final class QuestSync {

    private static MinecraftServer cachedFor;
    private static int cachedGeneration = -1;
    private static QuestDefinitionsPayload cachedDefinitions = QuestDefinitionsPayload.EMPTY;

    private QuestSync() {
    }

    // --- join ---------------------------------------------------------------

    /** Everything a joining player needs: the definition set plus their own progress. */
    public static void syncTo(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player, definitions(server));
        Services.NETWORK.sendToPlayer(player, QuestProgressPayload.of(server, player));
    }

    // --- reload -------------------------------------------------------------

    /** Re-send definitions and progress to every online player (after a datapack reload). */
    public static void syncAll(MinecraftServer server) {
        QuestDefinitionsPayload definitions = definitions(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Services.NETWORK.sendToPlayer(player, definitions);
            Services.NETWORK.sendToPlayer(player, QuestProgressPayload.of(server, player));
        }
    }

    /** Re-send just the definition set to every online player. */
    public static void syncDefinitions(MinecraftServer server) {
        QuestDefinitionsPayload definitions = definitions(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Services.NETWORK.sendToPlayer(player, definitions);
        }
    }

    /** Re-send just the definition set to one player. */
    public static void syncDefinitionsTo(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            Services.NETWORK.sendToPlayer(player, definitions(server));
        }
    }

    // --- change -------------------------------------------------------------

    /** Send one player a fresh snapshot of their own progress. */
    public static void syncProgressTo(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            Services.NETWORK.sendToPlayer(player, QuestProgressPayload.of(server, player));
        }
    }

    /**
     * Send every online player a fresh snapshot of their own progress — used when shared
     * {@code scope: server} progress moved, which everyone can see.
     */
    public static void syncProgressAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Services.NETWORK.sendToPlayer(player, QuestProgressPayload.of(server, player));
        }
    }

    // --- internals ----------------------------------------------------------

    /** The definition snapshot for this server, rebuilt only when the definitions were re-read. */
    private static synchronized QuestDefinitionsPayload definitions(MinecraftServer server) {
        int generation = QuestDefinitions.generation();
        if (server != cachedFor || generation != cachedGeneration) {
            cachedDefinitions = QuestDefinitionsPayload.of(server);
            cachedFor = server;
            cachedGeneration = QuestDefinitions.generation();
        }
        return cachedDefinitions;
    }
}
