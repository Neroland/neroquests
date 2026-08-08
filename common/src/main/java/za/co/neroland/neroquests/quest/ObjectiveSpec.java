package za.co.neroland.neroquests.quest;

import java.util.Optional;
import java.util.OptionalInt;

import com.mojang.serialization.Codec;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.event.ThresholdEvents.ThresholdCrossing;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;
import za.co.neroland.neroquests.quest.engine.QuestEngine;

/**
 * One thing a player must do to advance a quest. Each concrete objective is a record
 * implementing this interface, registered with {@link ObjectiveTypes} under a type id and
 * serialised through the shared {@link #CODEC} dispatch:
 *
 * <pre>{@code { "type": "neroquests:quest_complete", "quest": "neroquests:chapter1/mine_ore" }}</pre>
 *
 * <p>An objective whose {@code type} is not registered decodes to an
 * {@link za.co.neroland.neroquests.quest.objective.UnknownObjective} so
 * {@link QuestDefinitions} can drop the owning quest with a precise warning instead of failing
 * the whole file.
 *
 * <h2>Evaluation contract</h2>
 *
 * <p>{@link QuestEngine} advances an objective in exactly two ways, and every objective opts into
 * whichever fits — there is no type switch anywhere in the engine:
 *
 * <ul>
 *   <li><b>Measured</b> — {@link #measure(ObjectiveContext)} recomputes the objective's absolute
 *       progress from current world state (inventory contents, current dimension, a gate's state).
 *       It must be <em>idempotent</em>: two calls with no world change return the same number.
 *       Objectives that can only ever move forward (a milestone) leave
 *       {@link #measureRegresses()} at {@code false}, so a later, smaller measurement never
 *       rewinds stored progress; a collect-style objective overrides it to {@code true}.</li>
 *   <li><b>Credited</b> — {@code creditXxx} returns how many units one discrete trigger event is
 *       worth (a craft, a kill). Credits are additive and are applied exactly once per event.</li>
 * </ul>
 *
 * <p>An objective may do both, or neither (in which case it can never complete). Every method here
 * runs on the server thread.
 */
public interface ObjectiveSpec {

    /** Dispatches on the {@code type} field to the codec registered in {@link ObjectiveTypes}. */
    Codec<ObjectiveSpec> CODEC =
            Identifier.CODEC.<ObjectiveSpec>dispatch("type", ObjectiveSpec::typeId, ObjectiveTypes::codecFor);

    /** The registered type id of this objective (its {@code type} field in JSON). */
    Identifier typeId();

    /**
     * How many units satisfy this objective. Binary objectives (be somewhere, hold a flag) leave
     * this at {@code 1}; counted objectives return their {@code count} field. The engine clamps
     * anything below 1 up to 1.
     */
    default int target() {
        return 1;
    }

    /**
     * This objective's absolute progress right now, recomputed from world state — or
     * {@linkplain OptionalInt#empty() empty} for objectives that only ever advance through
     * discrete trigger events. Must be idempotent.
     */
    default OptionalInt measure(ObjectiveContext context) {
        return OptionalInt.empty();
    }

    /**
     * Whether a fresh {@link #measure(ObjectiveContext)} may <em>lower</em> stored progress.
     * Collect-style objectives say {@code true} (spend the items and the counter falls back);
     * milestone-style objectives say {@code false}, so having once been measured at a value keeps
     * it. Ignored for objectives that do not measure.
     */
    default boolean measureRegresses() {
        return false;
    }

    /**
     * Units credited by one crafting result — {@code count} copies of {@code crafted} leaving a
     * result slot. Return {@code 0} when this objective does not care.
     */
    default int creditCraft(ItemStack crafted, int count, ObjectiveContext context) {
        return 0;
    }

    /**
     * Units credited by one kill of {@code victim} attributed to the context's player. Return
     * {@code 0} when this objective does not care.
     */
    default int creditKill(Entity victim, ObjectiveContext context) {
        return 0;
    }

    /**
     * Units credited by one Neroland Core threshold crossing. Return {@code 0} when this objective
     * does not care about that channel, direction, scope key or value.
     *
     * <p>Unlike a craft or a kill, a crossing names a <b>place or system and never a player</b>
     * (Core's {@code ThresholdCrossing} contract), so the trigger evaluates it once per online
     * player and each objective decides for itself who the news belongs to — see
     * {@link za.co.neroland.neroquests.quest.objective.CustomEventObjective.Audience}.
     */
    default int creditThreshold(ThresholdCrossing crossing, ObjectiveContext context) {
        return 0;
    }

    /**
     * Why this objective could never advance inside a quest of {@code scope} — a short,
     * operator-facing reason — or {@linkplain Optional#empty() empty} when the two are compatible.
     *
     * <p>Checked once per definition load, not per evaluation: an objective that cannot move is an
     * authoring mistake, and drops its quest with a named reason (visible in
     * {@code /neroquests reload-check}) rather than leaving a player staring at a counter that never
     * ticks. <b>Resource ids and plain words only</b> — this is logged and must never carry player
     * data.
     */
    default Optional<String> unusableInScope(QuestScope scope) {
        return Optional.empty();
    }

    /**
     * Whether the game content this objective names actually exists here — a registered item /
     * entity type, a non-empty tag, a loaded dimension. When this is {@code false} the objective
     * references content from a mod that is not installed, and the engine applies the configured
     * {@code missingModObjectivePolicy} instead of blocking the quest forever.
     */
    default boolean contentPresent(ObjectiveContext context) {
        return true;
    }

    /**
     * A short label for the one-off "content missing" warning. <b>Resource ids only</b> — this is
     * written to the log and must never carry player data.
     */
    default String contentLabel() {
        return typeId().toString();
    }
}
