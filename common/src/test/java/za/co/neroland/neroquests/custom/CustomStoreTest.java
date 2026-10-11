package za.co.neroland.neroquests.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.objective.CollectItemObjective;
import za.co.neroland.neroquests.quest.objective.ItemTarget;
import za.co.neroland.neroquests.quest.reward.XpReward;

/** D4: the custom store — content building, export and erasure of created_by, persistence, delete-prunes-progress. */
class CustomStoreTest {

    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final ObjectiveSpec STONE = new CollectItemObjective(
            new ItemTarget(Optional.of(Identifier.parse("minecraft:stone")), Optional.empty()), 4, false);

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static CustomQuestState store() {
        CustomQuestState state = new CustomQuestState();
        state.putChapter(new CustomChapter("chap2345", "My chapter", Identifier.parse("minecraft:stone"), 0, 1L));
        state.putQuest(CustomQuest.create(Identifier.parse("custom:chap2345/aaaa2222"), "chap2345", "Mine",
                Quest.DEFAULT_ICON, 3, -2, List.of(STONE), Optional.of(AUTHOR), 10L)
                .withRewards(List.of(new XpReward(5)), List.of(), 11L));
        state.putQuest(CustomQuest.create(Identifier.parse("custom:chap2345/bbbb3333"), "chap2345", "Theirs",
                Quest.DEFAULT_ICON, 0, 0, List.of(STONE), Optional.of(OTHER), 12L));
        return state;
    }

    @Test
    void storeBuildsDefinitionsForTheSharedPipeline() {
        QuestDefinitions.Extra extra = CustomContent.build(store());
        assertEquals(2, extra.quests().size());
        Quest quest = extra.quests().get(Identifier.parse("custom:chap2345/aaaa2222"));
        assertEquals("Mine", quest.title());
        assertEquals(1, quest.rewards().size());
        assertEquals(1, extra.chapters().size());
        assertEquals(3, extra.chapters().get(Identifier.parse("custom:chap2345")).quests().get(0).x());
    }

    @Test
    void exportHoldsOnlyTheRequestersRows() {
        CustomQuestState state = store();
        state.allow(AUTHOR);
        JsonObject export = state.export(AUTHOR);
        assertEquals(1, export.getAsJsonArray("authored_custom_quests").size());
        JsonObject row = export.getAsJsonArray("authored_custom_quests").get(0).getAsJsonObject();
        assertEquals("custom:chap2345/aaaa2222", row.get("id").getAsString());
        assertEquals("Mine", row.get("title").getAsString());
        assertTrue(export.get("editor_allow_list").getAsBoolean());
        assertFalse(export.toString().contains(OTHER.toString()));
        assertFalse(export.toString().contains(AUTHOR.toString()), "the export never repeats the UUID");
        assertFalse(state.export(OTHER).get("editor_allow_list").getAsBoolean());
    }

    @Test
    void erasureClearsCreatedByAndKeepsTheQuest() {
        CustomQuestState state = store();
        state.allow(AUTHOR);
        assertTrue(state.erase(AUTHOR));
        CustomQuest kept = state.quest(Identifier.parse("custom:chap2345/aaaa2222")).orElseThrow();
        assertTrue(kept.createdBy().isEmpty());
        assertEquals("Mine", kept.title());
        assertFalse(state.isAllowed(AUTHOR));
        assertEquals(0, state.export(AUTHOR).getAsJsonArray("authored_custom_quests").size());
        assertTrue(state.quest(Identifier.parse("custom:chap2345/bbbb3333")).orElseThrow().authoredBy(OTHER));
        assertFalse(state.erase(AUTHOR), "nothing left to erase");
    }

    @Test
    void persistenceRoundTrips() {
        CustomQuestState state = store();
        state.allow(OTHER);
        state.setLocked(true);
        var encoded = CustomQuestState.persistenceCodec().encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        CustomQuestState decoded = CustomQuestState.persistenceCodec().parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(2, decoded.quests().size());
        assertTrue(decoded.isLocked());
        assertTrue(decoded.isAllowed(OTHER));
        CustomQuest quest = decoded.quest(Identifier.parse("custom:chap2345/aaaa2222")).orElseThrow();
        assertEquals(Optional.of(AUTHOR), quest.createdBy());
        assertEquals(-2, quest.y());
        assertEquals(List.of(STONE), quest.objectives().orElseThrow());
    }

    @Test
    void undecodableRowsAreKeptButNotLoaded() {
        CustomQuestState state = new CustomQuestState();
        state.putChapter(new CustomChapter("chap2345", "C", Identifier.parse("minecraft:stone"), 0, 1L));
        CustomQuest broken = new CustomQuest(Identifier.parse("custom:chap2345/cccc4444"), "chap2345", "B", "",
                Quest.DEFAULT_ICON, 0, 0, List.of(), "[{\"type\":\"nosuchmod:thing\"", "[]", false, 0,
                Optional.empty(), 1L, 1L, List.of());
        state.putQuest(broken);
        assertTrue(CustomContent.build(state).quests().isEmpty());
        assertEquals(broken.objectivesJson(), state.quest(broken.id()).orElseThrow().objectivesJson());
    }

    @Test
    void deletingAQuestPrunesEveryPlayersProgressAndLedger() {
        QuestProgressState progress = new QuestProgressState();
        Identifier gone = Identifier.parse("custom:chap2345/aaaa2222");
        Identifier kept = Identifier.parse("neroquests:intro/first_steps");
        progress.incrementObjective(AUTHOR, gone, 0, 2);
        progress.markComplete(OTHER, gone, 5L);
        progress.markPaid(OTHER, gone);
        progress.markRepeatPaid(OTHER, gone, 6L);
        progress.markComplete(OTHER, kept, 5L);
        progress.recordQuestCompletion(gone);

        assertEquals(2, progress.pruneQuest(gone));
        assertTrue(progress.progress(AUTHOR, gone).isEmpty());
        assertTrue(progress.progress(OTHER, gone).isEmpty());
        assertFalse(progress.isPaid(OTHER, gone));
        assertEquals(0L, progress.lastRepeatPaid(OTHER, gone));
        assertEquals(0L, progress.questCompletions(gone));
        assertTrue(progress.isComplete(OTHER, kept), "other quests untouched");
    }

    @Test
    void repeatLedgerIsExportedErasedAndClearedByRepay() {
        QuestProgressState progress = new QuestProgressState();
        Identifier quest = Identifier.parse("custom:chap2345/aaaa2222");
        progress.markComplete(AUTHOR, quest, 5L);
        progress.markRepeatPaid(AUTHOR, quest, 1234L);
        assertEquals(1234L, progress.lastRepeatPaid(AUTHOR, quest));
        assertEquals(1, progress.export(AUTHOR).getAsJsonArray("repeat_paid").size());

        assertTrue(progress.rearmRepeatable(AUTHOR, quest));
        assertFalse(progress.isComplete(AUTHOR, quest));
        assertEquals(1234L, progress.lastRepeatPaid(AUTHOR, quest), "re-arming keeps the last-paid time");

        progress.revokeQuest(AUTHOR, quest, List.of(), true);
        assertEquals(0L, progress.lastRepeatPaid(AUTHOR, quest), "--repay clears it");

        progress.markRepeatPaid(AUTHOR, quest, 99L);
        progress.forgetPlayer(AUTHOR);
        assertEquals(0L, progress.lastRepeatPaid(AUTHOR, quest), "erasure clears it");
    }
}
