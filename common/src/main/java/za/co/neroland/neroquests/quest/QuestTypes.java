package za.co.neroland.neroquests.quest;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;
import za.co.neroland.neroquests.quest.reward.XpReward;

/**
 * Registers NeroQuests' built-in objective and reward types. Called once from
 * {@link NeroQuestsCommon#init()} — it must run before {@link QuestDefinitions} reads any
 * datapack file, or every objective/reward would decode as "unknown type" and drop its quest.
 *
 * <p>Only the two types that prove the dispatch end-to-end ship in this stage; the full set
 * (item/entity/dimension/gate objectives, item/gate/currency/reputation rewards) arrives with
 * the objective and reward engines.
 */
public final class QuestTypes {

    /** Objective {@code neroquests:quest_complete}. */
    public static final ObjectiveType<QuestCompleteObjective> QUEST_COMPLETE =
            ObjectiveTypes.register(QuestCompleteObjective.TYPE_ID, QuestCompleteObjective.CODEC);

    /** Reward {@code neroquests:xp}. */
    public static final RewardType<XpReward> XP =
            RewardTypes.register(XpReward.TYPE_ID, XpReward.CODEC);

    private QuestTypes() {
    }

    /**
     * Forces this class to initialise (and therefore its types to register). Idempotent —
     * repeat calls do nothing, because the registrations happen in the static initialiser.
     */
    public static void init() {
        NeroQuestsCommon.LOGGER.debug("[NeroQuests] Registered {} objective type(s) and {} reward type(s).",
                ObjectiveTypes.ids().size(), RewardTypes.ids().size());
    }
}
