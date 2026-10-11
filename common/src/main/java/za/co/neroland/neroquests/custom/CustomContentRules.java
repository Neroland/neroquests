package za.co.neroland.neroquests.custom;

import java.util.List;
import java.util.Optional;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.ObjectiveSpec;
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
import za.co.neroland.neroquests.quest.objective.VisitStructureObjective;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.LootTableReward;

/**
 * The id half of custom-quest validation (D2): every id an objective or reward names must exist <em>now</em>
 * — stricter than pack loading, which tolerates content from absent mods. Pure over a
 * {@link ContentLookup}. Errors are {@link EditResult}s with translatable keys; their arguments are
 * resource ids and numbers only.
 */
public final class CustomContentRules {

    private CustomContentRules() {
    }

    /** The first objective naming something that does not exist, as an error; empty when all resolve. */
    public static Optional<EditResult> objectives(List<ObjectiveSpec> objectives, ContentLookup lookup) {
        for (int index = 0; index < objectives.size(); index++) {
            Optional<EditResult> error = objective(objectives.get(index), index, lookup);
            if (error.isPresent()) {
                return error;
            }
        }
        return Optional.empty();
    }

    private static Optional<EditResult> objective(ObjectiveSpec objective, int index, ContentLookup lookup) {
        if (objective instanceof CollectItemObjective collect) {
            return item(collect.selector(), index, lookup);
        }
        if (objective instanceof CraftItemObjective craft) {
            return item(craft.selector(), index, lookup);
        }
        if (objective instanceof KillEntityObjective kill) {
            EntityTarget target = kill.selector();
            if (target.entity().isPresent()) {
                return missing(!lookup.entity(target.entity().get()), index, target.entity().get().toString());
            }
            return target.tag().isPresent()
                    ? missing(!lookup.entityTag(target.tag().get()), index, "#" + target.tag().get())
                    : missing(true, index, "?");
        }
        if (objective instanceof ReachDimensionObjective reach) {
            return missing(!lookup.dimension(reach.dimension()), index, reach.dimension().toString());
        }
        if (objective instanceof VisitStructureObjective visit) {
            if (visit.structure().isPresent()) {
                return missing(!lookup.structure(visit.structure().get()), index, visit.structure().get().toString());
            }
            return visit.tag().isPresent()
                    ? missing(!lookup.structureTag(visit.tag().get()), index, "#" + visit.tag().get())
                    : missing(true, index, "?");
        }
        if (objective instanceof GateOpenObjective gate) {
            return missing(!lookup.gate(gate.gate()), index, gate.gate().toString());
        }
        if (objective instanceof CustomEventObjective event) {
            return missing(!lookup.channel(event.channel()), index, event.channel().toString());
        }
        if (objective instanceof QuestCompleteObjective complete) {
            return missing(!lookup.quest(complete.quest()), index, complete.quest().toString());
        }
        return Optional.empty(); // manual: nothing to resolve
    }

    private static Optional<EditResult> item(ItemTarget target, int index, ContentLookup lookup) {
        if (target.item().isPresent()) {
            return missing(!lookup.item(target.item().get()), index, target.item().get().toString());
        }
        return target.tag().isPresent()
                ? missing(!lookup.itemTag(target.tag().get()), index, "#" + target.tag().get())
                : missing(true, index, "?");
    }

    private static Optional<EditResult> missing(boolean missing, int index, String id) {
        return missing
                ? Optional.of(EditResult.error(EditResult.OBJECTIVE_TARGET, Integer.toString(index + 1), id))
                : Optional.empty();
    }

    /** The first reward naming something that does not exist (or an over-stacked item); empty when fine. */
    public static Optional<EditResult> rewards(List<RewardSpec> rewards, ContentLookup lookup) {
        for (int index = 0; index < rewards.size(); index++) {
            RewardSpec reward = rewards.get(index);
            String position = Integer.toString(index + 1);
            if (reward instanceof ItemReward item) {
                Identifier id = item.item();
                if (!lookup.item(id)) {
                    return Optional.of(EditResult.error(EditResult.REWARD_ITEM, position, id.toString()));
                }
                int max = Math.max(1, lookup.maxStackSize(id));
                if (item.count() > max) {
                    return Optional.of(EditResult.error(EditResult.REWARD_STACK, position, Integer.toString(max)));
                }
            } else if (reward instanceof LootTableReward loot) {
                if (!lookup.lootTable(loot.table())) {
                    return Optional.of(EditResult.error(EditResult.REWARD_LOOT_TABLE, position,
                            loot.table().toString()));
                }
            } else if (reward instanceof CurrencyReward currency) {
                if (currency.oncePerWorld()) {
                    return Optional.of(EditResult.error(EditResult.REWARD_TYPE, position, "once_per_world"));
                }
                if (!lookup.currency(currency.currency())) {
                    return Optional.of(EditResult.error(EditResult.REWARD_CURRENCY, position,
                            currency.currency().toString()));
                }
            }
        }
        return Optional.empty();
    }
}
