package za.co.neroland.neroquests.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.EditorPermissions;
import za.co.neroland.neroquests.quest.EditorPermissions.Access;
import za.co.neroland.neroquests.quest.QuestSource;
import za.co.neroland.neroquests.quest.QuestSources;

/** J1 (§13 authorisation, immutability, input rate limits): the pure front door of every editor request. */
class EditGateTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Identifier CUSTOM = Identifier.parse("custom:abcd2345/efgh6723");
    private static final Identifier SPINE = Identifier.parse("neroquests:groundwork/what_this_book_is");
    private static final Identifier PACK = Identifier.parse("nerotech:industrial_record/first_machine");

    /** creative, op level, allow-listed, locked — as re-derived on the server (spectator/dead covered elsewhere). */
    private static boolean authorised(boolean creative, int op, boolean listed, boolean locked) {
        return EditorPermissions.decide(new Access(false, true, creative, op, listed, locked, true, 2));
    }

    private static Optional<String> request(boolean authorised, RateLimiter burst, RateLimiter minute, long now,
                                            Identifier subject) {
        return EditGate.refusal(false, authorised, PLAYER, now, burst, minute, subject);
    }

    @Test
    void losingCreativeMidEditRefusesTheNextRequest() {
        RateLimiter burst = EditGate.editorBurst();
        RateLimiter minute = EditGate.editorMinute();
        assertEquals(Optional.empty(), request(authorised(true, 0, false, false), burst, minute, 0L, CUSTOM));
        // Game mode switched to survival on the server between two packets: re-derived, refused.
        assertEquals(Optional.of(EditResult.DENIED),
                request(authorised(false, 0, false, false), burst, minute, 10L, CUSTOM));
        // A refused request spends no rate-limit token: back in creative, a full burst is still there.
        for (int i = 0; i < EditGate.EDITOR_BURST - 1; i++) {
            assertEquals(Optional.empty(), request(true, burst, minute, 20L, CUSTOM));
        }
    }

    @Test
    void theClientIsDroppedToViewModeWhenTheFlagFlips() {
        assertTrue(EditorSync.flipped(Boolean.TRUE, false), "a flip triggers a re-send");
        assertTrue(EditorSync.flipped(Boolean.FALSE, true));
        assertFalse(EditorSync.flipped(Boolean.TRUE, true), "no flip, no re-send");
        assertFalse(EditorSync.flipped(null, false), "never sent: the first sync carries it");
        assertFalse(EditGate.editModeAfterSync(true, false), "edit mode ends when can_author goes false");
        assertTrue(EditGate.editModeAfterSync(true, true));
        assertFalse(EditGate.editModeAfterSync(false, true), "a sync never switches edit mode on");
    }

    @Test
    void editorLockBeatsEverythingButLevelFour() {
        assertFalse(authorised(true, 0, false, true), "creative");
        assertFalse(authorised(false, 0, true, true), "allow-list");
        assertFalse(authorised(true, 3, true, true), "level 3 + creative + allow-list");
        assertTrue(authorised(false, 4, false, true), "level 4");
        assertEquals(Optional.of(EditResult.DENIED), request(authorised(true, 3, true, true),
                EditGate.editorBurst(), EditGate.editorMinute(), 0L, CUSTOM));
    }

    @Test
    void spineAndPackIdsAreRefusedOnEveryPathIncludingAdmin() {
        for (boolean admin : new boolean[] {false, true}) {
            assertEquals(Optional.of(QuestSources.LOCKED_SPINE), EditGate.refusal(admin, true, PLAYER, 0L,
                    EditGate.editorBurst(), EditGate.editorMinute(), SPINE));
            assertEquals(Optional.of(QuestSources.LOCKED_PACK), EditGate.refusal(admin, true, PLAYER, 0L,
                    EditGate.editorBurst(), EditGate.editorMinute(), PACK));
            assertEquals(Optional.empty(), EditGate.refusal(admin, true, PLAYER, 0L,
                    EditGate.editorBurst(), EditGate.editorMinute(), CUSTOM));
        }
        assertEquals(Optional.of(QuestSources.LOCKED_SPINE), EditGate.refusal(false, true, PLAYER, 0L,
                EditGate.editorBurst(), EditGate.editorMinute(), Identifier.parse("neroquests:groundwork")),
                "a spine chapter as the target of create_quest");
    }

    @Test
    void editorRateLimitIsTwentyPerSecondBurstAndTwoHundredPerMinute() {
        RateLimiter burst = EditGate.editorBurst();
        RateLimiter minute = EditGate.editorMinute();
        for (int i = 0; i < 20; i++) {
            assertEquals(Optional.empty(), request(true, burst, minute, 0L, CUSTOM), "burst " + i);
        }
        assertEquals(Optional.of(EditResult.RATE_LIMITED), request(true, burst, minute, 0L, CUSTOM));
        // A client hammering at 50 requests/s for ten minutes: sustained throughput is the per-minute rate.
        int accepted = 0;
        for (long t = 1_000L; t <= 601_000L; t += 20L) {
            if (request(true, burst, minute, t, CUSTOM).isEmpty()) {
                accepted++;
            }
        }
        // At most a full minute bucket (200) plus 200 a minute for the ~600 s that follow.
        assertTrue(accepted <= 200 + 2_004, "accepted " + accepted);
        assertTrue(accepted >= 2_000, "accepted " + accepted);
        RateLimiter other = EditGate.editorBurst();
        assertTrue(other.tryAcquire(UUID.randomUUID(), 0L), "per player");
    }

    @Test
    void manualCompletionsAreTenAMinuteAndSpineOrPackNeedAnOperator() {
        RateLimiter manual = EditGate.manualLimiter();
        for (int i = 0; i < EditGate.MANUAL_PER_MINUTE; i++) {
            assertTrue(manual.tryAcquire(PLAYER, 0L));
        }
        assertFalse(manual.tryAcquire(PLAYER, 0L));
        assertTrue(manual.tryAcquire(PLAYER, 6_000L), "one back every six seconds");
        assertTrue(EditGate.mayTickManual(QuestSource.CUSTOM, true, false));
        assertFalse(EditGate.mayTickManual(QuestSource.SPINE, true, false), "an author never changes spine progress");
        assertFalse(EditGate.mayTickManual(QuestSource.PACK, true, false), "nor pack progress");
        assertTrue(EditGate.mayTickManual(QuestSource.PACK, false, true), "trusted operator (counted)");
        assertFalse(EditGate.mayTickManual(QuestSource.CUSTOM, false, false));
    }

    @Test
    void nodeCoordinatesAndCooldownsAreBounded() {
        assertTrue(CustomLimits.coordinateOk(CustomLimits.MIN_COORD));
        assertTrue(CustomLimits.coordinateOk(CustomLimits.MAX_COORD));
        assertFalse(CustomLimits.coordinateOk(CustomLimits.MIN_COORD - 1));
        assertFalse(CustomLimits.coordinateOk(CustomLimits.MAX_COORD + 1));
        assertFalse(CustomLimits.coordinateOk(Integer.MIN_VALUE));
        assertTrue(CustomLimits.cooldownOk(60));
        assertFalse(CustomLimits.cooldownOk(59));
        assertFalse(CustomLimits.cooldownOk(CustomLimits.MAX_COOLDOWN_SECONDS + 1));
    }
}
