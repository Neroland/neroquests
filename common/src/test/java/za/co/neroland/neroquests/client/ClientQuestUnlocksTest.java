package za.co.neroland.neroquests.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The "Quest Unlocked" queue: small batches split into cards, large ones collapse into "+N more". */
class ClientQuestUnlocksTest {

    private static List<Identifier> ids(int count) {
        List<Identifier> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(Identifier.fromNamespaceAndPath("neroquests", "q" + i));
        }
        return out;
    }

    @AfterEach
    void reset() {
        ClientQuestUnlocks.clear();
    }

    @Test
    void smallBatchIsOneCardPerQuest() {
        ClientQuestUnlocks.enqueue(ids(3), 3);
        assertEquals(3, ClientQuestUnlocks.size());
        assertEquals(0, ClientQuestUnlocks.peek().more());
    }

    @Test
    void largeBatchCollapsesToOneCard() {
        ClientQuestUnlocks.enqueue(ids(12), 12);
        assertEquals(1, ClientQuestUnlocks.size());
        assertEquals(Identifier.fromNamespaceAndPath("neroquests", "q0"), ClientQuestUnlocks.peek().quest());
        assertEquals(11, ClientQuestUnlocks.peek().more());
    }

    @Test
    void truncatedBatchCountsTheUnnamed() {
        ClientQuestUnlocks.enqueue(ids(2), 70);
        assertEquals(1, ClientQuestUnlocks.size());
        assertEquals(69, ClientQuestUnlocks.peek().more());
    }

    @Test
    void queueNeverHoldsMoreThanTheCapAndLosesNoCount() {
        for (int i = 0; i < 5; i++) {
            ClientQuestUnlocks.enqueue(ids(2), 2);
        }
        assertEquals(ClientQuestUnlocks.MAX_CARDS, ClientQuestUnlocks.size());
        int shown = 0;
        while (ClientQuestUnlocks.peek() != null) {
            shown += 1 + ClientQuestUnlocks.peek().more();
            ClientQuestUnlocks.pop();
        }
        assertEquals(10, shown);
        assertNull(ClientQuestUnlocks.peek());
    }
}
