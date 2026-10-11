package za.co.neroland.neroquests.quest.reward;

import java.util.Collection;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * Reward {@code neroquests:loot_table} — rolls a loot table once per completion for the completing player
 * and hands over what it produced (D2).
 *
 * <pre>{@code { "type": "neroquests:loot_table", "table": "minecraft:chests/simple_dungeon" }}</pre>
 *
 * <p>The table is rolled with the chest parameter set at the player's position (or the world origin when
 * they are offline). Items that do not fit — or every item for a recipient who is offline, dead or
 * disconnecting — are kept in the owed queue exactly like {@link ItemReward} ({@link OwedDelivery}).
 *
 * <p>An id that names no loaded loot table grants nothing and logs once by resource id; the editor
 * refuses such an id up front ({@link #exists}). Refused in escrow mode (random loot cannot be deposited).
 */
public record LootTableReward(Identifier table) implements RewardSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "loot_table");

    public static final MapCodec<LootTableReward> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Identifier.CODEC.fieldOf("table").forGetter(LootTableReward::table)
    ).apply(inst, LootTableReward::new));

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    /** Whether {@code table} is a loot table the server has loaded. */
    public static boolean exists(MinecraftServer server, Identifier table) {
        // An unknown key resolves to LootTable.EMPTY rather than null.
        return table != null && server.reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, table)) != LootTable.EMPTY;
    }

    /** Pure form of {@link #exists(MinecraftServer, Identifier)}: is {@code table} among {@code loaded}? */
    public static boolean exists(Collection<Identifier> loaded, Identifier table) {
        return table != null && loaded.contains(table);
    }

    @Override
    public void grant(RewardContext context) {
        MinecraftServer server = context.server();
        if (!exists(server, table)) {
            RewardLog.warnOnce("loot_table:" + table,
                    "[NeroQuests] Reward {} names loot table '{}', which is not loaded here; granting nothing "
                            + "for it.", TYPE_ID, table);
            return;
        }
        LootTable loot = server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, table));
        ServerPlayer player = context.player();
        Vec3 origin = player != null ? player.position() : Vec3.ZERO;
        LootParams params = new LootParams.Builder(server.overworld())
                .withParameter(LootContextParams.ORIGIN, origin)
                .create(LootContextParamSets.CHEST);
        boolean receive = OwedDelivery.canReceive(player);
        for (ItemStack stack : loot.getRandomItems(params)) {
            if (stack.isEmpty()) {
                continue;
            }
            if (!receive || player == null) {
                OwedDelivery.keepItem(server, context.playerId(), player, stack.getItem(), stack.getCount(),
                        stack.getComponentsPatch(), context.quest().id());
                continue;
            }
            int remaining = OwedDelivery.insert(player, stack.getItem(), stack.getCount(), stack.getComponentsPatch());
            if (remaining <= 0) {
                continue;
            }
            if (!NeroQuestsConfig.OWED_ITEMS_DROP_WHEN_FULL.get().booleanValue()) {
                OwedDelivery.keepItem(server, context.playerId(), player, stack.getItem(), remaining,
                        stack.getComponentsPatch(), context.quest().id());
                continue;
            }
            ItemStack rest = stack.copyWithCount(remaining);
            //? if >=26.3 {
            /*player.drop(rest, false, net.minecraft.util.Prediction.SERVER_ONLY);
            *///?} else {
            player.drop(rest, false);
            //?}
        }
    }
}
