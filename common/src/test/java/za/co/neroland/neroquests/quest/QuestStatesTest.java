package za.co.neroland.neroquests.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.data.QuestProgress;

/** AUD-18: one quest-state rule — prerequisites resolve by their own scope; locked beats in progress. */
class QuestStatesTest {

    private static final Identifier PLAYER_A = id("player_a");
    private static final Identifier WORLD_W = id("world_w");
    private static final Identifier GATE = Identifier.fromNamespaceAndPath("nerolandcore", "first_colony");

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("neroquests", "test/" + path);
    }

    private static Quest quest(Identifier id, QuestScope scope, List<Identifier> prerequisites,
                               Optional<Identifier> gate) {
        String json = "{\"title\":\"t\",\"objectives\":[{\"type\":\"neroquests:quest_complete\","
                + "\"quest\":\"neroquests:a\"},{\"type\":\"neroquests:quest_complete\",\"quest\":\"neroquests:b\"}]}";
        Quest.Data data = Quest.DATA_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        return new Quest(id, data.title(), data.description(), data.icon(), prerequisites, data.objectives(),
                data.rewards(), scope, gate);
    }

    private static Map<Identifier, Quest> definitions(Quest... quests) {
        Map<Identifier, Quest> out = new LinkedHashMap<>();
        for (Quest quest : quests) {
            out.put(quest.id(), quest);
        }
        return out;
    }

    @Test
    void eachPrerequisiteResolvesByItsOwnScope() {
        Quest a = quest(PLAYER_A, QuestScope.PLAYER, List.of(), Optional.empty());
        Quest w = quest(WORLD_W, QuestScope.SERVER, List.of(), Optional.empty());
        Quest playerQuest = quest(id("p"), QuestScope.PLAYER, List.of(PLAYER_A, WORLD_W), Optional.empty());
        Quest serverQuest = quest(id("s"), QuestScope.SERVER, List.of(PLAYER_A, WORLD_W), Optional.empty());
        Map<Identifier, Quest> defs = definitions(a, w, playerQuest, serverQuest);

        // Both prerequisites met, each in its own section: unlocked for both dependents.
        assertTrue(QuestStates.prerequisitesMet(playerQuest, defs, Set.of(PLAYER_A), Set.of(WORLD_W)));
        assertTrue(QuestStates.prerequisitesMet(serverQuest, defs, Set.of(PLAYER_A), Set.of(WORLD_W)));
        // The world quest's id in the player's set (or vice versa) does not count: wrong section.
        assertFalse(QuestStates.prerequisitesMet(playerQuest, defs, Set.of(PLAYER_A, WORLD_W), Set.of()));
        assertFalse(QuestStates.prerequisitesMet(serverQuest, defs, Set.of(), Set.of(PLAYER_A, WORLD_W)));
    }

    @Test
    void unknownPrerequisiteCountsInEitherSet() {
        Identifier hidden = id("hidden");
        Quest dependent = quest(id("d"), QuestScope.PLAYER, List.of(hidden), Optional.empty());
        Map<Identifier, Quest> defs = definitions(dependent); // the client was never sent 'hidden'
        assertTrue(QuestStates.prerequisitesMet(dependent, defs, Set.of(hidden), Set.of()));
        assertTrue(QuestStates.prerequisitesMet(dependent, defs, Set.of(), Set.of(hidden)));
        assertFalse(QuestStates.prerequisitesMet(dependent, defs, Set.of(), Set.of()));
    }

    @Test
    void stateOrderIsCompletedLockedInProgressAvailable() {
        Quest a = quest(PLAYER_A, QuestScope.PLAYER, List.of(), Optional.empty());
        Quest dependent = quest(id("dep"), QuestScope.PLAYER, List.of(PLAYER_A), Optional.empty());
        Map<Identifier, Quest> defs = definitions(a, dependent);
        QuestProgress started = new QuestProgress(List.of(Integer.valueOf(0), Integer.valueOf(1)), 0L);
        QuestProgress done = new QuestProgress(List.of(Integer.valueOf(1), Integer.valueOf(1)), 5L);

        assertEquals(QuestStates.State.COMPLETED,
                QuestStates.stateOf(dependent, defs, Set.of(), Set.of(), done, Set.of()));
        // Started but its prerequisite is not (or no longer) complete: locked, not in progress.
        assertEquals(QuestStates.State.LOCKED,
                QuestStates.stateOf(dependent, defs, Set.of(), Set.of(), started, Set.of()));
        assertEquals(QuestStates.State.IN_PROGRESS,
                QuestStates.stateOf(dependent, defs, Set.of(PLAYER_A), Set.of(), started, Set.of()));
        assertEquals(QuestStates.State.AVAILABLE,
                QuestStates.stateOf(dependent, defs, Set.of(PLAYER_A), Set.of(), QuestProgress.EMPTY, Set.of()));
    }

    @Test
    void closedVisibleGateIsLockedAndNotAvailable() {
        Quest gated = quest(id("gated"), QuestScope.PLAYER, List.of(), Optional.of(GATE));
        Map<Identifier, Quest> defs = definitions(gated);
        assertFalse(QuestStates.isAvailable(gated, defs, Set.of(), Set.of(), Set.of()));
        assertTrue(QuestStates.isAvailable(gated, defs, Set.of(), Set.of(), Set.of(GATE)));
        assertEquals(QuestStates.State.LOCKED,
                QuestStates.stateOf(gated, defs, Set.of(), Set.of(), QuestProgress.EMPTY, Set.of()));
        // The predicate form (the client asks Core's mirror) agrees with the set form.
        assertTrue(QuestStates.isAvailable(gated, defs, Set.of(), Set.of(), GATE::equals));
    }
}
