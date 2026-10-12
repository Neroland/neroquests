package za.co.neroland.neroquests.client.screen;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import za.co.neroland.nerolandcore.progression.ClientGates;
import za.co.neroland.neroquests.client.ClientQuestDefinitions;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestSource;
import za.co.neroland.neroquests.quest.QuestText;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.objective.CollectItemObjective;
import za.co.neroland.neroquests.quest.objective.CraftItemObjective;
import za.co.neroland.neroquests.quest.objective.CustomEventObjective;
import za.co.neroland.neroquests.quest.objective.EconomyObjective;
import za.co.neroland.neroquests.quest.objective.EntityTarget;
import za.co.neroland.neroquests.quest.objective.GateOpenObjective;
import za.co.neroland.neroquests.quest.objective.ItemTarget;
import za.co.neroland.neroquests.quest.objective.KillEntityObjective;
import za.co.neroland.neroquests.quest.objective.ManualObjective;
import za.co.neroland.neroquests.quest.objective.VisitStructureObjective;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;
import za.co.neroland.neroquests.quest.objective.ReachDimensionObjective;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.GateReward;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.LootTableReward;
import za.co.neroland.neroquests.quest.reward.ModHookReward;
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
        return objective(spec, QuestSource.SPINE);
    }

    /**
     * {@link #objective(ObjectiveSpec)} for an objective of a quest from {@code owner}: a custom quest's
     * {@code manual} label is author text and renders literally (D3), never as a translation key.
     */
    public static Component objective(ObjectiveSpec spec, QuestSource owner) {
        if (spec instanceof CollectItemObjective collect) {
            return Component.translatable(G + (collect.consume() ? "objective.collect_item_consume" : "objective.collect_item"),
                    itemTarget(collect.selector()));
        }
        if (spec instanceof EconomyObjective economy) {
            String event = economy.event().name().toLowerCase(java.util.Locale.ROOT);
            if (economy.event() == EconomyObjective.Event.EARNED) {
                return Component.translatable(G + "objective.economy.earned");
            }
            Component goods = economy.item().isPresent() ? itemName(economy.item().get())
                    : economy.tag().isPresent() ? rawId("target.tag", economy.tag().get())
                    : Component.translatable(G + "objective.economy.any_goods");
            return Component.translatable(G + "objective.economy." + event, goods);
        }
        if (spec instanceof CraftItemObjective craft) {
            return Component.translatable(G + "objective.craft_item", itemTarget(craft.selector()));
        }
        if (spec instanceof KillEntityObjective kill) {
            return Component.translatable(G + "objective.kill_entity", entityTarget(kill.selector()));
        }
        if (spec instanceof ReachDimensionObjective reach) {
            return Component.translatable(G + "objective.reach_dimension",
                    rawId("unknown_dimension", reach.dimension()));
        }
        if (spec instanceof GateOpenObjective gate) {
            return Component.translatable(G + "objective.gate_open", rawId("gate_id", gate.gate()));
        }
        if (spec instanceof QuestCompleteObjective chain) {
            return Component.translatable(G + "objective.quest_complete", questName(chain.quest()));
        }
        if (spec instanceof CustomEventObjective event) {
            // The channel id is the only stable thing to show: its meaning belongs to the publishing
            // mod, and NeroQuests has no dependency on it to ask for a nicer name.
            return Component.translatable(G + "objective.custom_event",
                    rawId("channel_id", event.channel()));
        }
        if (spec instanceof VisitStructureObjective visit) {
            return Component.translatable(G + "objective.visit_structure", visit.structure().isPresent()
                    ? rawId("structure_id", visit.structure().get())
                    : Component.translatable(G + "target.tag", Component.literal(visit.tag().get().toString())));
        }
        if (spec instanceof ManualObjective manual) {
            return Component.translatable(G + "objective.manual", QuestText.manualLabel(owner, manual));
        }
        return Component.translatable(G + "objective.unknown", rawId("type_id", spec.typeId()));
    }

    /**
     * The icon shown beside an objective in the quest drawer: the target item for item objectives, a fitting
     * vanilla item for the other kinds, or {@link ItemStack#EMPTY} (the drawer then draws a bullet).
     */
    public static ItemStack objectiveIcon(ObjectiveSpec spec) {
        if (spec instanceof CollectItemObjective collect) {
            return targetIcon(collect.selector());
        }
        if (spec instanceof CraftItemObjective craft) {
            ItemStack target = targetIcon(craft.selector());
            return target.isEmpty() ? new ItemStack(Items.CRAFTING_TABLE) : target;
        }
        if (spec instanceof EconomyObjective economy) {
            Item item = economy.item().map(QuestBookText::resolve).orElse(null);
            return new ItemStack(item == null ? Items.EMERALD : item);
        }
        if (spec instanceof KillEntityObjective) {
            return new ItemStack(Items.IRON_SWORD);
        }
        if (spec instanceof ReachDimensionObjective) {
            return new ItemStack(Items.COMPASS);
        }
        if (spec instanceof VisitStructureObjective) {
            return new ItemStack(Items.FILLED_MAP);
        }
        if (spec instanceof QuestCompleteObjective) {
            return new ItemStack(Items.BOOK);
        }
        if (spec instanceof ManualObjective) {
            return new ItemStack(Items.WRITABLE_BOOK);
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack targetIcon(ItemTarget target) {
        Item item = target.item().map(QuestBookText::resolve).orElse(null);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    /** The stack an item reward pays out (with its count), or {@link ItemStack#EMPTY} for other rewards. */
    public static ItemStack rewardStack(RewardSpec spec) {
        if (spec instanceof ItemReward item) {
            Item resolved = resolve(item.item());
            return resolved == null ? ItemStack.EMPTY : new ItemStack(resolved, Math.max(1, item.count()));
        }
        return ItemStack.EMPTY;
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
            return Component.translatable(G + "reward.gate", rawId("gate_id", gate.gate()));
        }
        if (spec instanceof CurrencyReward currency) {
            return Component.translatable(G + (currency.oncePerWorld() ? "reward.currency_once" : "reward.currency"),
                    Long.valueOf(currency.amount()), rawId("currency_id", currency.currency()));
        }
        if (spec instanceof ReputationReward reputation) {
            return Component.translatable(G + "reward.reputation",
                    Integer.valueOf(reputation.amount()), rawId("faction_id", reputation.faction()));
        }
        if (spec instanceof LootTableReward loot) {
            return Component.translatable(G + "reward.loot_table", rawId("loot_table_id", loot.table()));
        }
        if (spec instanceof ModHookReward hook) {
            // The sibling may name its hook with lang key hook.<ns>.<id>; the gate id is the fallback.
            Identifier gate = hook.gate();
            String id = gate.getPath().startsWith(ModHookReward.HOOK_PATH_PREFIX)
                    ? gate.getPath().substring(ModHookReward.HOOK_PATH_PREFIX.length())
                    : gate.getPath();
            return Component.translatable(G + "reward.mod_hook", Component.translatableWithFallback(
                    "hook." + gate.getNamespace() + "." + id.replace('/', '.'), gate.toString()));
        }
        return Component.translatable(G + "reward.unknown", rawId("type_id", spec.typeId()));
    }

    /** The display name of an item objective's target — an item name, or {@code #tag}. */
    public static Component itemTarget(ItemTarget target) {
        if (target.item().isPresent()) {
            return itemName(target.item().get());
        }
        if (target.tag().isPresent()) {
            return rawId("target.tag", target.tag().get());
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
            return rawId("target.tag", target.tag().get());
        }
        return Component.translatable(G + "target.none");
    }

    /** An item's display name, falling back to its raw id when the item is not registered here. */
    public static Component itemName(Identifier id) {
        Item item = resolve(id);
        return item == null ? rawId("unknown_item", id) : Component.translatable(item.getDescriptionId());
    }

    /**
     * A quest's title. A quest the server did not send (hidden from this player, AUD-19) or whose
     * {@code visible_gate} is closed here reads as "an undiscovered quest" — never its title or id.
     */
    public static Component questName(Identifier id) {
        Quest quest = ClientQuestDefinitions.quest(id).orElse(null);
        if (quest == null || (quest.visibleGate().isPresent() && !ClientGates.isOpen(quest.visibleGate().get()))) {
            return Component.translatable(G + "quest.undiscovered");
        }
        return QuestText.title(quest);
    }

    /**
     * A raw resource id shown where no display name exists (AUD-33): the id stays an id, but it sits in
     * a translation key ({@code gui.neroquests.<key>}, argument = the id) so translators control the
     * words and punctuation around it.
     */
    private static Component rawId(String key, Identifier id) {
        return Component.translatable(G + key, id.toString());
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
