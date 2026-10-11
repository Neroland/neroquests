package za.co.neroland.neroquests.quest.objective;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;

/**
 * Objective {@code neroquests:collect_item} — <i>have</i> this many matching items in your
 * inventory at once.
 *
 * <pre>{@code { "type": "neroquests:collect_item", "item": "minecraft:iron_ingot", "count": 10 }}</pre>
 *
 * <p>Progress is a <b>recount, not a tally</b>: every evaluation counts the matching items the
 * player is carrying right now (main inventory, hotbar, off-hand and worn equipment) and stores
 * that. Handing the items away lowers it again — which is exactly what makes the objective
 * exploit-proof, since there is no way to farm progress by cycling the same stack.
 *
 * <p>Because the recount is idempotent, how often it runs only affects latency. The engine runs it
 * on the cheap trigger points (a craft, a kill) and on a low-frequency server sweep.
 */
public record CollectItemObjective(ItemTarget selector, int count, boolean consume) implements ObjectiveSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "collect_item");

    public static final MapCodec<CollectItemObjective> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            ItemTarget.MAP_CODEC.forGetter(CollectItemObjective::selector),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1)
                    .forGetter(CollectItemObjective::count),
            Codec.BOOL.optionalFieldOf("consume", Boolean.FALSE).forGetter(CollectItemObjective::boxedConsume)
    ).apply(inst, CollectItemObjective::of));

    /** Boxed-parameter factory for the codec (avoids the ECJ unboxing null-safety warning). */
    private static CollectItemObjective of(ItemTarget selector, Integer count, Boolean consume) {
        return new CollectItemObjective(selector, count.intValue(), consume.booleanValue());
    }

    private Boolean boxedConsume() {
        return Boolean.valueOf(consume);
    }

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public int target() {
        return count;
    }

    @Override
    public OptionalInt measure(ObjectiveContext context) {
        Inventory inventory = context.player().getInventory();
        int held = 0;
        int size = inventory.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (selector.matches(stack)) {
                held += stack.getCount();
                if (held >= count) {
                    return OptionalInt.of(count); // enough is enough; stop scanning
                }
            }
        }
        return OptionalInt.of(held);
    }

    /** Spending the items takes the progress back down again. */
    @Override
    public boolean measureRegresses() {
        return true;
    }

    /**
     * With {@code "consume": true} the items are handed in: the quest takes {@code count} matching
     * items from the completing player when it completes. Showing the same stack on every account
     * then completes it only once, which is the safer shape for anything that pays out.
     */
    @Override
    public boolean takesOnComplete() {
        return consume;
    }

    @Override
    public boolean canTakeOnComplete(ObjectiveContext context) {
        OptionalInt held = measure(context);
        return held.isPresent() && held.getAsInt() >= count;
    }

    @Override
    public void takeOnComplete(ObjectiveContext context) {
        Inventory inventory = context.player().getInventory();
        int remaining = count;
        int size = inventory.getContainerSize();
        for (int slot = 0; slot < size && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (selector.matches(stack)) {
                int taken = Math.min(remaining, stack.getCount());
                stack.shrink(taken);
                remaining -= taken;
            }
        }
        inventory.setChanged();
    }

    @Override
    public boolean contentPresent(ObjectiveContext context) {
        return selector.present();
    }

    @Override
    public Optional<String> unknownTarget(MinecraftServer server, Set<Identifier> definedQuests) {
        return selector.typo();
    }

    @Override
    public String contentLabel() {
        return selector.label();
    }
}
