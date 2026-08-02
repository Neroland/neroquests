package za.co.neroland.neroquests.link;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerolandcore.link.LinkEvent;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.engine.QuestCompletion;
import za.co.neroland.neroquests.quest.engine.QuestCompletionListeners;
import za.co.neroland.neroquests.quest.engine.QuestEngine;

/**
 * The live half of the link module: the two things worth waking a companion client for.
 *
 * <ul>
 *   <li><b>{@code quest_completed}</b> — one quest finished. Published from the same completion
 *       channel rewards hang off ({@link QuestCompletionListeners}), so it fires exactly once per
 *       completion, however the completion came about (played, cascaded, or granted by an
 *       operator). Payload: quest id, title key, scope, timestamp.</li>
 *   <li><b>{@code progress}</b> — objective counters moved. Published by {@link QuestEngine} at the
 *       end of an evaluation pass that actually mutated something, alongside the client sync it
 *       already sends there — so at most <em>one</em> player event per pass, and a settled world
 *       produces none at all (the 1 Hz sweep is silent when nothing changed).</li>
 * </ul>
 *
 * <h2>Scope and privacy (POPIA/GDPR)</h2>
 *
 * <p>A {@code scope: player} event is published with {@link LinkEvent#forPlayer}, which the bridge
 * routes to that player's sessions only. A {@code scope: server} event belongs to the world and is
 * published with {@link LinkEvent#broadcast} — <em>without</em> a UUID, so nothing says who tipped
 * it over. Broadcast payloads carry quest ids and counters only.
 *
 * <p>Because a broadcast reaches every session, a server-scope quest that declares a
 * {@code visible_gate} is deliberately <b>not</b> broadcast: announcing it would reveal hidden
 * content to players whose gate is still shut. Those players see the quest the moment their own
 * snapshot includes it.
 *
 * <p>Server thread only.
 */
public final class QuestLinkEvents {

    private QuestLinkEvents() {
    }

    /** Subscribe to the in-mod completion channel. Called from {@link QuestLinkModule#init()}. */
    static void init() {
        QuestCompletionListeners.onCompletion(QuestLinkEvents::onCompletion);
    }

    // --- quest_completed ------------------------------------------------------

    private static void onCompletion(QuestCompletion completion) {
        Quest quest = completion.quest();
        boolean serverScope = quest.scope() == QuestScope.SERVER;
        if (serverScope && quest.visibleGate().isPresent()) {
            return; // never broadcast gated content (see the class javadoc)
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("schema_version", QuestLinkModule.SCHEMA_VERSION);
        payload.addProperty("quest", quest.id().toString());
        payload.addProperty("title", quest.title());
        payload.addProperty("scope", QuestLinkSnapshots.scopeName(quest.scope()));
        payload.addProperty("timestamp", System.currentTimeMillis());
        publish(serverScope
                ? LinkEvent.broadcast(QuestLinkModule.MODULE_ID,
                        QuestLinkModule.TOPIC_QUEST_COMPLETED, payload)
                : LinkEvent.forPlayer(QuestLinkModule.MODULE_ID,
                        QuestLinkModule.TOPIC_QUEST_COMPLETED, completion.player(), payload));
    }

    // --- progress -------------------------------------------------------------

    /**
     * Publish what one engine evaluation pass changed: at most one player-scoped event for
     * {@code player} and one world broadcast, both omitted when the matching map is empty.
     *
     * <p>Called once, at the end of a pass, from {@link QuestEngine} — the same place the client
     * sync payload goes out — so a burst of writes inside a pass collapses into a single event.
     *
     * @param player        the evaluated player (the only recipient of the player-scoped event)
     * @param playerChanges quest id &rarr; (objective index &rarr; new counter) for that player's
     *                      own rows; may be {@code null} or empty
     * @param serverChanges the same for the shared {@code scope: server} section; may be
     *                      {@code null} or empty
     */
    public static void publishProgress(UUID player,
                                       Map<Identifier, Map<Integer, Integer>> playerChanges,
                                       Map<Identifier, Map<Integer, Integer>> serverChanges) {
        try {
            if (player != null && playerChanges != null && !playerChanges.isEmpty()) {
                JsonObject payload = progressPayload("player", playerChanges, false);
                if (payload != null) {
                    publish(LinkEvent.forPlayer(QuestLinkModule.MODULE_ID,
                            QuestLinkModule.TOPIC_PROGRESS, player, payload));
                }
            }
            if (serverChanges != null && !serverChanges.isEmpty()) {
                JsonObject payload = progressPayload("server", serverChanges, true);
                if (payload != null) {
                    publish(LinkEvent.broadcast(QuestLinkModule.MODULE_ID,
                            QuestLinkModule.TOPIC_PROGRESS, payload));
                }
            }
        } catch (RuntimeException e) {
            // A link event must never disturb the engine pass that produced it.
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Publishing a NeroLink progress event failed.", e);
        }
    }

    /**
     * {@code {"schema_version":1,"scope":"player","quests":[{"quest":"…","title":"…",
     * "objectives":[{"index":0,"current":3,"target":10}]}]}}, or {@code null} when nothing
     * survived filtering.
     *
     * @param broadcast whether this payload goes to every session, in which case gated quests are
     *                  dropped
     */
    private static JsonObject progressPayload(String scope,
                                              Map<Identifier, Map<Integer, Integer>> changes,
                                              boolean broadcast) {
        JsonArray quests = new JsonArray();
        for (Map.Entry<Identifier, Map<Integer, Integer>> questChanges : changes.entrySet()) {
            Identifier questId = questChanges.getKey();
            Quest quest = QuestDefinitions.quest(questId).orElse(null);
            if (broadcast && quest != null && quest.visibleGate().isPresent()) {
                continue;
            }
            JsonArray objectives = new JsonArray();
            for (Map.Entry<Integer, Integer> change : questChanges.getValue().entrySet()) {
                Integer boxedIndex = change.getKey();
                Integer boxedValue = change.getValue();
                if (boxedIndex == null || boxedIndex.intValue() < 0) {
                    continue;
                }
                int index = boxedIndex.intValue();
                int current = boxedValue == null ? 0 : Math.max(boxedValue.intValue(), 0);
                int target = targetOf(quest, index);
                JsonObject objective = new JsonObject();
                objective.addProperty("index", index);
                objective.addProperty("current", target > 0 ? Math.min(current, target) : current);
                if (target > 0) {
                    objective.addProperty("target", target);
                }
                objectives.add(objective);
            }
            if (objectives.isEmpty()) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("quest", questId.toString());
            if (quest != null) {
                row.addProperty("title", quest.title());
            }
            row.add("objectives", objectives);
            quests.add(row);
        }
        if (quests.isEmpty()) {
            return null;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("schema_version", QuestLinkModule.SCHEMA_VERSION);
        payload.addProperty("scope", scope);
        payload.add("quests", quests);
        return payload;
    }

    /** One objective's target, or {@code -1} when the quest/objective is no longer loaded. */
    private static int targetOf(Quest quest, int index) {
        if (quest == null) {
            return -1;
        }
        List<ObjectiveSpec> objectives = quest.objectives();
        if (index >= objectives.size()) {
            return -1;
        }
        return Math.max(1, objectives.get(index).target());
    }

    // --- plumbing -------------------------------------------------------------

    /** Publish to Core's shared bus; a failure there is logged, never thrown at the caller. */
    private static void publish(LinkEvent event) {
        try {
            NeroLinkRegistry.eventBus().publish(event);
        } catch (RuntimeException e) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Publishing the NeroLink '{}' event failed.",
                    event.topic(), e);
        }
    }
}
