package za.co.neroland.neroquests.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

/** AUD-01: the paid ledger, revoked-until-changed, erasure and persistence. No Minecraft bootstrap. */
class PaidLedgerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final Identifier QUEST = Identifier.fromNamespaceAndPath("neroquests", "test/ledger");
    private static final List<Integer> TARGETS = List.of(Integer.valueOf(3), Integer.valueOf(1));

    private static QuestProgressState completed() {
        QuestProgressState state = new QuestProgressState();
        state.incrementObjective(PLAYER, QUEST, 0, 3);
        state.incrementObjective(PLAYER, QUEST, 1, 1);
        assertTrue(state.markComplete(PLAYER, QUEST, 1_000L));
        assertTrue(state.markPaid(PLAYER, QUEST), "first completion pays");
        return state;
    }

    private static List<Integer> snapshot(QuestProgressState state) {
        return QuestProgressState.revocationSnapshot(
                state.progress(PLAYER, QUEST).orElse(QuestProgress.EMPTY), TARGETS);
    }

    @Test
    void ledgerPreventsRepayAfterRevoke() {
        QuestProgressState state = completed();
        assertTrue(state.revokeQuest(PLAYER, QUEST, snapshot(state), false));
        assertFalse(state.isComplete(PLAYER, QUEST));
        assertTrue(state.isPaid(PLAYER, QUEST), "revoke without --repay keeps the ledger entry");
        assertTrue(state.markComplete(PLAYER, QUEST, 2_000L));
        assertFalse(state.markPaid(PLAYER, QUEST), "re-completion must not pay again");
    }

    @Test
    void repayFlagPaysAgain() {
        QuestProgressState state = completed();
        assertTrue(state.revokeQuest(PLAYER, QUEST, snapshot(state), true));
        assertFalse(state.isPaid(PLAYER, QUEST));
        assertTrue(state.markPaid(PLAYER, QUEST), "--repay lets the next completion pay");
    }

    @Test
    void serverLedgerWorksTheSameWay() {
        QuestProgressState state = new QuestProgressState();
        assertTrue(state.markServerComplete(QUEST, 1_000L));
        assertTrue(state.markServerPaid(QUEST));
        assertTrue(state.revokeServerQuest(QUEST, List.of(Integer.valueOf(1)), false));
        assertFalse(state.markServerPaid(QUEST));
        assertTrue(state.revokeServerQuest(QUEST, List.of(Integer.valueOf(1)), true) || !state.isServerPaid(QUEST));
        assertTrue(state.markServerPaid(QUEST));
    }

    @Test
    void snapshotOfACompletedQuestIsEveryTarget() {
        QuestProgressState state = new QuestProgressState();
        state.markComplete(PLAYER, QUEST, 1_000L); // e.g. an admin grant: no counters at all
        assertEquals(List.of(Integer.valueOf(3), Integer.valueOf(1)), snapshot(state));
    }

    @Test
    void revokedUntilChangedHoldsUntilACounterChanges() {
        QuestProgressState state = completed();
        state.revokeQuest(PLAYER, QUEST, snapshot(state), false);
        assertTrue(state.isRevoked(PLAYER, QUEST));
        // The sweep re-measures the same inventory/gates: still held.
        assertTrue(state.revocationHolds(PLAYER, QUEST, new int[] {3, 1}));
        // A missing-content objective (-1) is not compared.
        assertTrue(state.revocationHolds(PLAYER, QUEST, new int[] {3, -1}));
        assertTrue(state.isRevoked(PLAYER, QUEST));
        // The player's progress actually changed: the hold lifts for good.
        assertFalse(state.revocationHolds(PLAYER, QUEST, new int[] {2, 1}));
        assertFalse(state.isRevoked(PLAYER, QUEST));
        assertFalse(state.revocationHolds(PLAYER, QUEST, new int[] {3, 1}));
    }

    @Test
    void ledgerAndRevocationRoundTripAndAreErased() {
        QuestProgressState state = completed();
        state.revokeQuest(PLAYER, QUEST, snapshot(state), false);
        Identifier other = Identifier.fromNamespaceAndPath("neroquests", "test/other");
        state.markPaid(PLAYER, other);
        state.markServerPaid(QUEST);

        JsonElement json = QuestProgressState.persistenceCodec().encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        QuestProgressState decoded = QuestProgressState.persistenceCodec().parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(Set.of(QUEST, other), decoded.paidQuests(PLAYER));
        assertTrue(decoded.isRevoked(PLAYER, QUEST));
        assertTrue(decoded.revocationHolds(PLAYER, QUEST, new int[] {3, 1}));
        assertTrue(decoded.isServerPaid(QUEST));
        assertTrue(decoded.export(PLAYER).getAsJsonArray("paid_quests").size() == 2);

        decoded.forgetPlayer(PLAYER);
        assertTrue(decoded.paidQuests(PLAYER).isEmpty());
        assertFalse(decoded.isRevoked(PLAYER, QUEST));
        assertTrue(decoded.isSuppressed(PLAYER), "an erased player is not evaluated until they rejoin");
        assertTrue(decoded.isServerPaid(QUEST), "world data is not personal data");
        decoded.playerJoined(PLAYER);
        assertFalse(decoded.isSuppressed(PLAYER));
    }

    @Test
    void preLedgerStoreSeedsTheLedgerFromCompletions() {
        String legacy = "{\"players\":[{\"player\":\"" + PLAYER + "\",\"updated_at\":5,"
                + "\"quests\":[{\"quest\":\"" + QUEST + "\",\"counters\":[3,1],\"completed_at\":9}]}],"
                + "\"server\":[{\"quest\":\"neroquests:test/world\",\"completed_at\":9}]}";
        QuestProgressState decoded = QuestProgressState.persistenceCodec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString(legacy)).getOrThrow();
        assertTrue(decoded.isPaid(PLAYER, QUEST));
        assertTrue(decoded.isServerPaid(Identifier.fromNamespaceAndPath("neroquests", "test/world")));
    }

    @Test
    void retentionNeverPurgesActivePlayers() {
        QuestProgressState state = completed();
        // Stamped "now" by completed(); a 1-day retention keeps them, and so does being online.
        assertEquals(0, state.pruneStale(1, Set.of(PLAYER)));
        assertTrue(state.isComplete(PLAYER, QUEST));
    }
}
