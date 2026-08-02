package za.co.neroland.neroquests.link;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.progression.ProgressionState;
import za.co.neroland.neroquests.quest.engine.QuestEngine;
import za.co.neroland.neroquests.quest.engine.QuestTriggers;

/**
 * The two questions both link surfaces have to answer before they may say anything: <em>which
 * server is running</em>, and <em>which gates are open for this player</em> (which decides what
 * they are allowed to see).
 *
 * <p>Core's snapshot/action API hands over a {@link UUID} and nothing else — no server, no player —
 * so the running server is taken from {@link QuestTriggers}, which already captures it for the
 * progression-gate listener.
 *
 * <p><b>Offline players.</b> A companion client may ask while its player is logged off. Core's
 * {@code resolvedOpenGates} needs a live {@link ServerPlayer}, so for an offline player the gate
 * set is rebuilt from the stored progression state: their own player-scope gates plus the
 * server-scope ones. Team-scope gates need a live player to resolve a team and are therefore
 * treated as closed while offline — the conservative direction, since a gate read as closed only
 * ever <em>hides</em> a quest and never leaks one.
 *
 * <p>Server thread only; reads nothing but progression state.
 */
final class QuestLinkAccess {

    private QuestLinkAccess() {
    }

    /** The running server, or {@code null} before the first server tick / after shutdown. */
    static MinecraftServer server() {
        return QuestTriggers.currentServer();
    }

    /**
     * The Neroland Core gate ids currently open for this player, as {@link Identifier}s — the set
     * {@link za.co.neroland.neroquests.quest.Quest#isVisible(Set)} filters against, so a quest
     * hidden behind an unopened {@code visible_gate} is hidden over the link too.
     */
    static Set<Identifier> openGates(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            // Online: the engine's own resolution, so the link and the game agree exactly.
            return QuestEngine.openGatesFor(player);
        }
        ProgressionState state = ProgressionState.get(server);
        Set<Identifier> gates = new LinkedHashSet<>();
        addParsed(gates, state.playerGates(playerId));
        addParsed(gates, state.serverGates());
        return gates;
    }

    /** Whether this player is online right now (their gate set is only fully resolvable then). */
    static boolean isOnline(MinecraftServer server, UUID playerId) {
        return server.getPlayerList().getPlayer(playerId) != null;
    }

    /** Core stores gate ids as strings; anything unparseable is dropped rather than thrown. */
    private static void addParsed(Set<Identifier> target, Set<String> ids) {
        for (String id : ids) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed != null) {
                target.add(parsed);
            }
        }
    }
}
