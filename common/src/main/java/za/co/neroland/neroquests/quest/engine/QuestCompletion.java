package za.co.neroland.neroquests.quest.engine;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;

/**
 * One quest just finished. Handed to every {@link QuestCompletionListeners} subscriber the moment
 * {@link QuestEngine} records the completion, on the server thread.
 *
 * <p>For a {@code scope: server} quest the completion belongs to the world; {@code player} is then
 * simply whoever's action tipped it over, and the event fires exactly once for everyone.
 *
 * <p>POPIA/GDPR: the {@code player} field is the player's existing Minecraft game UUID, the same
 * identifier the progress store already uses. Subscribers must not log it.
 *
 * <p>A completion is not always a payout (AUD-01/AUD-02): {@link #rewards()} says whether this one
 * pays, and if not, why. Listeners other than reward execution (the NeroLink event, for one) still
 * see every completion.
 *
 * @param server  the running server
 * @param player  the completing player, or the triggering player for {@link QuestScope#SERVER}
 * @param quest   the quest that completed
 * @param rewards whether the quest's rewards are paid for this completion
 */
public record QuestCompletion(MinecraftServer server, UUID player, Quest quest, RewardDecision rewards) {

    /** A completion that pays its rewards. */
    public QuestCompletion(MinecraftServer server, UUID player, Quest quest) {
        this(server, player, quest, RewardDecision.PAY);
    }

    /** Whether the quest's rewards are paid for this completion. */
    public boolean paysRewards() {
        return rewards == RewardDecision.PAY;
    }

    /** Whether a completion pays, and why not when it does not. */
    public enum RewardDecision {

        /** Pay every reward (the quest was not yet in the paid ledger). */
        PAY,

        /** Already in the paid ledger: re-completed after a revoke without {@code --repay}. */
        ALREADY_PAID,

        /**
         * An objective was auto-satisfied by {@code missingModObjectivePolicy=autocomplete} and
         * {@code degradedCompletionsPay} is off (AUD-02).
         */
        WITHHELD_DEGRADED
    }
}
