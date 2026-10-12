package za.co.neroland.neroquests.custom;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestSource;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.objective.CollectItemObjective;
import za.co.neroland.neroquests.quest.objective.CraftItemObjective;
import za.co.neroland.neroquests.quest.objective.CustomEventObjective;
import za.co.neroland.neroquests.quest.objective.GateOpenObjective;
import za.co.neroland.neroquests.quest.objective.KillEntityObjective;
import za.co.neroland.neroquests.quest.objective.ManualObjective;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;
import za.co.neroland.neroquests.quest.objective.ReachDimensionObjective;
import za.co.neroland.neroquests.quest.objective.VisitStructureObjective;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.LootTableReward;
import za.co.neroland.neroquests.quest.reward.XpReward;

/**
 * The definition rules for {@link QuestSource#CUSTOM} quests (D1/D2), as pure functions so the loader
 * ({@code QuestDefinitions}), the mutation API, the importer and the tests apply exactly the same checks.
 * Each returns a short operator-facing reason — <b>resource ids and plain words only, never the quest's
 * title, description or labels</b> (author text is never logged) — or empty.
 *
 * <p>The registry-dependent checks (does this item exist, is this channel known) live in
 * {@link CustomContentRules}; these are the shape rules that need no server.
 */
public final class CustomRules {

    /** Objective types a custom quest may use (§3.6). */
    public static final Set<Identifier> OBJECTIVE_TYPES = Set.of(
            CollectItemObjective.TYPE_ID, CraftItemObjective.TYPE_ID, KillEntityObjective.TYPE_ID,
            ReachDimensionObjective.TYPE_ID, GateOpenObjective.TYPE_ID, QuestCompleteObjective.TYPE_ID,
            CustomEventObjective.TYPE_ID, VisitStructureObjective.TYPE_ID, ManualObjective.TYPE_ID);

    /** Reward types a custom quest may use (§3.4): never gate, reputation or mod_hook. */
    public static final Set<Identifier> REWARD_TYPES = Set.of(
            ItemReward.TYPE_ID, LootTableReward.TYPE_ID, XpReward.TYPE_ID, CurrencyReward.TYPE_ID);

    /** Longest {@code custom_event} {@code event_scope} a custom quest may use. */
    public static final int EVENT_SCOPE_MAX = 64;

    private static final Pattern EVENT_SCOPE = Pattern.compile("[A-Za-z0-9_.:/#-]{1,64}");

    /** Whether {@code scope} is a legal custom-quest {@code event_scope}: 1..64 id-like characters. */
    public static boolean eventScopeOk(String scope) {
        return scope != null && EVENT_SCOPE.matcher(scope).matches();
    }

    /** The largest single item reward a custom quest may pay: one full stack of a 64-stacking item. */
    public static final int MAX_ITEM_COUNT = 64;

    private CustomRules() {
    }

    /** The first rule a custom quest breaks, or empty. Non-custom quests are not judged here. */
    public static Optional<String> violation(Quest quest, CustomLimits limits) {
        if (quest.source() != QuestSource.CUSTOM) {
            return Optional.empty();
        }
        if (quest.scope() != QuestScope.PLAYER) {
            return Optional.of("custom quests are player-scope only");
        }
        if (quest.visibleGate().isPresent()) {
            return Optional.of("custom quests cannot use visible_gate");
        }
        if (!TextSanitizer.isClean(quest.title()) || !TextSanitizer.hasVisible(quest.title())
                || TextSanitizer.length(quest.title()) > TextSanitizer.TITLE_MAX) {
            return Optional.of("title is empty, too long or not sanitised");
        }
        if (!TextSanitizer.isClean(quest.description())
                || TextSanitizer.length(quest.description()) > TextSanitizer.DESCRIPTION_MAX) {
            return Optional.of("description is too long or not sanitised");
        }
        Optional<String> objectives = objectives(quest.objectives());
        if (objectives.isPresent()) {
            return objectives;
        }
        return rewards(quest.rewards(), limits);
    }

    /** The objective-list rules: 1..12 objectives of allowed types, counts in range, labels sanitised. */
    public static Optional<String> objectives(List<ObjectiveSpec> objectives) {
        if (objectives.isEmpty()) {
            return Optional.of("no objectives");
        }
        if (objectives.size() > CustomLimits.MAX_OBJECTIVES) {
            return Optional.of("more than " + CustomLimits.MAX_OBJECTIVES + " objectives");
        }
        for (int index = 0; index < objectives.size(); index++) {
            ObjectiveSpec objective = objectives.get(index);
            if (!OBJECTIVE_TYPES.contains(objective.typeId())) {
                return Optional.of("objective #" + index + ": type " + objective.typeId()
                        + " is not available to custom quests");
            }
            int count = objective.target();
            if (count < CustomLimits.MIN_COUNT || count > CustomLimits.MAX_COUNT) {
                return Optional.of("objective #" + index + ": count must be " + CustomLimits.MIN_COUNT + " to "
                        + CustomLimits.MAX_COUNT);
            }
            if (objective instanceof ManualObjective manual && (!TextSanitizer.hasVisible(manual.label())
                    || !TextSanitizer.isClean(manual.label())
                    || TextSanitizer.length(manual.label()) > TextSanitizer.LABEL_MAX)) {
                return Optional.of("objective #" + index + ": manual label is empty, too long or not sanitised");
            }
            // J2 (RT-09): the one free string a custom objective carries besides a label is a custom_event scope
            // key (a colony, region or dimension key): bounded and id-like, so it cannot smuggle text or bulk.
            if (objective instanceof CustomEventObjective event && event.eventScope().isPresent()
                    && !eventScopeOk(event.eventScope().get())) {
                return Optional.of("objective #" + index + ": event_scope must be 1 to " + EVENT_SCOPE_MAX
                        + " characters of a-z, A-Z, 0-9 and _ . : / # -");
            }
        }
        return Optional.empty();
    }

    /** The reward-list rules: at most 9 rewards of allowed types, within the per-quest caps. */
    public static Optional<String> rewards(List<RewardSpec> rewards, CustomLimits limits) {
        if (rewards.size() > CustomLimits.MAX_REWARDS) {
            return Optional.of("more than " + CustomLimits.MAX_REWARDS + " rewards");
        }
        int stacks = 0;
        long xp = 0L;
        long currency = 0L;
        for (int index = 0; index < rewards.size(); index++) {
            RewardSpec reward = rewards.get(index);
            if (!REWARD_TYPES.contains(reward.typeId())) {
                return Optional.of("reward #" + index + ": type " + reward.typeId()
                        + " is not available to custom quests");
            }
            if (reward instanceof ItemReward item) {
                stacks++;
                if (item.count() > MAX_ITEM_COUNT) {
                    return Optional.of("reward #" + index + ": an item reward is at most one stack");
                }
            } else if (reward instanceof LootTableReward) {
                stacks++; // J2 (RT-04): a loot-table roll takes a stack slot
            } else if (reward instanceof XpReward experience) {
                xp += experience.amount();
            } else if (reward instanceof CurrencyReward money) {
                currency += money.amount();
            }
        }
        if (stacks > limits.maxStacks()) {
            return Optional.of("more than " + limits.maxStacks() + " item-stack and loot-table rewards");
        }
        if (xp > limits.maxXp()) {
            return Optional.of("more than " + limits.maxXp() + " experience");
        }
        if (currency > limits.maxCurrency()) {
            return Optional.of("more than " + limits.maxCurrency() + " currency");
        }
        return Optional.empty();
    }

    /**
     * A spine or pack quest may never depend on a custom quest (as a prerequisite or a
     * {@code quest_complete} objective): editor content must not be able to lock or unlock shipped
     * progression. The first such reference, or empty.
     */
    public static Optional<String> referencesCustom(Quest quest) {
        if (quest.source() == QuestSource.CUSTOM) {
            return Optional.empty();
        }
        for (Identifier prerequisite : quest.prerequisites()) {
            if (QuestSource.of(prerequisite) == QuestSource.CUSTOM) {
                return Optional.of("prerequisite " + prerequisite
                        + " is a custom (editor) quest; spine and pack quests may not depend on one");
            }
        }
        for (ObjectiveSpec objective : quest.objectives()) {
            if (objective instanceof QuestCompleteObjective complete
                    && QuestSource.of(complete.quest()) == QuestSource.CUSTOM) {
                return Optional.of("quest_complete objective names custom (editor) quest " + complete.quest()
                        + "; spine and pack quests may not depend on one");
            }
        }
        return Optional.empty();
    }
}
