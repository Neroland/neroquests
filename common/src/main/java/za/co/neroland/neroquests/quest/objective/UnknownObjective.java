package za.co.neroland.neroquests.quest.objective;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.ObjectiveSpec;

/**
 * Placeholder produced when a datapack names an objective {@code type} that is not
 * registered (a typo, or a type from a NeroQuests version/add-on that is not installed).
 *
 * <p>Decoding never fails on it — {@code QuestDefinitions} instead drops the owning quest
 * and logs the offending resource id plus this {@link #typeId()}, so one bad objective
 * cannot take down a whole quest file's sibling entries or crash the server.
 */
public record UnknownObjective(Identifier typeId) implements ObjectiveSpec {
}
