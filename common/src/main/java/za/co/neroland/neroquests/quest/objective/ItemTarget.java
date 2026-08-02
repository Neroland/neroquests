package za.co.neroland.neroquests.quest.objective;

import java.util.Optional;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

/**
 * What an item objective is looking for: <b>exactly one</b> of a single item id or an item tag.
 * Shared by {@link CollectItemObjective} and {@link CraftItemObjective}, whose JSON therefore reads
 * the same way:
 *
 * <pre>{@code
 * { "type": "neroquests:collect_item", "item": "minecraft:iron_ingot", "count": 10 }
 * { "type": "neroquests:collect_item", "tag":  "c:ingots/iron",        "count": 10 }
 * }</pre>
 *
 * <p>Declaring both, or neither, is a decode error and drops the owning quest with a warning.
 *
 * <p>{@link #present()} answers the cross-mod degradation question — an unregistered item id or a
 * tag that resolves to nothing means the objective names content from a mod that is not installed.
 */
public record ItemTarget(Optional<Identifier> item, Optional<Identifier> tag) {

    /**
     * Contributes the {@code item} / {@code tag} fields straight into the owning objective's map,
     * so an objective composes it with {@code ItemTarget.MAP_CODEC.forGetter(...)}.
     */
    public static final MapCodec<ItemTarget> MAP_CODEC = RecordCodecBuilder.<ItemTarget>mapCodec(inst -> inst.group(
            Identifier.CODEC.optionalFieldOf("item").forGetter(ItemTarget::item),
            Identifier.CODEC.optionalFieldOf("tag").forGetter(ItemTarget::tag)
    ).apply(inst, ItemTarget::new)).validate(target -> target.item().isPresent() == target.tag().isPresent()
            ? DataResult.error(() -> "an item objective needs exactly one of 'item' or 'tag'")
            : DataResult.success(target));

    /** Whether {@code stack} is one of the items this target selects. Empty stacks never match. */
    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (item.isPresent()) {
            return stack.typeHolder().is(item.get());
        }
        return tag.isPresent() && stack.typeHolder().is(TagKey.create(Registries.ITEM, tag.get()));
    }

    /**
     * Whether the selected content exists in the running game: the item id is registered, or the
     * tag resolves to at least one item. Note {@code BuiltInRegistries.ITEM} is a
     * <em>defaulted</em> registry (an unknown id silently yields {@code air}), so this must ask
     * {@code containsKey} rather than looking the value up.
     */
    public boolean present() {
        if (item.isPresent()) {
            return BuiltInRegistries.ITEM.containsKey(item.get());
        }
        return tag.isPresent()
                && BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, tag.get()))
                        .iterator().hasNext();
    }

    /** A log-safe label (a resource id, never player data). */
    public String label() {
        return item.map(Identifier::toString)
                .orElseGet(() -> tag.map(id -> "#" + id).orElse("<no item or tag>"));
    }
}
