package za.co.neroland.neroquests.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.Test;

/** The unlock diff: each quest is reported once per session, in definition order. */
class QuestUnlocksTest {

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("neroquests", path);
    }

    @Test
    void reportsOnlyNewIdsAndRemembersThem() {
        Set<Identifier> known = new LinkedHashSet<>(List.of(id("a")));
        assertEquals(List.of(id("b"), id("c")), QuestUnlocks.newlyUnlocked(known, List.of(id("a"), id("b"), id("c"))));
        assertTrue(QuestUnlocks.newlyUnlocked(known, List.of(id("b"), id("c"))).isEmpty());
    }

    @Test
    void aQuestThatLeavesAndReturnsIsNotReportedAgain() {
        Set<Identifier> known = new LinkedHashSet<>();
        QuestUnlocks.newlyUnlocked(known, List.of(id("repeatable")));
        QuestUnlocks.newlyUnlocked(known, List.of());
        assertTrue(QuestUnlocks.newlyUnlocked(known, List.of(id("repeatable"))).isEmpty());
    }

    @Test
    void payloadKeepsTheTotalWhenTruncated() {
        List<Identifier> many = new java.util.ArrayList<>();
        for (int i = 0; i < QuestUnlockedPayload.MAX_IDS + 10; i++) {
            many.add(id("q" + i));
        }
        QuestUnlockedPayload payload = QuestUnlockedPayload.of(many);
        assertEquals(QuestUnlockedPayload.MAX_IDS, payload.quests().size());
        assertEquals(QuestUnlockedPayload.MAX_IDS + 10, payload.total());
    }
}
