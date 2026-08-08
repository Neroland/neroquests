package za.co.neroland.neroquests.client.screen;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import za.co.neroland.neroquests.client.ClientQuestDefinitions;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.objective.CollectItemObjective;
import za.co.neroland.neroquests.quest.objective.CraftItemObjective;
import za.co.neroland.neroquests.quest.objective.CustomEventObjective;
import za.co.neroland.neroquests.quest.objective.EntityTarget;
import za.co.neroland.neroquests.quest.objective.GateOpenObjective;
import za.co.neroland.neroquests.quest.objective.ItemTarget;
import za.co.neroland.neroquests.quest.objective.KillEntityObjective;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;
import za.co.neroland.neroquests.quest.objective.ReachDimensionObjective;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.GateReward;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.ReputationReward;
import za.co.neroland.neroquests.quest.reward.XpReward;

/**
 * Turns quest data into display text and icons for the quest book. Client-only.
 *
 * <p>Every string goes through a translation key so packs and translators can restyle the book
 * without touching code. Objectives and rewards are matched by <em>type</em> rather than by their
 * type id string, so an unregistered type (content from a mod that is not installed) falls through
 * to the generic "unknown" line instead of rendering blank.
 */
public final class QuestBookText {

    private static final String G = "gui.neroquests.";

    private QuestBookText() {
    }

    /** A one-line description of what an objective asks for, without its progress numbers. */
    public static Component objective(ObjectiveSpec spec) {
        if (spec instanceof CollectItemObjective collect) {
            return Component.translatable(G + "objective.collect_item", itemTarget(collect.selector()));
        }
        if (spec instanceof CraftItemObjective craft) {
            return Component.translatable(G + "objective.craft_item", itemTarget(craft.selector()));
        }
        if (spec instanceof KillEntityObjective kill) {
            return Component.translatable(G + "objective.kill_entity", entityTarget(kill.selector()));
        }
        if (spec instanceof ReachDimensionObjective reach) {
            return Component.translatable(G + "objective.reach_dimension",
                    Component.literal(reach.dimension().toString()));
        }
        if (spec instanceof GateOpenObjective gate) {
            return Component.translatable(G + "objective.gate_open", Component.literal(gate.gate().toString()));
        }
        if (spec instanceof QuestCompleteObjective chain) {
            return Component.translatable(G + "objective.quest_complete", questName(chain.quest()));
        }
        if (spec instanceof CustomEventObjective event) {
            // The channel id is the only stable thing to show: its meaning belongs to the publishing
            // mod, and NeroQuests has no dependency on it to ask for a nicer name.
            return Component.translatable(G + "objective.custom_event",
                    Component.literal(event.channel().toString()));
        }
        return Component.translatable(G + "objective.unknown", Component.literal(spec.typeId().toString()));
    }

    /** A one-line description of a reward payout. */
    public static Component reward(RewardSpec spec) {
        if (spec instanceof XpReward xp) {
            return Component.translatable(G + "reward.xp", Integer.valueOf(xp.amount()));
        }
        if (spec instanceof ItemReward item) {
            return Component.translatable(G + "reward.item",
                    Integer.valueOf(item.count()), itemName(item.item()));
        }
        if (spec instanceof GateReward gate) {
            return Component.translatable(G + "reward.gate", Component.literal(gate.gate().toString()));
        }
        if (spec instanceof CurrencyReward currency) {
            return Component.translatable(G + "reward.currency",
                    Long.valueOf(currency.amount()), Component.literal(currency.currency().toString()));
        }
        if (spec instanceof ReputationReward reputation) {
            return Component.translatable(G + "reward.reputation",
                    Integer.valueOf(reputation.amount()), Component.literal(reputation.faction().toString()));
        }
        return Component.translatable(G + "reward.unknown", Component.literal(spec.typeId().toString()));
    }

    /** The display name of an item objective's target — an item name, or {@code #tag}. */
    public static Component itemTarget(ItemTarget target) {
        if (target.item().isPresent()) {
            return itemName(target.item().get());
        }
        if (target.tag().isPresent()) {
            return Component.literal("#" + target.tag().get());
        }
        return Component.translatable(G + "target.none");
    }

    /** The display name of an entity objective's target — an entity name, or {@code #tag}. */
    public static Component entityTarget(EntityTarget target) {
        if (target.entity().isPresent()) {
            // Identifier#toLanguageKey builds "entity.<namespace>.<path>", the entity description key.
            return Component.translatable(target.entity().get().toLanguageKey("entity"));
        }
        if (target.tag().isPresent()) {
            return Component.literal("#" + target.tag().get());
        }
        return Component.translatable(G + "target.none");
    }

    /** An item's display name, falling back to its raw id when the item is not registered here. */
    public static Component itemName(Identifier id) {
        Item item = resolve(id);
        return item == null ? Component.literal(id.toString()) : Component.translatable(item.getDescriptionId());
    }

    /** A quest's title, falling back to its raw id when the quest is not in the synced snapshot. */
    public static Component questName(Identifier id) {
        Quest quest = ClientQuestDefinitions.quest(id).orElse(null);
        return quest == null ? Component.literal(id.toString()) : Component.translatable(quest.title());
    }

    /** The icon stack for a quest or chapter, falling back to a plain book. */
    public static ItemStack icon(Identifier id) {
        Item item = resolve(id);
        return item == null ? new ItemStack(Items.BOOK) : new ItemStack(item);
    }

    /**
     * BuiltInRegistries.ITEM is a <em>defaulted</em> registry — an unknown id silently yields air —
     * so presence has to be asked for explicitly before looking the value up.
     */
    private static Item resolve(Identifier id) {
        return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.getValue(id) : null;
    }
}
