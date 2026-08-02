package za.co.neroland.neroquests.quest.objective;

import java.util.OptionalInt;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;

/**
 * Objective {@code neroquests:gate_open} — a Neroland Core progression gate must be open for you.
 *
 * <pre>{@code { "type": "neroquests:gate_open", "gate": "nerolandcore:reached_orbit" }}</pre>
 *
 * <p>Binary and sticky. This is the ecosystem's universal join: every Nero mod drives Core's gates
 * as the player passes its own milestones, so a NeroQuests quest can require "has reached orbit"
 * or "has founded a colony" while depending on nothing but Core. Gate changes are picked up the
 * moment Core fires them — see {@code QuestTriggers}.
 *
 * <p>Not to be confused with a quest's {@code visible_gate}, which hides the whole quest until the
 * gate opens; this is one tick-box inside an already-visible quest.
 *
 * <p>Gates carry no missing-content degradation: an id no mod defines simply never opens, because
 * Core resolves any unknown gate as a closed player-scope gate rather than an error.
 */
public record GateOpenObjective(Identifier gate) implements ObjectiveSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "gate_open");

    public static final MapCodec<GateOpenObjective> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Identifier.CODEC.fieldOf("gate").forGetter(GateOpenObjective::gate)
    ).apply(inst, GateOpenObjective::new));

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public OptionalInt measure(ObjectiveContext context) {
        return OptionalInt.of(ProgressionGates.isOpen(context.player(), gate) ? 1 : 0);
    }

    @Override
    public String contentLabel() {
        return gate.toString();
    }
}
