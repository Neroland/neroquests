package za.co.neroland.neroquests.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

/** AUD-06: each recipient of a server-scope quest is paid at most once, and --repay reopens all of them. */
class ServerRecipientLedgerTest {

    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final Identifier QUEST = Identifier.fromNamespaceAndPath("neroquests", "test/world");

    @Test
    void eachRecipientIsPaidOncePerServerQuest() {
        QuestProgressState state = new QuestProgressState();
        assertTrue(state.markServerPaid(QUEST), "the world completion pays once");
        assertTrue(state.markPaid(A, QUEST));
        assertTrue(state.markPaid(B, QUEST));
        assertFalse(state.markPaid(A, QUEST), "a recipient is never paid twice for one quest");
        assertFalse(state.markServerPaid(QUEST));
    }

    @Test
    void repayOfAServerQuestReopensEveryRecipient() {
        QuestProgressState state = new QuestProgressState();
        state.markServerPaid(QUEST);
        state.markPaid(A, QUEST);
        state.markPaid(B, QUEST);
        state.revokeServerQuest(QUEST, List.of(), false);
        assertFalse(state.markPaid(A, QUEST), "revoke without --repay keeps the recipients paid");
        state.revokeServerQuest(QUEST, List.of(), true);
        assertTrue(state.markServerPaid(QUEST));
        assertTrue(state.markPaid(A, QUEST));
        assertTrue(state.markPaid(B, QUEST));
    }
}
