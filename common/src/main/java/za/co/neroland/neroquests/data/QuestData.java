package za.co.neroland.neroquests.data;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.custom.CustomQuestState;
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
            CustomQuestState custom = CustomQuestState.get(server);
            if (custom.erase(uuid)) {
                custom.backupNow(server);
            }
        });
    }
}
