package za.co.neroland.neroquests.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * Reward {@code neroquests:item} — hands over a stack of items on completion.
 *
 * <pre>{@code { "type": "neroquests:item", "item": "minecraft:iron_ingot", "count": 8 }}</pre>
 *
 * <p>The items go into the player's inventory; anything that does not fit is dropped at their feet,
 * so a full inventory never silently swallows a reward. A {@code count} larger than the item's
 * stack size is split into whole stacks.
 *
 * <p><b>Missing content.</b> An {@code item} id that is not registered here (a modpack quest shipped
 * to a smaller instance) is <em>not</em> a load error — the quest still completes and every other
 * reward still pays out; this one logs once by resource id and grants nothing. That mirrors the
 * objective side's missing-content handling: a quest must never become uncompletable, and a reward
 * must never crash a server.
 *
 * <p>Items need a body to go into, so the reward is skipped (debug line) when the recipient is
 * offline — on a {@code scope: server} quest, that means the triggering player logged off between
 * the trigger and the payout.
 */
public record ItemReward(Identifier item, int count) implements RewardSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "item");

    /** Upper bound on {@code count} — 100 full stacks, far past any sane reward and loop-safe. */
    public static final int MAX_COUNT = 6400;

    public static final MapCodec<ItemReward> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Identifier.CODEC.fieldOf("item").forGetter(ItemReward::item),
            Codec.intRange(1, MAX_COUNT).optionalFieldOf("count", 1).forGetter(ItemReward::count)
    ).apply(inst, ItemReward::of));

    /** Boxed-parameter factory for the codec (avoids the ECJ unboxing null-safety warning). */
    private static ItemReward of(Identifier item, Integer count) {
        return new ItemReward(item, count.intValue());
    }

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public void grant(RewardContext context) {
        ServerPlayer player = context.player();
        if (player == null) {
            NeroQuestsCommon.LOGGER.debug(
                    "[NeroQuests] Quest {} reward {} skipped: the recipient is offline.",
                    context.quest().id(), TYPE_ID);
            return;
        }
        // BuiltInRegistries.ITEM is a *defaulted* registry — an unknown id yields air rather than
        // null, so presence has to be asked for explicitly.
        if (!BuiltInRegistries.ITEM.containsKey(item)) {
            RewardLog.warnOnce("item:" + item,
                    "[NeroQuests] Reward {} names item '{}', which is not registered here; "
                            + "granting nothing for it.", TYPE_ID, item);
            return;
        }
        Item resolved = BuiltInRegistries.ITEM.getValue(item);
        if (resolved == null) {
            return;
        }
        int remaining = Math.min(Math.max(count, 1), MAX_COUNT);
        int perStack = Math.max(1, new ItemStack(resolved).getMaxStackSize());
        while (remaining > 0) {
            int size = Math.min(remaining, perStack);
            ItemStack stack = new ItemStack(resolved, size);
            if (!player.addItem(stack)) {
                player.drop(stack, false);
            }
            remaining -= size;
        }
    }
}
