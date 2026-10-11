package za.co.neroland.neroquests.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.objective.CollectItemObjective;
import za.co.neroland.neroquests.quest.objective.CustomEventObjective;
import za.co.neroland.neroquests.quest.objective.ItemTarget;
import za.co.neroland.neroquests.quest.objective.ManualObjective;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;
import za.co.neroland.neroquests.quest.objective.ReachDimensionObjective;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.LootTableReward;

/** D4: every id a custom objective or reward names must exist now — including loot tables. */
class CustomContentRulesTest {

    private static Identifier id(String full) {
        return Identifier.parse(full);
    }

    /** A tiny world: stone and ender pearls (stack 16), the overworld, one channel, one quest, one loot table. */
    private static final ContentLookup WORLD = new ContentLookup() {
        @Override
        public boolean item(Identifier id) {
            return Set.of("minecraft:stone", "minecraft:ender_pearl").contains(id.toString());
        }

        @Override
        public boolean itemTag(Identifier id) {
            return id.toString().equals("c:ingots");
        }

        @Override
        public int maxStackSize(Identifier item) {
            return item.toString().equals("minecraft:ender_pearl") ? 16 : 64;
        }

        @Override
        public boolean entity(Identifier id) {
            return false;
        }

        @Override
        public boolean entityTag(Identifier id) {
            return false;
        }

        @Override
        public boolean dimension(Identifier id) {
            return id.toString().equals("minecraft:overworld");
        }

        @Override
        public boolean structure(Identifier id) {
            return false;
        }

        @Override
        public boolean structureTag(Identifier id) {
            return false;
        }

        @Override
        public boolean gate(Identifier id) {
            return false;
        }

        @Override
        public boolean channel(Identifier id) {
            return id.toString().equals("nerocolonies:life_support");
        }

        @Override
        public boolean quest(Identifier id) {
            return id.toString().equals("neroquests:intro/a");
        }

        @Override
        public boolean lootTable(Identifier id) {
            return LootTableReward.exists(Set.of(id("minecraft:chests/simple_dungeon")), id);
        }

        @Override
        public boolean currency(Identifier id) {
            return CurrencyReward.DEFAULT_CURRENCY.equals(id);
        }
    };

    private static CollectItemObjective collect(String item) {
        return new CollectItemObjective(new ItemTarget(Optional.of(id(item)), Optional.empty()), 1, false);
    }

    @Test
    void objectivesMustResolve() {
        assertTrue(CustomContentRules.objectives(List.of(collect("minecraft:stone"),
                new ReachDimensionObjective(id("minecraft:overworld")),
                new QuestCompleteObjective(id("neroquests:intro/a")), new ManualObjective("Build it")), WORLD).isEmpty());
        Optional<EditResult> typo = CustomContentRules.objectives(List.of(collect("minecraft:stone"),
                collect("minecraft:stoen")), WORLD);
        assertTrue(typo.isPresent());
        assertEquals(EditResult.OBJECTIVE_TARGET, typo.get().key());
        assertEquals(List.of("2", "minecraft:stoen"), typo.get().args());
        assertTrue(CustomContentRules.objectives(List.of(new ReachDimensionObjective(id("nerospace:moon"))), WORLD)
                .isPresent(), "an absent mod's dimension is refused (stricter than packs)");
        assertTrue(CustomContentRules.objectives(List.of(new QuestCompleteObjective(id("custom:x/y"))), WORLD)
                .isPresent());
    }

    @Test
    void channelsComeFromTheSeenSetOrALoadedMod() {
        CustomEventObjective known = new CustomEventObjective(id("nerocolonies:life_support"), Optional.empty(),
                CustomEventObjective.Direction.ANY, Optional.empty(), Optional.empty(),
                CustomEventObjective.Audience.EVERYONE, 1);
        CustomEventObjective unknown = new CustomEventObjective(id("made:up"), Optional.empty(),
                CustomEventObjective.Direction.ANY, Optional.empty(), Optional.empty(),
                CustomEventObjective.Audience.EVERYONE, 1);
        assertTrue(CustomContentRules.objectives(List.of(known), WORLD).isEmpty());
        assertTrue(CustomContentRules.objectives(List.of(unknown), WORLD).isPresent());
    }

    @Test
    void lootTablesAreValidatedAgainstTheLoadedSet() {
        assertTrue(CustomContentRules.rewards(List.of(new LootTableReward(id("minecraft:chests/simple_dungeon"))),
                WORLD).isEmpty());
        Optional<EditResult> missing = CustomContentRules.rewards(
                List.of(new LootTableReward(id("minecraft:chests/nope"))), WORLD);
        assertTrue(missing.isPresent());
        assertEquals(EditResult.REWARD_LOOT_TABLE, missing.get().key());
        assertFalse(LootTableReward.exists(Set.of(), null));
    }

    @Test
    void itemRewardsAreRealItemsOfAtMostOneStack() {
        assertTrue(CustomContentRules.rewards(List.of(new ItemReward(id("minecraft:ender_pearl"), 16)), WORLD)
                .isEmpty());
        assertEquals(EditResult.REWARD_STACK, CustomContentRules.rewards(
                List.of(new ItemReward(id("minecraft:ender_pearl"), 17)), WORLD).orElseThrow().key());
        assertEquals(EditResult.REWARD_ITEM, CustomContentRules.rewards(
                List.of(new ItemReward(id("minecraft:nope"), 1)), WORLD).orElseThrow().key());
    }

    @Test
    void creditsOnlyAndNeverOncePerWorld() {
        assertTrue(CustomContentRules.rewards(List.of(new CurrencyReward(CurrencyReward.DEFAULT_CURRENCY, 5, false)),
                WORLD).isEmpty());
        assertTrue(CustomContentRules.rewards(List.of(new CurrencyReward(id("mypack:scrip"), 5, false)), WORLD)
                .isPresent());
        assertTrue(CustomContentRules.rewards(List.of(new CurrencyReward(CurrencyReward.DEFAULT_CURRENCY, 5, true)),
                WORLD).isPresent());
    }
}
