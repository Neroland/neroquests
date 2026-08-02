package za.co.neroland.neroquests.link;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.link.LinkActionHandler;
import za.co.neroland.nerolandcore.link.LinkActionResult;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.data.QuestProgress;
import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;

/**
 * The write half of the link module — a deliberately tiny surface: one action,
 * {@code claim_reward}, and it pays out nothing.
 *
 * <h2>Why a no-op action exists</h2>
 *
 * <p>In this version rewards fire <em>automatically</em> the moment a quest completes
 * ({@code QuestRewards} subscribes to the completion channel), so there is nothing left to claim
 * and claiming again must never double-pay. The action is nevertheless part of the module's
 * advertised surface so companion clients have a <b>stable id to code against from day one</b>: a
 * "claim" button written today keeps working unchanged when the deferred-reward queue lands (see
 * the TODO in {@code QuestRewards}), at which point this handler starts actually handing over the
 * parked rewards.
 *
 * <p>Until then a valid call answers {@link LinkActionResult#ok(JsonObject)} with
 * {@code {"claimed": true, "note": "rewards are granted automatically on completion"}} — a truthful
 * "there is nothing outstanding", not a failure.
 *
 * <h2>Validation</h2>
 *
 * <p>Server-authoritative, and the incoming {@link UUID} is trusted for <em>nothing beyond scoping
 * the request to that player's own data</em>:
 *
 * <ol>
 *   <li>{@code quest} must be present and a well-formed id, and must name a loaded quest that this
 *       player may <em>see</em> — a quest hidden behind an unopened {@code visible_gate} answers
 *       exactly like a quest that does not exist, so its existence cannot be probed
 *       ({@link LinkActionResult.Error#VALIDATION});</li>
 *   <li>the quest must be complete for this player (or, at {@code scope: server}, for the world)
 *       ({@link LinkActionResult.Error#VALIDATION});</li>
 *   <li>the player must be online
 *       ({@link LinkActionResult.Error#PLAYER_OFFLINE_REQUIRED}) — see
 *       {@link #allowOffline(String)}.</li>
 * </ol>
 *
 * <p>Server thread only.
 */
public final class QuestLinkActions implements LinkActionHandler {

    private static final List<String> ACTIONS = List.of(QuestLinkModule.ACTION_CLAIM_REWARD);

    /** Sent back on a successful claim, so the app can explain why nothing arrived in-game. */
    private static final String AUTO_GRANT_NOTE = "rewards are granted automatically on completion";

    @Override
    public String moduleId() {
        return QuestLinkModule.MODULE_ID;
    }

    @Override
    public List<String> actionIds() {
        return ACTIONS;
    }

    /**
     * {@code false} — deliberately, for now. Core's javadoc names a reward claim as the canonical
     * offline-capable action, and it will become one here: once unclaimed rewards are parked in a
     * queue they can be granted to a logged-off player. Today they cannot — item and experience
     * rewards need a live body to land in and are skipped for an offline recipient — so an offline
     * claim would silently do nothing while reporting success. Refusing it is the honest answer.
     */
    @Override
    public boolean allowOffline(String actionId) {
        return false;
    }

    @Override
    public LinkActionResult execute(UUID playerId, String actionId, JsonObject params) {
        if (!QuestLinkModule.ACTION_CLAIM_REWARD.equals(actionId)) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "NeroQuests does not know the action '" + actionId + "'.");
        }
        if (playerId == null) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION, "No player was supplied.");
        }
        MinecraftServer server = QuestLinkAccess.server();
        if (server == null) {
            return LinkActionResult.error(LinkActionResult.Error.INTERNAL,
                    "The server is not running a world yet.");
        }
        try {
            return claimReward(server, playerId, params);
        } catch (RuntimeException e) {
            // Action id only — never who asked (POPIA/GDPR).
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] NeroLink action '{}' failed.", actionId, e);
            return LinkActionResult.error(LinkActionResult.Error.INTERNAL,
                    "The claim could not be processed.");
        }
    }

    // --- claim_reward ---------------------------------------------------------

    private static LinkActionResult claimReward(MinecraftServer server, UUID playerId,
                                                JsonObject params) {
        Identifier questId = questId(params);
        if (questId == null) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "This action needs a 'quest' parameter holding a quest id.");
        }

        Map<Identifier, Quest> definitions = QuestDefinitions.questsForServer(server);
        Quest quest = definitions.get(questId);
        // Gate visibility is resolved for the player themselves, so a hidden quest is indist-
        // inguishable from a missing one — the app can never probe for hidden content.
        Set<Identifier> openGates = QuestLinkAccess.openGates(server, playerId);
        if (quest == null || !quest.isVisible(openGates)) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "There is no quest '" + questId + "' here.");
        }

        QuestProgressState state = QuestProgressState.get(server);
        boolean serverScope = quest.scope() == QuestScope.SERVER;
        QuestProgress progress = (serverScope
                ? state.serverProgress(quest.id())
                : state.progress(playerId, quest.id())).orElse(QuestProgress.EMPTY);
        if (!progress.isComplete()) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "Quest '" + questId + "' is not complete, so it has no reward to claim.");
        }

        if (!QuestLinkAccess.isOnline(server, playerId)) {
            return LinkActionResult.error(LinkActionResult.Error.PLAYER_OFFLINE_REQUIRED,
                    "Quest rewards can only be claimed while you are online.");
        }

        // Nothing is granted: the payout already ran when the quest completed. Answering 'ok'
        // keeps the action idempotent and safe to press twice.
        JsonObject result = new JsonObject();
        result.addProperty("quest", questId.toString());
        result.addProperty("scope", QuestLinkSnapshots.scopeName(quest.scope()));
        result.addProperty("claimed", true);
        result.addProperty("granted_now", false);
        result.addProperty("completed_at", progress.completedAt());
        result.addProperty("note", AUTO_GRANT_NOTE);
        return LinkActionResult.ok(result);
    }

    /** The {@code quest} parameter as an id, or {@code null} if it is absent or malformed. */
    private static Identifier questId(JsonObject params) {
        if (params == null || !params.has("quest")) {
            return null;
        }
        JsonElement element = params.get("quest");
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String raw = element.getAsString();
        return raw == null || raw.isBlank() ? null : Identifier.tryParse(raw.trim());
    }
}
