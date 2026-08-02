package za.co.neroland.neroquests.data;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * Registers NeroQuests' player-keyed storage with Neroland Core's shared
 * {@link PlayerDataErasure} hook, so one erase request ({@code /neroland data eraseme}
 * or an admin erase) purges a player's quest progress together with every other
 * Nero mod's data. Called once from {@link NeroQuestsCommon#init()}.
 *
 * <p>POPIA/GDPR: quest storage ({@link QuestProgressState}) holds only quest IDs,
 * objective counters and timestamps keyed by the player's game UUID — see PRIVACY.md.
 * Erasure must never log player identity.
 */
public final class QuestData {

    private QuestData() {
    }

    public static void init() {
        // Purges every quest row and the retention stamp held for that UUID. Shared
        // server-scoped quest progress is untouched — it belongs to the world and holds no
        // identifiers. Nothing here logs who was erased.
        PlayerDataErasure.register((server, uuid) -> QuestProgressState.get(server).forgetPlayer(uuid));
    }
}
