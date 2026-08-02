package za.co.neroland.neroquests.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import za.co.neroland.neroquests.quest.engine.QuestTriggers;

/**
 * The crafting trigger for {@code neroquests:craft_item}.
 *
 * <p>{@code ItemStack#onCraftedBy(Player, int)} is vanilla's single choke point for "this player
 * just took this many of this item out of a result slot" — the crafting grid, a furnace output,
 * the stonecutter, the smithing table and a villager trade all route through it, each with the
 * exact count. Injecting here rather than subscribing to a crafting event on each loader means one
 * hook instead of three, identical behaviour everywhere, no chance of double-counting, and it
 * covers Fabric, which ships no crafting event at all. The loader modules therefore register no
 * craft event of their own.
 *
 * <p>{@link QuestTriggers#itemCrafted} discards anything that is not a server player, so the
 * client-side half of a synced menu never touches progress.
 */
@Mixin(ItemStack.class)
abstract class ItemStackMixin {

    @Inject(method = "onCraftedBy", at = @At("HEAD"))
    private void neroquests$creditCraftedItem(Player player, int craftCount, CallbackInfo ci) {
        QuestTriggers.itemCrafted(player, (ItemStack) (Object) this, craftCount);
    }
}
