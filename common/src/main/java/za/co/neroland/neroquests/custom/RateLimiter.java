package za.co.neroland.neroquests.custom;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A per-key token bucket (§13 flooding): {@code capacity} actions, refilled continuously at
 * {@code capacity} per {@code windowMillis}. Pure (the caller passes the clock), bounded (idle full
 * buckets are dropped once the map grows), in memory only — keys are player UUIDs or a console marker
 * and are never logged or saved.
 */
public final class RateLimiter {

    private static final int PURGE_ABOVE = 1024;

    private final int capacity;
    private final long windowMillis;
    private final Map<Object, double[]> buckets = new HashMap<>();

    public RateLimiter(int capacity, long windowMillis) {
        if (capacity < 1 || windowMillis < 1) {
            throw new IllegalArgumentException("capacity and window must be positive");
        }
        this.capacity = capacity;
        this.windowMillis = windowMillis;
    }

    /** Take one token for {@code key} at {@code now}; {@code false} (nothing taken) when the bucket is empty. */
    public synchronized boolean tryAcquire(Object key, long now) {
        double[] bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= PURGE_ABOVE) {
                purge(now);
            }
            bucket = new double[] {capacity, now};
            buckets.put(key, bucket);
        }
        refill(bucket, now);
        if (bucket[0] < 1.0) {
            return false;
        }
        bucket[0] -= 1.0;
        return true;
    }

    /** Forget every key (server stop). */
    public synchronized void clear() {
        buckets.clear();
    }

    private void refill(double[] bucket, long now) {
        long elapsed = Math.max(0L, now - (long) bucket[1]);
        bucket[0] = Math.min(capacity, bucket[0] + elapsed * (double) capacity / windowMillis);
        bucket[1] = now;
    }

    private void purge(long now) {
        for (Iterator<double[]> it = buckets.values().iterator(); it.hasNext();) {
            double[] bucket = it.next();
            refill(bucket, now);
            if (bucket[0] >= capacity) {
                it.remove();
            }
        }
    }
}
