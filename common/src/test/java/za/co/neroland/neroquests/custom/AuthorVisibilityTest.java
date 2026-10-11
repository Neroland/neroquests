package za.co.neroland.neroquests.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;

/** J1 (Stage D gap): an author can only name quests they can see; a hidden id answers like an unknown one. */
class AuthorVisibilityTest {

    private static final Identifier GATE = Identifier.parse("nerolandcore:reached_orbit");
    private static final Identifier HIDDEN = Identifier.parse("neroquests:space_race/deep_space");
    private static final Identifier OPEN = Identifier.parse("neroquests:groundwork/what_this_book_is");
    private static final Identifier MISSING = Identifier.parse("neroquests:no/such_quest");
    private static final Identifier STORED = Identifier.parse("custom:abcd2345/efgh6723");

    private static Quest quest(Identifier id, Optional<Identifier> gate) {
        return new Quest(id, "t", "", Quest.DEFAULT_ICON, List.of(), List.of(), List.of(), QuestScope.PLAYER, gate);
    }

    private static final Map<Identifier, Quest> LOADED = Map.of(
            HIDDEN, quest(HIDDEN, Optional.of(GATE)),
            OPEN, quest(OPEN, Optional.empty()));

    private static boolean known(Identifier id, Predicate<Identifier> gates) {
        return AuthorVisibility.known(id, LOADED, STORED::equals, gates);
    }

    @Test
    void aQuestBehindAClosedGateIsIndistinguishableFromAnUnknownId() {
        Predicate<Identifier> closed = Set.<Identifier>of()::contains;
        assertEquals(known(MISSING, closed), known(HIDDEN, closed));
        assertFalse(known(HIDDEN, closed));
        assertTrue(known(OPEN, closed));
    }

    @Test
    void itBecomesNameableOnceTheGateOpens() {
        assertTrue(known(HIDDEN, Set.of(GATE)::contains));
    }

    @Test
    void storeQuestsAndTheConsole() {
        assertTrue(known(STORED, id -> false), "custom quests in the previewed store are always visible");
        assertTrue(known(HIDDEN, AuthorVisibility.EVERYTHING), "an admin command without a player sees all");
        assertFalse(known(MISSING, AuthorVisibility.EVERYTHING));
        assertFalse(known(null, AuthorVisibility.EVERYTHING));
    }
}
