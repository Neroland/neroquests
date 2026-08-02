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

    /**
     * Grants nothing. Unreachable in practice — a quest carrying one of these is dropped at load —
     * but a placeholder for content that is not installed must pay out nothing if it ever is reached.
     */
    @Override
    public void grant(RewardContext context) {
        // Intentionally empty.
    }
}
