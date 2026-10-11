package za.co.neroland.neroquests.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

/**
 * K2: a progress store written by an old release loads through the current codec and migrations.
 *
 * <p>The 0.1.0-beta.1 and 0.3.0-beta.1 stores share one shape (the data package did not change
 * between those tags): no {@code format} field, {@code players:[{player, updated_at,
 * quests:[{quest, counters, completed_at}]}]} and {@code server:[{quest, counters, completed_at}]}.
 * The fixtures below are written in exactly that shape, with the quest ids each release shipped.
 */
class OldWorldStoreTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    private static Identifier spine(String path) {
        return Identifier.fromNamespaceAndPath("neroquests", path);
    }

    private static Identifier colony(String path) {
        return Identifier.fromNamespaceAndPath("nerocolonies", "colony_chronicle/" + path);
    }

    private static final Identifier FIRST_TOOLS = spine("groundwork/first_tools");
    private static final Identifier IRON_AGE = spine("groundwork/iron_age");
    private static final Identifier POWER_UP = spine("industrial/power_up");
    private static final Identifier UNITED = spine("space_race/united_frontier");

    /** A 0.1.0-beta.1 store: two players, one server-scope quest, no format field. */
    private static final String STORE_0_1_0 = "{"
            + "\"players\":["
            + "{\"player\":\"" + ALICE + "\",\"updated_at\":1726000000000,\"quests\":["
            + "{\"quest\":\"" + FIRST_TOOLS + "\",\"counters\":[1,1],\"completed_at\":1726000000100},"
            + "{\"quest\":\"" + IRON_AGE + "\",\"counters\":[12]}]},"
            + "{\"player\":\"" + BOB + "\",\"updated_at\":1726000000500,\"quests\":["
            + "{\"quest\":\"" + FIRST_TOOLS + "\",\"counters\":[0,1]}]}],"
            + "\"server\":["
            + "{\"quest\":\"" + UNITED + "\",\"counters\":[3],\"completed_at\":1726000000900},"
            + "{\"quest\":\"" + POWER_UP + "\",\"counters\":[7]}]}";

    /** A 0.3.0-beta.1 store (same shape) holding the three colony quests that later left the spine. */
    private static final String STORE_0_3_0 = "{"
            + "\"players\":["
            + "{\"player\":\"" + ALICE + "\",\"updated_at\":1728000000000,\"quests\":["
            + "{\"quest\":\"" + FIRST_TOOLS + "\",\"counters\":[1,1],\"completed_at\":1726000000100},"
            + "{\"quest\":\"neroquests:space_race/settled_ground\",\"counters\":[4],\"completed_at\":1728000000100},"
            + "{\"quest\":\"neroquests:space_race/full_larder\",\"counters\":[20]}]}],"
            + "\"server\":["
            + "{\"quest\":\"neroquests:space_race/breathing_room\",\"counters\":[1],\"completed_at\":1728000000200}]}";

    private static QuestProgressState decode(String json) {
        return QuestProgressState.persistenceCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static QuestProgressState roundTrip(QuestProgressState state) {
        JsonElement saved = QuestProgressState.persistenceCodec().encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        assertEquals(2, saved.getAsJsonObject().get("format").getAsInt(), "saved in the current format");
        return QuestProgressState.persistenceCodec().parse(JsonOps.INSTANCE, saved).getOrThrow();
    }

    @Test
    void store010ProgressIsIntact() {
        QuestProgressState state = decode(STORE_0_1_0);
        assertTrue(state.isComplete(ALICE, FIRST_TOOLS));
        assertEquals(1726000000100L, state.progress(ALICE, FIRST_TOOLS).orElseThrow().completedAt());
        assertFalse(state.isComplete(ALICE, IRON_AGE));
        assertEquals(12, state.progress(ALICE, IRON_AGE).orElseThrow().counter(0));
        assertFalse(state.isComplete(BOB, FIRST_TOOLS));
        assertEquals(0, state.progress(BOB, FIRST_TOOLS).orElseThrow().counter(0));
        assertEquals(1, state.progress(BOB, FIRST_TOOLS).orElseThrow().counter(1));
        assertTrue(state.isServerComplete(UNITED));
        assertEquals(7, state.serverProgress(POWER_UP).orElseThrow().counter(0));
        assertTrue(state.owed(ALICE).isEmpty(), "no owed rewards invented");
    }

    @Test
    void store010PaidLedgerBackFilledFromCompletions() {
        QuestProgressState state = decode(STORE_0_1_0);
        assertTrue(state.isPaid(ALICE, FIRST_TOOLS), "a completion paid by 0.1.0 never pays again");
        assertFalse(state.isPaid(ALICE, IRON_AGE), "an incomplete quest is not in the ledger");
        assertFalse(state.isPaid(BOB, FIRST_TOOLS));
        assertTrue(state.isServerPaid(UNITED));
        assertFalse(state.isServerPaid(POWER_UP));
        QuestProgressState reloaded = roundTrip(state);
        assertTrue(reloaded.isPaid(ALICE, FIRST_TOOLS), "ledger persisted");
        assertTrue(reloaded.isServerPaid(UNITED));
    }

    @Test
    void store010FingerprintsStampedWithoutResetting() {
        QuestProgressState state = decode(STORE_0_1_0);
        Map<Identifier, Integer> fingerprints = new LinkedHashMap<>();
        fingerprints.put(FIRST_TOOLS, Integer.valueOf(101));
        fingerprints.put(IRON_AGE, Integer.valueOf(102));
        fingerprints.put(UNITED, Integer.valueOf(103));
        fingerprints.put(POWER_UP, Integer.valueOf(104));
        QuestProgressState.ReconcileResult result = state.reconcile(fingerprints, 0, 1_760_000_000_000L);
        assertEquals(5, result.stamped(), "every legacy row is stamped");
        assertEquals(0, result.reset(), "legacy rows are never reset");
        assertEquals(101, state.progress(ALICE, FIRST_TOOLS).orElseThrow().fingerprint());
        assertEquals(102, state.progress(ALICE, IRON_AGE).orElseThrow().fingerprint());
        assertEquals(12, state.progress(ALICE, IRON_AGE).orElseThrow().counter(0), "counters kept");
        assertEquals(104, state.serverProgress(POWER_UP).orElseThrow().fingerprint());
        assertTrue(state.isComplete(ALICE, FIRST_TOOLS));
        QuestProgressState reloaded = roundTrip(state);
        assertEquals(102, reloaded.progress(ALICE, IRON_AGE).orElseThrow().fingerprint(), "stamp persisted");
        assertFalse(reloaded.reconcile(fingerprints, 0, 1_760_000_001_000L).changed(), "nothing left to stamp");
    }

    @Test
    void store030MovesTheThreeColonyQuests() {
        QuestProgressState state = decode(STORE_0_3_0);
        assertTrue(state.isComplete(ALICE, colony("settled_ground")));
        assertFalse(state.progress(ALICE, spine("space_race/settled_ground")).isPresent());
        assertTrue(state.isPaid(ALICE, colony("settled_ground")), "ledger seeded, then moved with the row");
        assertFalse(state.isPaid(ALICE, spine("space_race/settled_ground")));
        assertEquals(20, state.progress(ALICE, colony("full_larder")).orElseThrow().counter(0));
        assertFalse(state.isPaid(ALICE, colony("full_larder")));
        assertFalse(state.progress(ALICE, spine("space_race/full_larder")).isPresent());
        assertTrue(state.isServerComplete(colony("breathing_room")));
        assertTrue(state.isServerPaid(colony("breathing_room")));
        assertFalse(state.serverProgress(spine("space_race/breathing_room")).isPresent());
        assertTrue(state.isComplete(ALICE, FIRST_TOOLS), "unmoved rows untouched");
        assertTrue(state.isPaid(ALICE, FIRST_TOOLS));
        QuestProgressState reloaded = roundTrip(state);
        assertTrue(reloaded.isComplete(ALICE, colony("settled_ground")));
        assertFalse(reloaded.migrateMovedQuests(), "a migrated store is not migrated again");
    }
}
