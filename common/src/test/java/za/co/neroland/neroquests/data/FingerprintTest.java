package za.co.neroland.neroquests.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestTypes;

/** AUD-17: objective fingerprints and reconciling stored rows with changed definitions. */
class FingerprintTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final Identifier QUEST = Identifier.fromNamespaceAndPath("neroquests", "test/fp");
    private static final long DAY = 86_400_000L;

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static int fingerprint(String objectivesJson) {
        List<ObjectiveSpec> objectives = ObjectiveSpec.CODEC.listOf()
                .parse(JsonOps.INSTANCE, JsonParser.parseString(objectivesJson)).getOrThrow();
        return Quest.objectivesFingerprint(objectives);
    }

    @Test
    void fingerprintIsStableAndSensitiveToObjectives() {
        String four = "[{\"type\":\"neroquests:collect_item\",\"item\":\"minecraft:stone\",\"count\":4}]";
        String five = "[{\"type\":\"neroquests:collect_item\",\"item\":\"minecraft:stone\",\"count\":5}]";
        String appended = "[{\"type\":\"neroquests:collect_item\",\"item\":\"minecraft:stone\",\"count\":4},"
                + "{\"type\":\"neroquests:quest_complete\",\"quest\":\"neroquests:a\"}]";
        assertEquals(fingerprint(four), fingerprint(four));
        assertNotEquals(0, fingerprint(four));
        assertNotEquals(fingerprint(four), fingerprint(five));
        assertNotEquals(fingerprint(four), fingerprint(appended));
    }

    @Test
    void changedObjectivesResetIncompleteRowsAndKeepCompleteOnes() {
        Identifier done = Identifier.fromNamespaceAndPath("neroquests", "test/done");
        QuestProgressState state = new QuestProgressState();
        state.incrementObjective(PLAYER, QUEST, 0, 7, 111);
        state.incrementObjective(PLAYER, done, 0, 1, 111);
        state.markComplete(PLAYER, done, 5L);

        QuestProgressState.ReconcileResult result =
                state.reconcile(Map.of(QUEST, Integer.valueOf(222), done, Integer.valueOf(222)), 0, 1_000L);
        assertEquals(1, result.reset());
        QuestProgress reset = state.progress(PLAYER, QUEST).orElseThrow();
        assertEquals(0, reset.counter(0), "old counters must not satisfy new objectives");
        assertEquals(222, reset.fingerprint());
        assertTrue(state.isComplete(PLAYER, done), "a completed quest stays complete");
        assertEquals(222, state.progress(PLAYER, done).orElseThrow().fingerprint());

        // Same definitions again: nothing to do.
        assertFalse(state.reconcile(Map.of(QUEST, Integer.valueOf(222), done, Integer.valueOf(222)), 0, 2_000L)
                .changed());
    }

    @Test
    void rowsWithoutFingerprintAreStampedNotReset() {
        QuestProgressState state = new QuestProgressState();
        state.incrementObjective(PLAYER, QUEST, 0, 3); // legacy write: no fingerprint
        QuestProgressState.ReconcileResult result = state.reconcile(Map.of(QUEST, Integer.valueOf(9)), 0, 1_000L);
        assertEquals(0, result.reset());
        assertEquals(1, result.stamped());
        assertEquals(3, state.progress(PLAYER, QUEST).orElseThrow().counter(0));
        assertEquals(9, state.progress(PLAYER, QUEST).orElseThrow().fingerprint());
    }

    @Test
    void orphanRowsFollowRetentionOrStayInert() {
        QuestProgressState state = new QuestProgressState();
        state.incrementObjective(PLAYER, QUEST, 0, 3, 5);
        Map<Identifier, Integer> without = Map.of(Identifier.fromNamespaceAndPath("neroquests", "other"),
                Integer.valueOf(1));
        assertEquals(1, state.reconcile(without, 0, DAY).orphaned());
        // Retention 0: kept inert however long.
        assertEquals(0, state.reconcile(without, 0, 400 * DAY).pruned());
        assertTrue(state.progress(PLAYER, QUEST).isPresent());
        // Retention 30 days: pruned once orphaned for longer than that.
        assertEquals(0, state.reconcile(without, 30, 20 * DAY).pruned());
        assertEquals(1, state.reconcile(without, 30, 40 * DAY).pruned());
        assertTrue(state.progress(PLAYER, QUEST).isEmpty());
    }

    @Test
    void fingerprintAndOrphanStampRoundTrip() {
        QuestProgressState state = new QuestProgressState();
        state.incrementObjective(PLAYER, QUEST, 0, 2, 77);
        state.incrementServerObjective(QUEST, 0, 1, 78);
        JsonElement json = QuestProgressState.persistenceCodec().encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        QuestProgressState decoded = QuestProgressState.persistenceCodec().parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(77, decoded.progress(PLAYER, QUEST).orElseThrow().fingerprint());
        assertEquals(78, decoded.serverProgress(QUEST).orElseThrow().fingerprint());
    }
}
