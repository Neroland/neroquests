package za.co.neroland.neroquests.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.platform.Services;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestStates;
import za.co.neroland.neroquests.quest.engine.QuestEngine;

/**
 * Works out which quests just became available to a player and tells that player's client, which shows the
 * top-left "Quest Unlocked" notification. Run after every progress or definitions sync to the player
 * ({@link QuestSync}), so a completion, a gate opening, a datapack reload or a custom-quest edit can all
 * unlock something.
 *
 * <p><b>The rule.</b> Per online player, the ids of every quest that has been available to them this
 * session are remembered ({@code KNOWN}). A quest available now (the shared {@link QuestStates} rule, only
 * among the quests this player is sent) and not complete in the scope that owns it, which is not in that
 * set, is new: it is reported once and added. A repeatable quest that resets, or a quest that is locked again
 * and later re-opens, is therefore not reported twice in one session. Everything one sync unlocked goes out
 * as one {@link QuestUnlockedPayload}, and the client collapses a large batch into "+N more".
 *
 * <p><b>Join.</b> The first check for a player records what is already available without reporting it, so a
 * returning player is not told about quests they already had. The exception is a player with no quest
 * progress at all (a new player): they are shown their opening quests.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> the per-player set is quest ids in memory only, never saved, never logged,
 * pruned when the player is offline and dropped on server stop. A payload is only sent to the player it is
 * about, and never to a player whose data was erased (suppressed).
 *
 * <p>Server thread only.
 */
public final class QuestUnlocks {

    /** Per online player: every quest id that has been available to them this session. */
    private static final Map<UUID, Set<Identifier>> KNOWN = new HashMap<>();

    private QuestUnlocks() {
    }

    /** Diff {@code player}'s available quests against what they already knew, and report the new ones. */
    static void check(MinecraftServer server, ServerPlayer player) {
        UUID id = player.getUUID();
        QuestProgressState state = QuestProgressState.get(server);
        if (state.isSuppressed(id)) {
            return;
        }
        List<Identifier> available = availableNow(server, player, state);
        Set<Identifier> known = KNOWN.get(id);
        if (known == null) {
            KNOWN.put(id, new LinkedHashSet<>(available));
            if (!available.isEmpty() && state.playerProgress(id).isEmpty()) {
                Services.NETWORK.sendToPlayer(player, QuestUnlockedPayload.of(available));
            }
            return;
        }
        List<Identifier> fresh = newlyUnlocked(known, available);
        if (!fresh.isEmpty()) {
            Services.NETWORK.sendToPlayer(player, QuestUnlockedPayload.of(fresh));
        }
    }

    /** The ids of {@code available} missing from {@code known}, in order; adds them to {@code known}. Pure. */
    static List<Identifier> newlyUnlocked(Set<Identifier> known, List<Identifier> available) {
        List<Identifier> fresh = new ArrayList<>();
        for (Identifier quest : available) {
            if (known.add(quest)) {
                fresh.add(quest);
            }
        }
        return fresh;
    }

    /** Quests the player is sent, may work on now and has not completed, in definition order. */
    private static List<Identifier> availableNow(MinecraftServer server, ServerPlayer player, QuestProgressState state) {
        Map<Identifier, Quest> definitions = QuestDefinitions.questsForServer(server);
        Set<Identifier> openGates = QuestEngine.openGatesFor(player);
        Set<Identifier> visible = new LinkedHashSet<>();
        for (Quest quest : definitions.values()) {
            if (quest.isVisible(openGates)) {
                visible.add(quest.id());
            }
        }
        // The same filter as the definitions sync, so no id the client was not sent is ever named.
        QuestSync.hideUnseenReferences(definitions, visible);
        Set<Identifier> playerCompleted = state.completedQuests(player.getUUID());
        Set<Identifier> serverCompleted = state.completedServerQuests();
        List<Identifier> out = new ArrayList<>();
        for (Identifier questId : visible) {
            Quest quest = definitions.get(questId);
            Set<Identifier> completed = quest.scope() == QuestScope.SERVER ? serverCompleted : playerCompleted;
            if (!completed.contains(questId)
                    && QuestStates.isAvailable(quest, definitions, playerCompleted, serverCompleted, openGates)) {
                out.add(questId);
            }
        }
        return out;
    }

    /** Forget players who are no longer online. */
    static void retainOnline(Set<UUID> online) {
        KNOWN.keySet().retainAll(online);
    }

    /** Whether the map has grown past what the online players need (pruning hint). */
    static int size() {
        return KNOWN.size();
    }

    /** Drop everything (server stop). */
    static void clear() {
        KNOWN.clear();
    }
}
