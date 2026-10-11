package za.co.neroland.neroquests.quest.engine;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Re-entrancy guard for engine passes (AUD-14). A reward granted inside a pass can publish an event
 * that wants another pass — a gate reward fires Core's {@code GateEvents}, which lands in
 * {@code QuestTriggers.gatesChanged} — and running that pass recursively would evaluate players
 * against state the outer pass is still writing, outside its cascade cap. Instead, work submitted
 * while a pass is running is queued and drained, in submission order, right after the outermost pass
 * ends. Work submitted with an equal key while still queued runs once.
 *
 * <p>Bounded: at most {@value #MAX_QUEUED} tasks wait at once and one drain runs at most
 * {@value #MAX_DRAINED} of them; anything beyond is dropped and counted (a monotonic engine never gets
 * there — this is a guard against a future feedback loop, not a scheduling policy).
 *
 * <p>Pure Java and single-threaded by contract: the engine's server thread only.
 */
final class PassGuard {

    /** Most tasks that may wait for the outer pass at once. */
    static final int MAX_QUEUED = 256;

    /** Most tasks one drain runs before giving up on the rest. */
    static final int MAX_DRAINED = 1024;

    private final Map<Object, Runnable> queued = new LinkedHashMap<>();
    private final Consumer<RuntimeException> onFailure;
    private int depth;
    private boolean draining;
    private int dropped;

    PassGuard(Consumer<RuntimeException> onFailure) {
        this.onFailure = onFailure;
    }

    /** Whether a pass is running right now. */
    boolean inPass() {
        return depth > 0;
    }

    /** Run {@code pass} as a pass; when the outermost pass ends, drain whatever was queued meanwhile. */
    void run(Runnable pass) {
        depth++;
        try {
            pass.run();
        } finally {
            depth--;
            if (depth == 0 && !draining) {
                drain();
            }
        }
    }

    /**
     * Run {@code task} now when no pass is running; otherwise queue it behind the current pass. A
     * task whose key equals one already queued is not queued again.
     */
    void submit(Object key, Runnable task) {
        if (depth == 0) {
            run(task);
            return;
        }
        if (queued.containsKey(key)) {
            return;
        }
        if (queued.size() >= MAX_QUEUED) {
            dropped++;
            return;
        }
        queued.put(key, task);
    }

    /** How many queued tasks the bounds have dropped since this guard was made. */
    int dropped() {
        return dropped;
    }

    /** How many tasks are waiting for the current pass to end. */
    int queuedCount() {
        return queued.size();
    }

    private void drain() {
        draining = true;
        try {
            int ran = 0;
            while (!queued.isEmpty()) {
                if (ran++ >= MAX_DRAINED) {
                    dropped += queued.size();
                    queued.clear();
                    return;
                }
                Iterator<Map.Entry<Object, Runnable>> it = queued.entrySet().iterator();
                Runnable task = it.next().getValue();
                it.remove();
                try {
                    run(task);
                } catch (RuntimeException e) {
                    onFailure.accept(e);
                }
            }
        } finally {
            draining = false;
        }
    }
}
