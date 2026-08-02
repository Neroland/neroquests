package za.co.neroland.neroquests.quest.objective;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;

/**
 * Objective {@code neroquests:craft_item} — <i>make</i> this many matching items.
 *
 * <pre>{@code { "type": "neroquests:craft_item", "tag": "c:ingots/iron", "count": 32 }}</pre>
 *
 * <p>Unlike {@link CollectItemObjective} this is a running tally: each crafting result adds its
 * stack size and nothing ever takes it back, so spending what you made does not undo the progress.
 *
 * <p>"Craft" means taking the output of a result slot, which vanilla treats uniformly: the
 * crafting grid, a furnace/smoker/blast-furnace output, the stonecutter, the smithing table and a
 * villager trade all count. That is deliberate — a pack asking for "20 iron ingots crafted" should
 * be satisfied by smelting them.
 */
public record CraftItemObjective(ItemTarget selector, int count) implements ObjectiveSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "craft_item");

    public static final MapCodec<CraftItemObjective> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            ItemTarget.MAP_CODEC.forGetter(CraftItemObjective::selector),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1)
                    .forGetter(CraftItemObjective::count)
    ).apply(inst, CraftItemObjective::new));

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public int target() {
        return count;
    }

    @Override
    public int creditCraft(ItemStack crafted, int amount, ObjectiveContext context) {
        return amount > 0 && selector.matches(crafted) ? amount : 0;
    }

    @Override
    public boolean contentPresent(ObjectiveContext context) {
        return selector.present();
    }

    @Override
    public String contentLabel() {
        return selector.label();
    }
}
