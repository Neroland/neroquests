package za.co.neroland.neroquests.link;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.link.LinkActionHandler;
import za.co.neroland.nerolandcore.link.LinkActionResult;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.data.OwedReward;
import za.co.neroland.neroquests.data.QuestProgress;
import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.reward.CurrencyPayouts;
import za.co.neroland.neroquests.quest.reward.OwedDelivery;

/**
 * The write half of the link module — one action, {@code claim_reward}, which hands over what the
 * owed queue keeps for the player (AUD-09).
 *
 * <p>Rewards still fire automatically the moment a quest completes. What a player could not take then
 * — credits the economy refused, items and experience while they were offline, dead or full — is kept
 * in the owed queue ({@code OwedDelivery}, {@code CurrencyPayouts}). {@code claim_reward} settles it:
 *
 * <pre>{@code params:  { "quest"?: "<quest id>", "key"?: "<owed entry key>" }
 * result:  { "delivered": [ {"type":"currency","currency":..,"amount":..}
 *                         | {"type":"item","item":..,"count":..} | {"type":"xp","amount":..} ],
 *            "remaining": n, "owed": [ {type, source, item|currency, count|amount, permanent} ],
 *            "claimed": true, "granted_now": bool, "note": "..",
 *            "quest"?, "scope"?, "completed_at"? }}</pre>
 *
 * <p>With a {@code quest}, only entries from that quest are settled; without, every deliverable entry.
 * Offline (allowed, see {@link #allowOffline(String)}) only <b>currency</b> can be settled; items and
 * experience stay and count as remaining. Every entry is settled by its own request key, so a repeated
 * claim never hands anything over twice. The response carries no UUID or name.
 *
 * <h2>Validation</h2>
 *
 * <p>Server-authoritative, and the incoming {@link UUID} is trusted for <em>nothing beyond scoping
 * the request to that player's own data</em>. A given {@code quest} must be a well-formed id naming a
 * loaded quest this player may <em>see</em> — a quest hidden behind an unopened {@code visible_gate}
 * answers exactly like one that does not exist — and must be complete for them (or, at
 * {@code scope: server}, for the world); otherwise {@link LinkActionResult.Error#VALIDATION}.
 *
 * <p>Server thread only.
 */
public final class QuestLinkActions implements LinkActionHandler {

    private static final List<String> ACTIONS = List.of(QuestLinkModule.ACTION_CLAIM_REWARD);

    /** How long an off-thread claim waits for the server thread. */
    private static final long CLAIM_TIMEOUT_SECONDS = 10L;

    /** Sent back when nothing (matching) is owed any more. */
    private static final String NOTHING_OWED_NOTE = "nothing is owed; rewards are granted automatically on completion";

    /** Sent back when entries remain (refused credits, or items/experience that need the player in-game). */
    private static final String STILL_OWED_NOTE = "some rewards are still kept; items and experience are handed over in-game";

    @Override
    public String moduleId() {
        return QuestLinkModule.MODULE_ID;
    }

    @Override
    public List<String> actionIds() {
        return ACTIONS;
    }

    /**
     * {@code true} for {@code claim_reward} only (AUD-09). An offline claim can only settle owed
     * <b>currency</b> — balances are UUID-keyed — so items and experience stay kept and are reported as
     * remaining until the player is in the world to receive them.
     */
    @Override
    public boolean allowOffline(String actionId) {
        return QuestLinkModule.ACTION_CLAIM_REWARD.equals(actionId);
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
            if (server.isSameThread()) {
                return claimReward(server, playerId, params);
            }
            // J2 (RT-17): Core promises the server thread, but nothing here relies on it — a call from any other
            // thread (an HTTP worker) hops onto the server thread and waits, so a claim can never race the join,
            // respawn or periodic delivery of the same owed entry.
            return server.submit(() -> claimReward(server, playerId, params)).get(CLAIM_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LinkActionResult.error(LinkActionResult.Error.INTERNAL, "The claim could not be processed.");
        } catch (ExecutionException | TimeoutException e) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] NeroLink action '{}' did not finish on the server thread ({}).",
                    actionId, e.getClass().getSimpleName());
            return LinkActionResult.error(LinkActionResult.Error.INTERNAL, "The claim could not be processed.");
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
        boolean questGiven = params != null && params.has("quest");
        Identifier questId = questId(params);
        if (questGiven && questId == null) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "The 'quest' parameter must hold a quest id.");
        }
        boolean keyGiven = params != null && params.has("key");
        String key = entryKey(params);
        if (keyGiven && key == null) {
            return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    "The 'key' parameter must hold an owed entry key.");
        }

        QuestProgressState state = QuestProgressState.get(server);
        JsonObject result = new JsonObject();
        if (key != null) {
            // G1 request idempotency: the owed entry key is the request id. An entry that is gone was
            // already settled (or never existed for this player) — answer success with nothing delivered.
            boolean present = false;
            for (OwedReward entry : state.owed(playerId)) {
                if (key.equals(entry.requestKey())) {
                    present = true;
                    break;
                }
            }
            result.addProperty("key", key);
            result.addProperty("already_settled", !present);
        }
        if (questId != null) {
            Map<Identifier, Quest> definitions = QuestDefinitions.questsForServer(server);
            Quest quest = definitions.get(questId);
            // Gate visibility is resolved for the player themselves, so a hidden quest is indist-
            // inguishable from a missing one — the app can never probe for hidden content.
            Set<Identifier> openGates = QuestLinkAccess.openGates(server, playerId);
            if (quest == null || !quest.isVisible(openGates)) {
                return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                        "There is no quest '" + questId + "' here.");
            }
            boolean serverScope = quest.scope() == QuestScope.SERVER;
            QuestProgress progress = (serverScope
                    ? state.serverProgress(quest.id())
                    : state.progress(playerId, quest.id())).orElse(QuestProgress.EMPTY);
            if (!progress.isComplete()) {
                return LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                        "Quest '" + questId + "' is not complete, so it has no reward to claim.");
            }
            result.addProperty("quest", questId.toString());
            result.addProperty("scope", QuestLinkSnapshots.scopeName(quest.scope()));
            result.addProperty("completed_at", progress.completedAt());
        }

        // Currency first (UUID-keyed: works offline), then — only for a player in the world who can
        // take them — kept items and experience. Each entry is settled by its own key, so pressing
        // claim twice can never hand anything over twice.
        JsonArray delivered = new JsonArray();
        long credits = 0L;
        for (OwedReward entry : state.owed(playerId)) {
            if (!entry.isCurrency() || (questId != null && !questId.equals(entry.source()))
                    || (key != null && !key.equals(entry.requestKey()))) {
                continue;
            }
            if (CurrencyPayouts.claimOne(server, playerId, entry)) {
                JsonObject row = new JsonObject();
                row.addProperty("type", "currency");
                row.addProperty("currency", entry.currency().toString());
                row.addProperty("amount", entry.amount());
                delivered.add(row);
                credits += entry.amount();
            }
        }
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (credits > 0L && online != null) {
            online.sendSystemMessage(Component.translatable("message.neroquests.owed_paid", Long.valueOf(credits)));
        }
        if (OwedDelivery.canReceive(online)) {
            for (OwedDelivery.Delivered given : OwedDelivery.deliver(online, questId, key, false)) {
                JsonObject row = new JsonObject();
                row.addProperty("type", given.kind().serialName());
                if (given.kind() == OwedReward.Kind.ITEM) {
                    row.addProperty("item", given.id().toString());
                    row.addProperty("count", given.amount());
                } else {
                    row.addProperty("amount", given.amount());
                }
                delivered.add(row);
            }
        }

        JsonArray owed = new JsonArray();
        for (OwedReward entry : state.owed(playerId)) {
            if ((questId != null && !questId.equals(entry.source()))
                    || (key != null && !key.equals(entry.requestKey()))) {
                continue;
            }
            // Schema-2 owed row (key, type, amount|item+count, currency?, quest?, reason, created_at,
            // permanent) plus the schema-1 'source'.
            JsonObject row = QuestLinkJson.owedRow(entry);
            row.addProperty("source", entry.source().toString());
            owed.add(row);
        }

        result.add("delivered", delivered);
        result.addProperty("remaining", owed.size());
        result.add("owed", owed);
        // Pre-AUD-09 fields, kept for clients written against schema 1.
        result.addProperty("claimed", true);
        result.addProperty("granted_now", !delivered.isEmpty());
        result.addProperty("note", owed.isEmpty() ? NOTHING_OWED_NOTE : STILL_OWED_NOTE);
        return LinkActionResult.ok(result);
    }

    /** The {@code key} parameter (an owed entry key, at most 256 chars), or {@code null} if absent or malformed. */
    private static String entryKey(JsonObject params) {
        if (params == null || !params.has("key")) {
            return null;
        }
        JsonElement element = params.get("key");
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String raw = element.getAsString();
        return raw == null || raw.isBlank() || raw.length() > 256 ? null : raw.trim();
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
