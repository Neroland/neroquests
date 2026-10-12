package za.co.neroland.neroquests.network;

import za.co.neroland.neroquests.custom.RateLimiter;

/**
 * J2 (RT-02): the cheap limits an editor packet meets <em>before</em> its body is decoded. Pure apart from the
 * clock its caller passes, so the order is unit-tested and benchmarked.
 *
 * <ol>
 *   <li><b>Inbound cap</b> — at most {@value #INBOUND_PER_SECOND} editor packets per player per second are even
 *       queued for the server thread; the rest are dropped at once, unanswered (whatever the player's rights).</li>
 *   <li>Then, on the server thread, the player's editor permission is re-derived and one token of the
 *       per-player editor buckets is spent ({@code CustomQuests.admit}); only an admitted request is decoded.</li>
 *   <li><b>Global slot</b> — at most {@value #GLOBAL_PER_SECOND} admitted mutations per second for the whole
 *       server, so many authorised editors together cannot fill a tick.</li>
 *   <li><b>Refusal replies</b> — a refused request is answered at most {@value #REPLIES_PER_SECOND} times per
 *       player per second; further refusals in that window are dropped silently.</li>
 * </ol>
 *
 * <p>Keys are player UUIDs, held in memory only (never logged or saved) and dropped on server stop.
 */
public final class EditorIntake {

    /** Editor packets one player may have queued per second. */
    public static final int INBOUND_PER_SECOND = 40;

    /** Admitted editor mutations per second, all players together. */
    public static final int GLOBAL_PER_SECOND = 200;

    /** Refusal replies per player per second. */
    public static final int REPLIES_PER_SECOND = 2;

    private static final Object GLOBAL_KEY = new Object();

    private final RateLimiter inbound = new RateLimiter(INBOUND_PER_SECOND, 1_000L);
    private final RateLimiter global = new RateLimiter(GLOBAL_PER_SECOND, 1_000L);
    private final RateLimiter replies = new RateLimiter(REPLIES_PER_SECOND, 1_000L);

    /** May this player's packet be queued at all? {@code false}: drop it, unanswered. */
    public boolean inbound(Object key, long now) {
        return key != null && inbound.tryAcquire(key, now);
    }

    /** One server-wide mutation slot for an admitted request; {@code false}: refuse it as rate-limited. */
    public boolean globalSlot(long now) {
        return global.tryAcquire(GLOBAL_KEY, now);
    }

    /** Should this refusal be answered? {@code false}: drop the reply. */
    public boolean replyToRefusal(Object key, long now) {
        return key != null && replies.tryAcquire(key, now);
    }

    /** Server stop: forget every key. */
    public void clear() {
        inbound.clear();
        global.clear();
        replies.clear();
    }
}
