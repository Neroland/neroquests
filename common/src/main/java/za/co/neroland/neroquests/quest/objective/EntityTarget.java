package za.co.neroland.neroquests.quest.objective;

import java.util.Optional;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * What an entity objective is looking for: <b>exactly one</b> of a single entity-type id or an
 * entity-type tag.
 *
 * <pre>{@code
 * { "type": "neroquests:kill_entity", "entity": "minecraft:zombie",     "count": 10 }
 * { "type": "neroquests:kill_entity", "tag":    "minecraft:skeletons",  "count": 10 }
 * }</pre>
 *
 * <p>Declaring both, or neither, is a decode error and drops the owning quest with a warning.
 */
public record EntityTarget(Optional<Identifier> entity, Optional<Identifier> tag) {

    /** Contributes the {@code entity} / {@code tag} fields straight into the owning objective's map. */
    public static final MapCodec<EntityTarget> MAP_CODEC = RecordCodecBuilder.<EntityTarget>mapCodec(inst -> inst.group(
            Identifier.CODEC.optionalFieldOf("entity").forGetter(EntityTarget::entity),
            Identifier.CODEC.optionalFieldOf("tag").forGetter(EntityTarget::tag)
    ).apply(inst, EntityTarget::new)).validate(target -> target.entity().isPresent() == target.tag().isPresent()
            ? DataResult.error(() -> "an entity objective needs exactly one of 'entity' or 'tag'")
            : DataResult.success(target));

    /** Whether {@code victim} is one of the entity types this target selects. */
    public boolean matches(Entity victim) {
        // EntityType#builtInRegistryHolder is deprecated in 26.x; go through the registry instead.
        Holder<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(victim.getType());
        if (entity.isPresent()) {
            return type.is(entity.get());
        }
        return tag.isPresent() && type.is(TagKey.create(Registries.ENTITY_TYPE, tag.get()));
    }

    /**
     * Whether the selected content exists here. As with items, the entity-type registry is
     * <em>defaulted</em>, so existence is asked with {@code containsKey}.
     */
    public boolean present() {
        if (entity.isPresent()) {
            return BuiltInRegistries.ENTITY_TYPE.containsKey(entity.get());
        }
        return tag.isPresent()
                && BuiltInRegistries.ENTITY_TYPE
                        .getTagOrEmpty(TagKey.create(Registries.ENTITY_TYPE, tag.get()))
                        .iterator().hasNext();
    }

    /** A log-safe label (a resource id, never player data). */
    public String label() {
        return entity.map(Identifier::toString)
                .orElseGet(() -> tag.map(id -> "#" + id).orElse("<no entity or tag>"));
    }
}
