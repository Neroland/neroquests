package za.co.neroland.neroquests.quest.reward;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestStates;
import za.co.neroland.neroquests.quest.QuestText;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.engine.QuestCompletion;
import za.co.neroland.neroquests.quest.engine.QuestEngine;
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
 * <p><b>Recipients who cannot take a reward now</b> (AUD-05). Currency and reputation are UUID-keyed
 * and land regardless (a refused payout is kept as owed). Items and experience for a recipient who is
 * offline, dead or disconnecting — and items that do not fit — are kept in the bounded owed queue
 * ({@link OwedDelivery}) and delivered on join, respawn, the periodic retry or a NeroLink
 * {@code claim_reward}. A player-scope gate for an offline recipient is written through Core's UUID
 * API. Only the chat line is lost for an offline recipient.
 *
 * <p><b>Server-scope recipients (AUD-06).</b> A {@code scope: server} quest completes once for the
 * world. Its player-targeted rewards go to the triggering player and to <em>every online player who
 * has the quest available</em> at that moment (visible gate open, prerequisites met), each recipient
 * entered in their own paid ledger under the quest id so nobody is paid twice for one quest; anything
 * a recipient cannot take now goes to the owed queue. World-level rewards (a server gate) are granted
 * once. There is no "first player in the list" any more.
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
        if (completion.quest().scope() == QuestScope.SERVER) {
            onServerCompletion(completion);
            return;
        }
        RewardContext context = RewardContext.of(completion);
        announce(context);
        if (completion.paysRewards()) {
            grantAll(context);
            return;
        }
        // AUD-01/AUD-02: completed, but nothing is paid. Tell the player why; log nothing personal.
        if (context.player() != null && !context.quest().rewards().isEmpty()) {
            context.player().sendSystemMessage(Component.translatable(
                    completion.rewards() == QuestCompletion.RewardDecision.WITHHELD_DEGRADED
                            ? "message.neroquests.rewards_withheld_degraded"
                            : "message.neroquests.rewards_already_paid"));
        }
    }

    /** AUD-06: a {@code scope: server} quest completed — reward every recipient once, the world once. */
    private static void onServerCompletion(QuestCompletion completion) {
        MinecraftServer server = completion.server();
        Quest quest = completion.quest();
        RewardContext trigger = RewardContext.of(completion);
        List<UUID> recipients = recipients(server, quest, completion.player());
        for (UUID recipient : recipients) {
            announce(RewardContext.forRecipient(server, recipient, quest));
        }
        if (!completion.paysRewards()) {
            if (trigger.player() != null && !quest.rewards().isEmpty()) {
                trigger.player().sendSystemMessage(Component.translatable(
                        completion.rewards() == QuestCompletion.RewardDecision.WITHHELD_DEGRADED
                                ? "message.neroquests.rewards_withheld_degraded"
                                : "message.neroquests.rewards_already_paid"));
            }
            return;
        }
        QuestProgressState state = QuestProgressState.get(server);
        List<RewardContext> paidTo = new ArrayList<>(recipients.size());
        for (UUID recipient : recipients) {
            RewardContext context = RewardContext.forRecipient(server, recipient, quest);
            if (state.markPaid(recipient, quest.id())) {
                paidTo.add(context);
            } else if (context.player() != null && !quest.rewards().isEmpty()) {
                context.player().sendSystemMessage(Component.translatable("message.neroquests.rewards_already_paid"));
            }
        }
        List<RewardSpec> rewards = quest.rewards();
        for (int index = 0; index < rewards.size(); index++) {
            RewardSpec reward = rewards.get(index);
            if (reward.worldLevel(trigger)) {
                grantOne(reward, trigger.withRewardIndex(index));
                continue;
            }
            for (RewardContext context : paidTo) {
                grantOne(reward, context.withRewardIndex(index));
            }
        }
    }

    /**
     * The recipients of a server-scope quest's player-targeted rewards: the triggering player first,
     * then every online player (not suppressed after an erasure) for whom the quest is available now.
     */
    private static List<UUID> recipients(MinecraftServer server, Quest quest, UUID triggering) {
        Set<UUID> out = new LinkedHashSet<>();
        if (triggering != null) {
            out.add(triggering);
        }
        QuestProgressState state = QuestProgressState.get(server);
        Set<Identifier> worldCompleted = state.completedServerQuests();
        Map<Identifier, Quest> definitions = QuestDefinitions.questsForServer(server);
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            UUID id = player.getUUID();
            if (out.contains(id) || state.isSuppressed(id)) {
                continue;
            }
            // AUD-18: the same availability rule as the engine (prerequisites by their own scope).
            if (QuestStates.isAvailable(quest, definitions, state.completedQuests(id), worldCompleted,
                    QuestEngine.openGatesFor(player))) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    /** Grant one reward, isolated: a failure is logged by quest and type id only. */
    private static void grantOne(RewardSpec reward, RewardContext context) {
        try {
            reward.grant(context);
        } catch (RuntimeException e) {
            // Resource ids only — never who completed it (POPIA/GDPR).
            NeroQuestsCommon.LOGGER.warn(
                    "[NeroQuests] Reward '{}' of quest {} failed to grant; the remaining rewards "
                            + "were still applied.", reward.typeId(), context.quest().id(), e);
        }
    }

    /** Tell the player, if they are here to be told. */
    private static void announce(RewardContext context) {
        ServerPlayer player = context.player();
        if (player == null) {
            return;
        }
        // Spine/pack titles are translation keys; a custom quest's title is author text, rendered literally
        // and never used as a key (D3, QuestText).
        player.sendSystemMessage(Component.translatable(COMPLETE_MESSAGE, QuestText.title(context.quest())));
    }

    /** Grant every reward of the completed quest, in definition order, each one isolated. */
    private static void grantAll(RewardContext context) {
        Quest quest = context.quest();
        List<RewardSpec> rewards = quest.rewards();
        for (int index = 0; index < rewards.size(); index++) {
            RewardSpec reward = rewards.get(index);
            try {
                reward.grant(context.withRewardIndex(index));
            } catch (RuntimeException e) {
                // Resource ids only — never who completed it (POPIA/GDPR).
                NeroQuestsCommon.LOGGER.warn(
                        "[NeroQuests] Reward '{}' of quest {} failed to grant; the remaining rewards "
                                + "were still applied.", reward.typeId(), quest.id(), e);
            }
        }
    }
}
