package za.co.neroland.neroquests.quest;

import com.mojang.serialization.Codec;

import net.minecraft.resources.Identifier;

/**
 * One thing a player receives when a quest completes. Each concrete reward is a record
 * implementing this interface, registered with {@link RewardTypes} under a type id and
 * serialised through the shared {@link #CODEC} dispatch:
 *
 * <pre>{@code { "type": "neroquests:xp", "amount": 50 }}</pre>
 *
 * <p>The pipeline that grants rewards lands in a later stage; this stage only defines the
 * data model and its datapack representation. A reward whose {@code type} is not registered
 * decodes to an {@link za.co.neroland.neroquests.quest.reward.UnknownReward} so
 * {@link QuestDefinitions} can drop the owning quest with a precise warning instead of
 * failing the whole file.
 */
public interface RewardSpec {

    /** Dispatches on the {@code type} field to the codec registered in {@link RewardTypes}. */
    Codec<RewardSpec> CODEC =
            Identifier.CODEC.<RewardSpec>dispatch("type", RewardSpec::typeId, RewardTypes::codecFor);

    /** The registered type id of this reward (its {@code type} field in JSON). */
    Identifier typeId();
}
