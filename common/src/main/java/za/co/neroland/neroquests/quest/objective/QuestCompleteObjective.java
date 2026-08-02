package za.co.neroland.neroquests.quest.objective;

import java.util.OptionalInt;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;

/**
 * Objective {@code neroquests:quest_complete} — satisfied once the named quest is complete
 * for the same scope holder (the player, or the server for a server-scoped quest).
 *
 * <pre>{@code { "type": "neroquests:quest_complete", "quest": "neroquests:chapter1/first_steps" }}</pre>
 *
 * <p>Note this is <em>not</em> the same thing as a prerequisite: a prerequisite hides/locks a
 * quest, whereas this objective is one of the boxes to tick inside an already-available quest.
 *
 * <p>Because quest completion is monotonic, the engine can safely re-run its passes after any
 * completion until nothing more completes — that cascade is what lets one quest finishing tick
 * this box in another and finish that one too, without ever looping.
 */
public record QuestCompleteObjective(Identifier quest) implements ObjectiveSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "quest_complete");

    public static final MapCodec<QuestCompleteObjective> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Identifier.CODEC.fieldOf("quest").forGetter(QuestCompleteObjective::quest)
    ).apply(inst, QuestCompleteObjective::new));

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    /** The context's completed set is already scope-resolved, so this needs no scope logic. */
    @Override
    public OptionalInt measure(ObjectiveContext context) {
        return OptionalInt.of(context.completedQuests().contains(quest) ? 1 : 0);
    }

    /** A quest that no longer exists (a removed add-on, a renamed pack entry) degrades. */
    @Override
    public boolean contentPresent(ObjectiveContext context) {
        return QuestDefinitions.quest(quest).isPresent();
    }

    @Override
    public String contentLabel() {
        return quest.toString();
    }
}
