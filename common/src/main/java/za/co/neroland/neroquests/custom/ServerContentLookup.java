package za.co.neroland.neroquests.custom;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.Structure;

import za.co.neroland.nerolandcore.progression.GateDefinitions;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.engine.SeenChannels;
import za.co.neroland.neroquests.quest.objective.ContentIds;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.LootTableReward;

/**
 * {@link ContentLookup} against the running server's registries, tags, levels, Core gate definitions,
 * the session's seen-channel set and the loaded quest definitions. Server thread only.
 */
public record ServerContentLookup(MinecraftServer server) implements ContentLookup {

    @Override
    public boolean item(Identifier id) {
        // Defaulted registry: an unknown id yields air, so ask containsKey; air itself is not an item to want.
        return BuiltInRegistries.ITEM.containsKey(id) && !"minecraft:air".equals(id.toString());
    }

    @Override
    public boolean itemTag(Identifier id) {
        return BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, id)).iterator().hasNext();
    }

    @Override
    public int maxStackSize(Identifier item) {
        return item(item) ? new ItemStack(BuiltInRegistries.ITEM.getValue(item)).getMaxStackSize() : 1;
    }

    @Override
    public boolean entity(Identifier id) {
        return BuiltInRegistries.ENTITY_TYPE.containsKey(id);
    }

    @Override
    public boolean entityTag(Identifier id) {
        return BuiltInRegistries.ENTITY_TYPE.getTagOrEmpty(TagKey.create(Registries.ENTITY_TYPE, id))
                .iterator().hasNext();
    }

    @Override
    public boolean dimension(Identifier id) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, id)) != null;
    }

    @Override
    public boolean structure(Identifier id) {
        return structures().containsKey(id);
    }

    @Override
    public boolean structureTag(Identifier id) {
        return structures().get(TagKey.create(Registries.STRUCTURE, id))
                .map(set -> set.size() > 0).orElse(Boolean.FALSE).booleanValue();
    }

    @Override
    public boolean gate(Identifier id) {
        return GateDefinitions.forServer(server).containsKey(id);
    }

    @Override
    public boolean channel(Identifier id) {
        return SeenChannels.seen(id) || ContentIds.modLoaded(id.getNamespace());
    }

    @Override
    public boolean quest(Identifier id) {
        return QuestDefinitions.questsForServer(server).containsKey(id);
    }

    @Override
    public boolean lootTable(Identifier id) {
        return LootTableReward.exists(server, id);
    }

    @Override
    public boolean currency(Identifier id) {
        // Custom quests pay the shared credits only: that is the currency NeroEconomy budgets.
        return CurrencyReward.DEFAULT_CURRENCY.equals(id);
    }

    private Registry<Structure> structures() {
        return server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
    }
}
