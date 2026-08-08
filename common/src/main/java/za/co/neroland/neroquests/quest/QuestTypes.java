package za.co.neroland.neroquests.quest;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.objective.CollectItemObjective;
import za.co.neroland.neroquests.quest.objective.CraftItemObjective;
import za.co.neroland.neroquests.quest.objective.CustomEventObjective;
import za.co.neroland.neroquests.quest.objective.GateOpenObjective;
import za.co.neroland.neroquests.quest.objective.KillEntityObjective;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;
import za.co.neroland.neroquests.quest.objective.ReachDimensionObjective;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.GateReward;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.ReputationReward;
import za.co.neroland.neroquests.quest.reward.XpReward;

/**
 * Registers NeroQuests' built-in objective and reward types. Called once from
 * {@link NeroQuestsCommon#init()} — it must run before {@link QuestDefinitions} reads any
 * datapack file, or every objective/reward would decode as "unknown type" and drop its quest.
 *
 * <p>The objective set is complete for the engine: gather ({@code collect_item}), make
 * ({@code craft_item}), fight ({@code kill_entity}), travel ({@code reach_dimension}), personal
 * ecosystem progression ({@code gate_open}), world-state ecosystem events ({@code custom_event})
 * and quest chaining ({@code quest_complete}). The reward set is complete too: loot
 * ({@code item}), experience ({@code xp}), ecosystem progression ({@code gate}) and the two economy
 * payouts ({@code currency}, {@code reputation}) that degrade to a no-op until a sibling mod
 * implements Core's contract.
 */
public final class QuestTypes {

    /** Objective {@code neroquests:quest_complete}. */
    public static final ObjectiveType<QuestCompleteObjective> QUEST_COMPLETE =
            ObjectiveTypes.register(QuestCompleteObjective.TYPE_ID, QuestCompleteObjective.CODEC);

    /** Objective {@code neroquests:collect_item}. */
    public static final ObjectiveType<CollectItemObjective> COLLECT_ITEM =
            ObjectiveTypes.register(CollectItemObjective.TYPE_ID, CollectItemObjective.CODEC);

    /** Objective {@code neroquests:craft_item}. */
    public static final ObjectiveType<CraftItemObjective> CRAFT_ITEM =
            ObjectiveTypes.register(CraftItemObjective.TYPE_ID, CraftItemObjective.CODEC);

    /** Objective {@code neroquests:kill_entity}. */
    public static final ObjectiveType<KillEntityObjective> KILL_ENTITY =
            ObjectiveTypes.register(KillEntityObjective.TYPE_ID, KillEntityObjective.CODEC);

    /** Objective {@code neroquests:reach_dimension}. */
    public static final ObjectiveType<ReachDimensionObjective> REACH_DIMENSION =
            ObjectiveTypes.register(ReachDimensionObjective.TYPE_ID, ReachDimensionObjective.CODEC);

    /** Objective {@code neroquests:gate_open}. */
    public static final ObjectiveType<GateOpenObjective> GATE_OPEN =
            ObjectiveTypes.register(GateOpenObjective.TYPE_ID, GateOpenObjective.CODEC);

    /** Objective {@code neroquests:custom_event}. */
    public static final ObjectiveType<CustomEventObjective> CUSTOM_EVENT =
            ObjectiveTypes.register(CustomEventObjective.TYPE_ID, CustomEventObjective.CODEC);

    /** Reward {@code neroquests:xp}. */
    public static final RewardType<XpReward> XP =
            RewardTypes.register(XpReward.TYPE_ID, XpReward.CODEC);

    /** Reward {@code neroquests:item}. */
    public static final RewardType<ItemReward> ITEM =
            RewardTypes.register(ItemReward.TYPE_ID, ItemReward.CODEC);

    /** Reward {@code neroquests:gate}. */
    public static final RewardType<GateReward> GATE =
            RewardTypes.register(GateReward.TYPE_ID, GateReward.CODEC);

    /** Reward {@code neroquests:currency}. */
    public static final RewardType<CurrencyReward> CURRENCY =
            RewardTypes.register(CurrencyReward.TYPE_ID, CurrencyReward.CODEC);

    /** Reward {@code neroquests:reputation}. */
    public static final RewardType<ReputationReward> REPUTATION =
            RewardTypes.register(ReputationReward.TYPE_ID, ReputationReward.CODEC);

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
