package za.co.neroland.neroquests.quest.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if >=1.20.5 {
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.Identifier;
//?}

class LegacyOwedItemTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    //? if <1.20.5 {
    /*@Test
    void legacyItemTagRoundTripsIndependentlyOfTheOriginalStack() {
        ItemStack original = new ItemStack(Items.DIAMOND_PICKAXE);
        original.setDamageValue(13);
        CompoundTag stored = OwedDelivery.encodePatch(null, original);
        original.setDamageValue(17);

        ItemStack delivered = new ItemStack(Items.DIAMOND_PICKAXE);
        assertTrue(OwedDelivery.applyPatch(null, delivered, stored));
        assertEquals(13, delivered.getDamageValue());
    }

    @Test
    void incompatibleItemDataCannotBecomeAPlainReward() {
        CompoundTag components = new CompoundTag();
        components.putInt("minecraft:damage", 13);

        ItemStack delivered = new ItemStack(Items.DIAMOND_PICKAXE);
        assertFalse(OwedDelivery.applyPatch(null, delivered, components));
        assertEquals(0, delivered.getDamageValue());
    }
    *///?} else {
    @Test
    void upgradingSavedLegacyItemsKeepsTheirDamage() {
        CompoundTag itemTag = new CompoundTag();
        itemTag.putInt("Damage", 13);
        CompoundTag stored = NbtUtils.addDataVersion(new CompoundTag(), 3463);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var ops = RegistryOps.create(NbtOps.INSTANCE, registries);

        Identifier id = Identifier.withDefaultNamespace("diamond_pickaxe");
        var patch = OwedDelivery.upgradeLegacyItem(id, stored, itemTag, ops).orElseThrow();
        var encoded = DataComponentPatch.CODEC.encodeStart(ops, patch).result().orElseThrow();
        var damage = assertInstanceOf(CompoundTag.class, encoded).get("minecraft:damage");
        assertEquals(13, NbtOps.INSTANCE.getNumberValue(damage).result().orElseThrow().intValue());
        assertTrue(OwedDelivery.upgradeLegacyItem(id, new CompoundTag(), itemTag, ops).isEmpty());
    }
    //?}
}
