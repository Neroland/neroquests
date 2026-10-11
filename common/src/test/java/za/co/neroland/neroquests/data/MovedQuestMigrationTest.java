package za.co.neroland.neroquests.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

/** I1: progress for the three colony quests that left the spine moves to the NeroColonies pack ids. */
class MovedQuestMigrationTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final Identifier OLD_SETTLED = Identifier.fromNamespaceAndPath("neroquests", "space_race/settled_ground");
    private static final Identifier NEW_SETTLED = Identifier.fromNamespaceAndPath("nerocolonies", "colony_chronicle/settled_ground");
    private static final Identifier OLD_AIR = Identifier.fromNamespaceAndPath("neroquests", "space_race/breathing_room");
    private static final Identifier NEW_AIR = Identifier.fromNamespaceAndPath("nerocolonies", "colony_chronicle/breathing_room");
    private static final Identifier OLD_FOOD = Identifier.fromNamespaceAndPath("neroquests", "space_race/full_larder");
    private static final Identifier NEW_FOOD = Identifier.fromNamespaceAndPath("nerocolonies", "colony_chronicle/full_larder");

    private static String legacyStore(int format) {
        return "{\"format\":" + format + ",\"players\":[{\"player\":\"" + PLAYER + "\",\"updated_at\":5,"
                + "\"quests\":[{\"quest\":\"" + OLD_SETTLED + "\",\"counters\":[3],\"completed_at\":9}],"
                + "\"paid\":[\"" + OLD_SETTLED + "\"],"
                + "\"revoked\":[{\"quest\":\"" + OLD_SETTLED + "\",\"counters\":[3]}],"
                + "\"repeat_paid\":[{\"quest\":\"" + OLD_SETTLED + "\",\"at\":7}],"
                + "\"owed\":[{\"key\":\"nq:1:" + OLD_SETTLED + "\",\"currency\":\"neroeconomy:credits\",\"amount\":250,"
                + "\"source\":\"" + OLD_SETTLED + "\"}]}],"
                + "\"server\":[{\"quest\":\"" + OLD_AIR + "\",\"counters\":[1],\"completed_at\":9},"
                + "{\"quest\":\"" + OLD_FOOD + "\",\"counters\":[0]}],"
                + "\"server_paid\":[\"" + OLD_AIR + "\"],"
                + "\"server_revoked\":[{\"quest\":\"" + OLD_FOOD + "\",\"counters\":[0]}],"
                + "\"claimed_once\":[\"" + OLD_AIR + "#1\"],"
                + "\"quest_completions\":[{\"id\":\"" + OLD_SETTLED + "\",\"count\":4},"
                + "{\"id\":\"" + NEW_SETTLED + "\",\"count\":1}]}";
    }

    private static QuestProgressState decode(String json) {
        return QuestProgressState.persistenceCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void formatOneStoreMovesEveryRow() {
        QuestProgressState state = decode(legacyStore(1));
        assertTrue(state.isComplete(PLAYER, NEW_SETTLED));
        assertFalse(state.progress(PLAYER, OLD_SETTLED).isPresent());
        assertTrue(state.isPaid(PLAYER, NEW_SETTLED));
        assertFalse(state.isPaid(PLAYER, OLD_SETTLED));
        assertTrue(state.isRevoked(PLAYER, NEW_SETTLED));
        assertEquals(7L, state.lastRepeatPaid(PLAYER, NEW_SETTLED));
        assertEquals(NEW_SETTLED, state.owed(PLAYER).get(0).source());
        assertEquals("nq:1:" + OLD_SETTLED, state.owed(PLAYER).get(0).requestKey(), "request key is identity: kept");
        assertTrue(state.isServerComplete(NEW_AIR));
        assertFalse(state.serverProgress(OLD_AIR).isPresent());
        assertTrue(state.serverProgress(NEW_FOOD).isPresent());
        assertTrue(state.isServerPaid(NEW_AIR));
        assertFalse(state.isServerPaid(OLD_AIR));
        assertTrue(state.isClaimedOnce(NEW_AIR + "#1"));
        assertFalse(state.isClaimedOnce(OLD_AIR + "#1"));
        assertEquals(5L, state.questCompletions(NEW_SETTLED), "counts merge");
        assertEquals(0L, state.questCompletions(OLD_SETTLED));
    }

    @Test
    void migrationIsIdempotent() {
        QuestProgressState state = decode(legacyStore(1));
        assertFalse(state.migrateMovedQuests(), "second run moves nothing");
        JsonElement saved = QuestProgressState.persistenceCodec().encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        assertEquals(2, saved.getAsJsonObject().get("format").getAsInt());
        QuestProgressState reloaded = QuestProgressState.persistenceCodec().parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(5L, reloaded.questCompletions(NEW_SETTLED), "a format-2 store is not migrated again");
        assertTrue(reloaded.isComplete(PLAYER, NEW_SETTLED));
    }

    @Test
    void formatTwoStoreIsLeftAlone() {
        QuestProgressState state = decode(legacyStore(2));
        assertTrue(state.isComplete(PLAYER, OLD_SETTLED), "only stores older than format 2 migrate");
    }

    @Test
    void completedOldRowBeatsIncompleteNewRow() {
        String json = "{\"format\":1,\"players\":[{\"player\":\"" + PLAYER + "\","
                + "\"quests\":[{\"quest\":\"" + OLD_SETTLED + "\",\"counters\":[3],\"completed_at\":9},"
                + "{\"quest\":\"" + NEW_SETTLED + "\",\"counters\":[1]}]}]}";
        QuestProgressState state = decode(json);
        assertTrue(state.isComplete(PLAYER, NEW_SETTLED));
        assertEquals(1, state.playerProgress(PLAYER).size());
    }
}
