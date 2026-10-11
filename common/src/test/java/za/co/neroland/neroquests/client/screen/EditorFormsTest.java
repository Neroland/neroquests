package za.co.neroland.neroquests.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.client.screen.EditorForms.ObjectiveDraft;
import za.co.neroland.neroquests.client.screen.EditorForms.ObjectiveKind;
import za.co.neroland.neroquests.client.screen.EditorForms.RewardDraft;
import za.co.neroland.neroquests.client.screen.EditorForms.RewardKind;
import za.co.neroland.neroquests.custom.CustomRules;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.LootTableReward;
import za.co.neroland.neroquests.quest.reward.XpReward;

/** E4: every editor form produces JSON the server's codecs accept, and the forms offer exactly the custom allow-lists. */
class EditorFormsTest {

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static ObjectiveSpec decodeObjective(JsonObject json) {
        return ObjectiveSpec.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }

    private static RewardSpec decodeReward(JsonObject json) {
        return RewardSpec.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
    }

    @Test
    void offersExactlyTheCustomAllowLists() {
        for (ObjectiveKind kind : ObjectiveKind.values()) {
            assertTrue(CustomRules.OBJECTIVE_TYPES.contains(kind.typeId()), kind + " allowed for custom quests");
        }
        assertEquals(CustomRules.OBJECTIVE_TYPES.size(), ObjectiveKind.values().length);
        assertEquals(CustomRules.REWARD_TYPES.size(), RewardKind.values().length);
    }

    @Test
    void everyObjectiveFormRoundTripsThroughTheCodec() {
        List<ObjectiveDraft> drafts = List.of(
                new ObjectiveDraft(ObjectiveKind.COLLECT_ITEM, "minecraft:oak_log", 16, true),
                new ObjectiveDraft(ObjectiveKind.COLLECT_ITEM, "#minecraft:logs", 4, false),
                new ObjectiveDraft(ObjectiveKind.CRAFT_ITEM, "minecraft:furnace", 1, false),
                new ObjectiveDraft(ObjectiveKind.KILL_ENTITY, "#minecraft:skeletons", 10, false),
                new ObjectiveDraft(ObjectiveKind.REACH_DIMENSION, "minecraft:the_nether", 1, false),
                new ObjectiveDraft(ObjectiveKind.VISIT_STRUCTURE, "#minecraft:village", 1, false),
                new ObjectiveDraft(ObjectiveKind.GATE_OPEN, "nerolandcore:first_colony", 1, false),
                new ObjectiveDraft(ObjectiveKind.CUSTOM_EVENT, "nerotech:machine_built", 3, false),
                new ObjectiveDraft(ObjectiveKind.QUEST_COMPLETE, "custom:abcd/efgh", 1, false),
                new ObjectiveDraft(ObjectiveKind.MANUAL, "Show the mayor your farm", 1, false));
        for (ObjectiveDraft draft : drafts) {
            Optional<JsonObject> json = EditorForms.objectiveJson(draft);
            assertTrue(json.isPresent(), () -> "form " + draft + " builds");
            ObjectiveSpec spec = decodeObjective(json.get());
            assertEquals(draft.kind().typeId(), spec.typeId());
            ObjectiveDraft back = EditorForms.draftOf(spec).orElseThrow();
            assertEquals(draft.kind(), back.kind());
            assertEquals(draft.target(), back.target());
            if (draft.kind().counted()) {
                assertEquals(draft.count(), back.count());
            }
        }
    }

    @Test
    void incompleteObjectiveFormsAreNotSent() {
        assertTrue(EditorForms.objectiveJson(new ObjectiveDraft(ObjectiveKind.COLLECT_ITEM, "", 1, false)).isEmpty());
        assertTrue(EditorForms.objectiveJson(new ObjectiveDraft(ObjectiveKind.COLLECT_ITEM, "Not An Id", 1, false))
                .isEmpty());
        assertTrue(EditorForms.objectiveJson(new ObjectiveDraft(ObjectiveKind.COLLECT_ITEM, "minecraft:stone", 0,
                false)).isEmpty(), "count below 1");
        assertTrue(EditorForms.objectiveJson(new ObjectiveDraft(ObjectiveKind.KILL_ENTITY, "minecraft:zombie",
                1_000_001, false)).isEmpty(), "count above 1,000,000");
        assertTrue(EditorForms.objectiveJson(new ObjectiveDraft(ObjectiveKind.REACH_DIMENSION, "#minecraft:x", 1,
                false)).isEmpty(), "dimensions have no tags");
        assertTrue(EditorForms.objectiveJson(new ObjectiveDraft(ObjectiveKind.MANUAL, "   ", 1, false)).isEmpty());
    }

    @Test
    void rewardFormsRoundTripThroughTheCodec() {
        assertEquals(new ItemReward(Identifier.withDefaultNamespace("diamond"), 3), decodeReward(
                EditorForms.rewardJson(new RewardDraft(RewardKind.ITEM, "minecraft:diamond", 3)).orElseThrow()));
        assertEquals(new XpReward(50), decodeReward(
                EditorForms.rewardJson(new RewardDraft(RewardKind.XP, "", 50)).orElseThrow()));
        assertEquals(new LootTableReward(Identifier.withDefaultNamespace("chests/simple_dungeon")), decodeReward(
                EditorForms.rewardJson(new RewardDraft(RewardKind.LOOT_TABLE, "minecraft:chests/simple_dungeon", 1))
                        .orElseThrow()));
        RewardSpec credits = decodeReward(
                EditorForms.rewardJson(new RewardDraft(RewardKind.CURRENCY, "", 25)).orElseThrow());
        assertEquals(25L, ((CurrencyReward) credits).amount());
        assertEquals(CurrencyReward.DEFAULT_CURRENCY, ((CurrencyReward) credits).currency());

        assertTrue(EditorForms.rewardJson(new RewardDraft(RewardKind.ITEM, "minecraft:diamond", 65)).isEmpty());
        assertTrue(EditorForms.rewardJson(new RewardDraft(RewardKind.ITEM, "#minecraft:logs", 1)).isEmpty());
        assertTrue(EditorForms.rewardJson(new RewardDraft(RewardKind.XP, "", 0)).isEmpty());
    }

    @Test
    void totalsCountStacksXpAndCredits() {
        List<RewardSpec> rewards = List.of(new ItemReward(Identifier.withDefaultNamespace("diamond"), 3),
                new ItemReward(Identifier.withDefaultNamespace("stone"), 64), new XpReward(20), new XpReward(5),
                new LootTableReward(Identifier.withDefaultNamespace("chests/simple_dungeon")),
                new CurrencyReward(CurrencyReward.DEFAULT_CURRENCY, 40L, false));
        assertEquals(new EditorForms.Totals(6, 2, 25L, 40L), EditorForms.totals(rewards));
    }

    @Test
    void listsAreEditedByIndex() {
        JsonArray list = new JsonArray();
        list.add(1);
        list.add(2);
        assertEquals("[1,2,3]", EditorForms.with(list, -1, new com.google.gson.JsonPrimitive(3)).toString());
        assertEquals("[9,2]", EditorForms.with(list, 0, new com.google.gson.JsonPrimitive(9)).toString());
        assertEquals("[2]", EditorForms.without(list, 0).toString());
        assertEquals("[1,2]", list.toString(), "the input is not changed");
    }

    @Test
    void numbersAndDurations() {
        assertEquals(Optional.of(Long.valueOf(5L)), EditorForms.number(" 5 ", 1, 10));
        assertTrue(EditorForms.number("11", 1, 10).isEmpty());
        assertTrue(EditorForms.number("x", 1, 10).isEmpty());
        long[] parts = EditorForms.splitDuration(90_061L);
        assertEquals(1L, parts[0]);
        assertEquals(1L, parts[1]);
        assertEquals(1L, parts[2]);
    }
}
