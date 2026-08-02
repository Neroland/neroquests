package za.co.neroland.neroquests.quest.reward;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.engine.QuestCompletion;
import za.co.neroland.neroquests.quest.engine.QuestCompletionListeners;

/**
 * The payout half of the engine: subscribes to {@link QuestCompletionListeners} and, for every quest
 * that completes, grants its rewards in the order the quest file lists them and tells the player.
 *
 * <p>{@link za.co.neroland.neroquests.quest.engine.QuestEngine} deliberately knows nothing about
 * this — it publishes a completion and moves on, which is what lets a later network sync or
 * completion toast hang off the same seam without touching completion logic.
 *
 * <p><b>Isolation.</b> Each reward is granted inside its own try/catch. The completion has already
 * been written to the progress store by the time we run, so a reward that blows up must not take the
 * rest of the payout — or the completion — with it. Failures are logged by quest id and reward type
 * id only.
 *
 * <p><b>Offline recipients.</b> Currency and reputation are UUID-keyed and land regardless; items,
 * experience, the chat line and player-scope gate writes need a live player and are skipped with a
 * debug line when there is none. That is only reachable for a {@code scope: server} quest whose
 * triggering player logged off between the trigger and the payout.
 *
 * <p>TODO (link module): there is deliberately no deferred-reward queue in this stage. When the
 * NeroLink {@code claim_reward} action lands, unclaimed player-bound rewards should be parked and
 * handed over on next login instead of being dropped.
 *
 * <p>Server thread only.
 */
public final class QuestRewards {

    /** Chat line sent to the completing player. Kept plain — toasts and book UI arrive later. */
    private static final String COMPLETE_MESSAGE = "message.neroquests.quest_complete";

    private QuestRewards() {
    }

    /**
     * Subscribe reward execution to the completion channel. Called once from
     * {@link NeroQuestsCommon#init()}, after the trigger wiring.
     */
    public static void init() {
        QuestCompletionListeners.onCompletion(QuestRewards::onCompletion);
    }

    private static void onCompletion(QuestCompletion completion) {
        RewardContext context = RewardContext.of(completion);
        announce(context);
        grantAll(context);
    }

    /** Tell the player, if they are here to be told. */
    private static void announce(RewardContext context) {
        ServerPlayer player = context.player();
        if (player == null) {
            return;
        }
        // The quest's title is a translation key; Component.translatable renders it if a lang file
        // supplies it and falls back to the literal string otherwise.
        player.sendSystemMessage(Component.translatable(COMPLETE_MESSAGE,
                Component.translatable(context.quest().title())));
    }

    /** Grant every reward of the completed quest, in definition order, each one isolated. */
    private static void grantAll(RewardContext context) {
        Quest quest = context.quest();
        for (RewardSpec reward : quest.rewards()) {
            try {
                reward.grant(context);
            } catch (RuntimeException e) {
                // Resource ids only — never who completed it (POPIA/GDPR).
                NeroQuestsCommon.LOGGER.warn(
                        "[NeroQuests] Reward '{}' of quest {} failed to grant; the remaining rewards "
                                + "were still applied.", reward.typeId(), quest.id(), e);
            }
        }
    }
}
