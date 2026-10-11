package za.co.neroland.neroquests.quest.objective;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestStates;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;

/**
 * Objective {@code neroquests:quest_complete} — satisfied once the named quest is complete in the
 * scope that owns <em>it</em>: a {@code scope: player} target in the evaluating player's own completed
 * set, a {@code scope: server} target in the world's — the same rule as a prerequisite
 * ({@link QuestStates#prerequisiteMet}), independent of the scope of the quest that asks.
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

    /** Resolved by the target's own scope, exactly like a prerequisite (R3 follow-up to AUD-18). */
    @Override
    public OptionalInt measure(ObjectiveContext context) {
        boolean done = QuestStates.prerequisiteMet(quest, context.definitions(),
                context.playerCompleted(), context.serverCompleted());
        return OptionalInt.of(done ? 1 : 0);
    }

    /** A quest that no longer exists (a removed add-on, a renamed pack entry) degrades. */
    @Override
    public boolean contentPresent(ObjectiveContext context) {
        return QuestDefinitions.quest(quest).isPresent();
    }

    /**
     * A quest id no definition file exists for, in a namespace whose mod is loaded (or
     * {@code neroquests}), is a typo (AUD-02): left as "missing content" it would make the chained
     * quest free.
     */
    @Override
    public Optional<String> unknownTarget(MinecraftServer server, Set<Identifier> definedQuests) {
        if (!definedQuests.contains(quest) && ContentIds.ownedByLoadedMod(quest.getNamespace())) {
            return Optional.of("quest_complete names unknown quest " + quest);
        }
        return Optional.empty();
    }

    @Override
    public String contentLabel() {
        return quest.toString();
    }
}
