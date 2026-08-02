package za.co.neroland.neroquests.quest.objective;

import java.util.OptionalInt;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;

/**
 * Objective {@code neroquests:reach_dimension} — set foot in a dimension.
 *
 * <pre>{@code { "type": "neroquests:reach_dimension", "dimension": "nerospace:greenxertz" }}</pre>
 *
 * <p>Binary and sticky: it flips to done the first time the player is measured inside that
 * dimension and stays done after they leave (it does not
 * {@linkplain ObjectiveSpec#measureRegresses() regress}).
 *
 * <p>This is the loader-free way for a quest to react to another mod's worlds — Nerospace's
 * planets, a modded Nether analogue — without NeroQuests depending on that mod at all. If the
 * dimension is not loaded (its mod is absent), the objective degrades under
 * {@code missingModObjectivePolicy} instead of blocking the quest forever.
 */
public record ReachDimensionObjective(Identifier dimension) implements ObjectiveSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "reach_dimension");

    public static final MapCodec<ReachDimensionObjective> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Identifier.CODEC.fieldOf("dimension").forGetter(ReachDimensionObjective::dimension)
    ).apply(inst, ReachDimensionObjective::new));

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public OptionalInt measure(ObjectiveContext context) {
        boolean here = context.player().level().dimension().identifier().equals(dimension);
        return OptionalInt.of(here ? 1 : 0);
    }

    @Override
    public boolean contentPresent(ObjectiveContext context) {
        return context.server().getLevel(ResourceKey.create(Registries.DIMENSION, dimension)) != null;
    }

    @Override
    public String contentLabel() {
        return dimension.toString();
    }
}
