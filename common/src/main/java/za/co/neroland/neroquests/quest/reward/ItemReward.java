package za.co.neroland.neroquests.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.custom.CustomQuests;
import za.co.neroland.neroquests.quest.QuestSource;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * Reward {@code neroquests:item} — hands over a stack of items on completion.
 *
 * <pre>{@code { "type": "neroquests:item", "item": "minecraft:iron_ingot", "count": 8 }}</pre>
 *
 * <p>The items go into the player's inventory; anything that does not fit is <b>kept</b> in the owed
 * queue and delivered once there is room (AUD-05) — or, with the server config
 * {@code owedItemsDropWhenFull=true}, dropped at the player's feet as before. A {@code count} larger
 * than the item's stack size is split into whole stacks.
 *
 * <p><b>Missing content.</b> An {@code item} id that is not registered here (a modpack quest shipped
 * to a smaller instance) is <em>not</em> a load error — the quest still completes and every other
 * reward still pays out; this one logs once by resource id and grants nothing. That mirrors the
 * objective side's missing-content handling: a quest must never become uncompletable, and a reward
 * must never crash a server.
 *
 * <p>Items need a body to go into: when the recipient is offline, dead or disconnecting, the whole
 * reward is kept in the owed queue and delivered on their next join or respawn ({@link OwedDelivery}).
 */
public record ItemReward(Identifier item, int count) implements RewardSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "item");

    /**
     * Upper bound on {@code count} (AUD-22): a full player inventory of 64-stacks (64 × 36). Beyond it the
     * codec fails and the quest is dropped (strict decode).
     */
    public static final int MAX_COUNT = 64 * 36;

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
        // D3: a custom quest's item rewards may be escrowed (paid from the author's deposit, finite).
        if (context.quest().source() == QuestSource.CUSTOM && !CustomQuests.takeEscrow(context)) {
            return;
        }
        ServerPlayer player = context.player();
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
        int total = Math.min(Math.max(count, 1), MAX_COUNT);
        if (!OwedDelivery.canReceive(player)) {
            OwedDelivery.keepItem(context.server(), context.playerId(), player, resolved, total,
                    DataComponentPatch.EMPTY, context.quest().id());
            return;
        }
        int remaining = OwedDelivery.insert(player, resolved, total, DataComponentPatch.EMPTY);
        if (remaining <= 0) {
            return;
        }
        if (!NeroQuestsConfig.OWED_ITEMS_DROP_WHEN_FULL.get().booleanValue()) {
            OwedDelivery.keepItem(context.server(), context.playerId(), player, resolved, remaining,
                    DataComponentPatch.EMPTY, context.quest().id());
            return;
        }
        int perStack = Math.max(1, new ItemStack(resolved).getMaxStackSize());
        while (remaining > 0) {
            int size = Math.min(remaining, perStack);
            ItemStack stack = new ItemStack(resolved, size);
            //? if >=26.3 {
            /*player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
            *///?} else {
            player.drop(stack, false);
            //?}
            remaining -= size;
        }
    }
}
