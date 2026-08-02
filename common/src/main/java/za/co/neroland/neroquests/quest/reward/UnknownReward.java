package za.co.neroland.neroquests.quest.reward;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * Placeholder produced when a datapack names a reward {@code type} that is not registered
 * (a typo, or a type from a NeroQuests version/add-on that is not installed).
 *
 * <p>Decoding never fails on it — {@code QuestDefinitions} instead drops the owning quest
 * and logs the offending resource id plus this {@link #typeId()}, so a bad reward can never
 * hand out something unintended or crash the server.
 */
public record UnknownReward(Identifier typeId) implements RewardSpec {
}
