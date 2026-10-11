package za.co.neroland.neroquests.custom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroquests.quest.EditorPermissions;
import za.co.neroland.neroquests.quest.EditorPermissions.Access;

/** D4: the authorisation matrix (§3.3) as pure logic. */
class EditorPermissionsTest {

    /** spectator, alive, creative, op level, allow-listed, locked; defaults creativeGrants=true, minOp=2. */
    private static boolean decide(boolean spectator, boolean alive, boolean creative, int op, boolean listed,
                                  boolean locked) {
        return EditorPermissions.decide(new Access(spectator, alive, creative, op, listed, locked, true, 2));
    }

    @Test
    void creativeAuthors() {
        assertTrue(decide(false, true, true, 0, false, false));
    }

    @Test
    void operatorAtMinLevelAuthorsInSurvival() {
        assertTrue(decide(false, true, false, 2, false, false));
        assertFalse(decide(false, true, false, 1, false, false));
    }

    @Test
    void allowListAuthorsInSurvival() {
        assertTrue(decide(false, true, false, 0, true, false));
    }

    @Test
    void nobodyElse() {
        assertFalse(decide(false, true, false, 0, false, false));
    }

    @Test
    void spectatorsAndTheDeadNever() {
        assertFalse(decide(true, true, true, 4, true, false));
        assertFalse(decide(false, false, true, 4, true, false));
    }

    @Test
    void lockBeatsEverythingButLevelFour() {
        assertFalse(decide(false, true, true, 0, false, true));
        assertFalse(decide(false, true, false, 0, true, true));
        assertFalse(decide(false, true, false, 3, false, true));
        assertTrue(decide(false, true, false, 4, false, true));
    }

    @Test
    void creativeRouteCanBeSwitchedOff() {
        assertFalse(EditorPermissions.decide(new Access(false, true, true, 0, false, false, false, 2)));
        assertTrue(EditorPermissions.decide(new Access(false, true, true, 0, true, false, false, 2)));
    }

    @Test
    void minOpLevelIsHonoured() {
        assertFalse(EditorPermissions.decide(new Access(false, true, false, 3, false, false, false, 4)));
        assertTrue(EditorPermissions.decide(new Access(false, true, false, 4, false, false, false, 4)));
    }
}
