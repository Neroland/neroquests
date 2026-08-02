package za.co.neroland.neroquests.quest;

import com.mojang.serialization.Codec;

import net.minecraft.resources.Identifier;

/**
 * One thing a player must do to advance a quest. Each concrete objective is a record
 * implementing this interface, registered with {@link ObjectiveTypes} under a type id and
 * serialised through the shared {@link #CODEC} dispatch:
 *
 * <pre>{@code { "type": "neroquests:quest_complete", "quest": "neroquests:chapter1/mine_ore" }}</pre>
 *
 * <p>The engine that evaluates objectives lands in a later stage; this stage only defines
 * the data model and its datapack representation. An objective whose {@code type} is not
 * registered decodes to an {@link za.co.neroland.neroquests.quest.objective.UnknownObjective}
 * so {@link QuestDefinitions} can drop the owning quest with a precise warning instead of
 * failing the whole file.
 */
public interface ObjectiveSpec {

    /** Dispatches on the {@code type} field to the codec registered in {@link ObjectiveTypes}. */
    Codec<ObjectiveSpec> CODEC =
            Identifier.CODEC.<ObjectiveSpec>dispatch("type", ObjectiveSpec::typeId, ObjectiveTypes::codecFor);

    /** The registered type id of this objective (its {@code type} field in JSON). */
    Identifier typeId();
}
