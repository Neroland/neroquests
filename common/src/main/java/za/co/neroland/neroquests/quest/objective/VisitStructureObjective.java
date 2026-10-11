package za.co.neroland.neroquests.quest.objective;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;

/**
 * Objective {@code neroquests:visit_structure} (C5) — stand inside a generated structure.
 *
 * <pre>{@code { "type": "neroquests:visit_structure", "structure": "minecraft:village_plains" }
 * { "type": "neroquests:visit_structure", "tag": "minecraft:village" }}</pre>
 *
 * <p>Exactly one of {@code structure} or {@code tag}. Measured on the 1 Hz sweep for players who have
 * the quest in progress: the player's position is checked against the structure pieces referenced by
 * the chunk they stand in ({@code StructureManager.getStructureWithPieceAt}) — a lookup of data the
 * loaded chunk already holds, no searching. A milestone: once visited it stays done. No position is
 * stored or sent anywhere.
 */
public record VisitStructureObjective(Optional<Identifier> structure, Optional<Identifier> tag)
        implements ObjectiveSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "visit_structure");

    public static final MapCodec<VisitStructureObjective> CODEC = RecordCodecBuilder.<VisitStructureObjective>mapCodec(
            inst -> inst.group(
                    Identifier.CODEC.optionalFieldOf("structure").forGetter(VisitStructureObjective::structure),
                    Identifier.CODEC.optionalFieldOf("tag").forGetter(VisitStructureObjective::tag)
            ).apply(inst, VisitStructureObjective::new))
            .validate(objective -> objective.structure().isPresent() == objective.tag().isPresent()
                    ? DataResult.error(() -> "a visit_structure objective takes exactly one of 'structure' or 'tag'")
                    : DataResult.success(objective));

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public OptionalInt measure(ObjectiveContext context) {
        ServerLevel level = context.server().getLevel(context.player().level().dimension());
        if (level == null) {
            return OptionalInt.of(0);
        }
        boolean inside;
        if (structure.isPresent()) {
            // The holder-predicate overload exists on every supported version (26.3 dropped the
            // Structure one).
            Identifier wanted = structure.get();
            inside = level.structureManager().getStructureWithPieceAt(context.player().blockPosition(),
                    holder -> holder.is(wanted)).isValid();
        } else {
            inside = level.structureManager().getStructureWithPieceAt(context.player().blockPosition(),
                    TagKey.create(Registries.STRUCTURE, tag.get())).isValid();
        }
        return OptionalInt.of(inside ? 1 : 0);
    }

    @Override
    public boolean contentPresent(ObjectiveContext context) {
        Registry<Structure> registry = structures(context.server());
        if (structure.isPresent()) {
            return registry.containsKey(structure.get());
        }
        return registry.get(TagKey.create(Registries.STRUCTURE, tag.get()))
                .map(set -> set.size() > 0).orElse(Boolean.FALSE).booleanValue();
    }

    /** A structure id (or a closed-namespace tag) that does not exist although its owner is here is a typo. */
    @Override
    public Optional<String> unknownTarget(MinecraftServer server, Set<Identifier> definedQuests) {
        Registry<Structure> registry = structures(server);
        if (structure.isPresent()) {
            if (!registry.containsKey(structure.get()) && ContentIds.ownedByLoadedMod(structure.get().getNamespace())) {
                return Optional.of("unknown structure " + structure.get() + " (its namespace's mod is loaded)");
            }
            return Optional.empty();
        }
        Identifier id = tag.get();
        boolean empty = registry.get(TagKey.create(Registries.STRUCTURE, id))
                .map(set -> set.size() == 0).orElse(Boolean.TRUE).booleanValue();
        if (empty && ContentIds.closedNamespace(id.getNamespace())) {
            return Optional.of("structure tag " + id + " is empty or unknown");
        }
        return Optional.empty();
    }

    private static Registry<Structure> structures(MinecraftServer server) {
        return server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
    }

    @Override
    public String contentLabel() {
        return structure.map(Identifier::toString).orElseGet(() -> "#" + tag.get());
    }
}
