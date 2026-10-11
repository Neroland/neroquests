package za.co.neroland.neroquests.quest.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** AUD-14: engine passes never nest; work submitted mid-pass drains after the outermost pass. */
class PassGuardTest {

    private final List<RuntimeException> failures = new ArrayList<>();
    private final PassGuard guard = new PassGuard(failures::add);

    @Test
    void outsideAPassWorkRunsAtOnce() {
        List<String> log = new ArrayList<>();
        guard.submit("k", () -> log.add("ran"));
        assertEquals(List.of("ran"), log);
        assertFalse(guard.inPass());
    }

    @Test
    void reentrantWorkWaitsForTheOuterPassAndNeverNests() {
        List<String> log = new ArrayList<>();
        int[] maxDepthSeen = new int[1];
        guard.run(() -> {
            log.add("outer-start");
            // A gate reward inside the pass fires GateEvents -> gatesChanged -> another evaluation.
            guard.submit("player-a", () -> {
                log.add("deferred-a");
                maxDepthSeen[0] = guard.inPass() ? 1 : 0;
                // ...whose own gate reward queues yet another one: still behind, still not nested.
                guard.submit("player-b", () -> log.add("deferred-b"));
                log.add("deferred-a-end");
            });
            log.add("outer-end");
        });
        assertEquals(List.of("outer-start", "outer-end", "deferred-a", "deferred-a-end", "deferred-b"), log);
        assertEquals(1, maxDepthSeen[0], "a drained task runs as a pass of its own");
        assertFalse(guard.inPass());
        assertEquals(0, guard.queuedCount());
    }

    @Test
    void equalKeysQueuedTogetherRunOnce() {
        int[] runs = new int[1];
        guard.run(() -> {
            guard.submit("everyone", () -> runs[0]++);
            guard.submit("everyone", () -> runs[0]++);
            guard.submit(new Object(), () -> runs[0]++);
        });
        assertEquals(2, runs[0]);
    }

    @Test
    void aFailingTaskDoesNotStopTheRest() {
        List<String> log = new ArrayList<>();
        guard.run(() -> {
            guard.submit(1, () -> {
                throw new IllegalStateException("boom");
            });
            guard.submit(2, () -> log.add("second"));
        });
        assertEquals(List.of("second"), log);
        assertEquals(1, failures.size());
    }

    @Test
    void aFeedbackLoopIsBoundedAndCounted() {
        Runnable[] loop = new Runnable[1];
        int[] runs = new int[1];
        loop[0] = () -> {
            runs[0]++;
            guard.submit(new Object(), loop[0]);
        };
        guard.run(() -> guard.submit(new Object(), loop[0]));
        assertEquals(PassGuard.MAX_DRAINED, runs[0]);
        assertTrue(guard.dropped() > 0);
        assertEquals(0, guard.queuedCount());
    }
}
