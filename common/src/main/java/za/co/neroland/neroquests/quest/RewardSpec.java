package za.co.neroland.neroquests.quest;

import com.mojang.serialization.Codec;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.reward.RewardContext;

/**
 * One thing a player receives when a quest completes. Each concrete reward is a record
 * implementing this interface, registered with {@link RewardTypes} under a type id and
 * serialised through the shared {@link #CODEC} dispatch:
 *
 * <pre>{@code { "type": "neroquests:xp", "amount": 50 }}</pre>
 *
 * <p>A reward whose {@code type} is not registered decodes to an
 * {@link za.co.neroland.neroquests.quest.reward.UnknownReward} so {@link QuestDefinitions} can drop
 * the owning quest with a precise warning instead of failing the whole file.
 *
 * <p>Payout is {@link #grant(RewardContext)}, called by
 * {@link za.co.neroland.neroquests.quest.reward.QuestRewards} on the server thread once per
 * completion, in the order the rewards appear in the quest file. Each call is wrapped in its own
 * try/catch, so one reward that fails never stops the rest — but an implementation is still expected
 * to <b>degrade rather than throw</b> when the thing it needs is absent (a missing item id, an
 * economy provider no mod has registered, an offline recipient): log once through
 * {@link za.co.neroland.neroquests.quest.reward.RewardLog} and return.
 */
public interface RewardSpec {

    /** Dispatches on the {@code type} field to the codec registered in {@link RewardTypes}. */
    Codec<RewardSpec> CODEC =
            Identifier.CODEC.<RewardSpec>dispatch("type", RewardSpec::typeId, RewardTypes::codecFor);

    /** The registered type id of this reward (its {@code type} field in JSON). */
    Identifier typeId();

    /**
     * Pay this reward out. Server thread only; never called for a quest that was already complete.
     *
     * <p>Implementations must not log the recipient's identity (POPIA/GDPR) — resource ids and
     * amounts only.
     */
    void grant(RewardContext context);
}
