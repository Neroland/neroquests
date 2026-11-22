package za.co.neroland.neroquests.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.nerolandcore.data.PlayerDataExport;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.custom.CustomQuestState;
import za.co.neroland.neroquests.custom.CustomRewardPolicy;
import za.co.neroland.neroquests.quest.reward.CurrencyPayouts;

/**
 * Registers NeroQuests' player-keyed storage with Neroland Core's shared
 * {@link PlayerDataErasure} hook, so one erase request ({@code /neroland data eraseme}
 * or an admin erase) purges a player's quest progress together with every other
 * Nero mod's data. Called once from {@link NeroQuestsCommon#init()}.
 *
 * <p>POPIA/GDPR: quest storage ({@link QuestProgressState}) holds only quest IDs,
 * objective counters and timestamps keyed by the player's game UUID — see PRIVACY.md.
 * Erasure must never log player identity. The custom-quest store ({@link CustomQuestState}) is reached
 * by the same eraser: its {@code created_by} fields are cleared and the editor allow-list entry removed.
 */
public final class QuestData {

    private QuestData() {
    }

    public static void init() {
        // Purges every quest row and the retention stamp held for that UUID. Shared
        // server-scoped quest progress is untouched — it belongs to the world and holds no
        // identifiers. Nothing here logs who was erased.
        PlayerDataErasure.register((server, uuid) -> {
            QuestProgressState state = QuestProgressState.get(server);
            state.forgetPlayer(uuid);
            // J1: an in-flight payout record (normally none) goes too.
            CurrencyPayouts.journal(server).erase(uuid);
            // The recovery backup is a second copy of the same rows: erase there too, now (AUD-16).
            state.backupNow(server);
            // D1: custom quests they authored keep existing with created_by cleared; they leave the editor
            // allow-list. The store's backup is refreshed in the same request.
            // J2: their in-memory custom-payout tally for today goes as well.
            CustomRewardPolicy.BUDGET.forget(uuid);
            CustomQuestState custom = CustomQuestState.get(server);
            if (custom.erase(uuid)) {
                custom.backupNow(server);
            }
        });
        // The read-side twin: Core's /neroland data exportme carries the same document as /neroquests export.
        PlayerDataExport.register(NeroQuestsCommon.MOD_ID, QuestData::coreExport);
    }

    /**
     * One player's own quest data as JSON: progress, owed rewards, the paid ledger, repeat timestamps, daily
     * contracts, and the custom quests they authored plus their editor allow-list membership. The single
     * document behind both {@code /neroquests export} and Core's export. Nobody else's rows, no shared
     * server-scoped progress, no UUID or name. Works for offline players (it reads the saved stores only).
     */
    public static JsonObject document(MinecraftServer server, UUID player) {
        JsonObject json = QuestProgressState.exportPlayer(server, player);
        json.add("custom_quests", CustomQuestState.get(server).export(player));
        return json;
    }

    /** Core {@code PlayerDataExporter}: {@link #document}, or {@code null} when NeroQuests stores nothing. */
    @Nullable
    public static JsonElement coreExport(MinecraftServer server, UUID player) {
        JsonObject json = document(server, player);
        return isEmpty(json) ? null : json;
    }

    /** Whether a {@link #document} holds no row for the player. */
    public static boolean isEmpty(JsonObject json) {
        if (json.has("last_updated") && json.get("last_updated").getAsLong() != 0L) {
            return false;
        }
        if (json.has("daily_contracts")) {
            return false;
        }
        for (String key : new String[] {"owed_rewards", "paid_quests", "revoked_quests", "repeat_paid"}) {
            if (json.has(key) && json.getAsJsonArray(key).size() > 0) {
                return false;
            }
        }
        if (json.has("quests") && json.getAsJsonObject("quests").size() > 0) {
            return false;
        }
        JsonObject custom = json.has("custom_quests") ? json.getAsJsonObject("custom_quests") : new JsonObject();
        JsonArray authored = custom.has("authored_custom_quests")
                ? custom.getAsJsonArray("authored_custom_quests") : new JsonArray();
        boolean allowed = custom.has("editor_allow_list") && custom.get("editor_allow_list").getAsBoolean();
        return authored.isEmpty() && !allowed;
    }
}
