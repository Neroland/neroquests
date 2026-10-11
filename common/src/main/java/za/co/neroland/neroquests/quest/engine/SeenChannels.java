package za.co.neroland.neroquests.quest.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.Identifier;

/**
 * The threshold channels seen on Neroland Core's {@code ThresholdEvents} bus during this server
 * session (AUD-03 / C5): a bounded, insertion-ordered set of at most {@value #MAX_CHANNELS} channel
 * ids, the oldest forgotten first. Channel ids only — a crossing never names a player, and nothing
 * else of it is kept.
 *
 * <p>A {@code neroquests:} (spine) {@code custom_event} objective whose channel namespace is not a
 * loaded mod is still "present" once its channel has actually been seen, because then something here
 * evidently publishes it.
 *
 * <p>Thread-safe: crossings may be published from another mod's thread.
 */
public final class SeenChannels {

    /** Upper bound on remembered channels. */
    public static final int MAX_CHANNELS = 256;

    private static final Map<Identifier, Boolean> SEEN = new LinkedHashMap<>(32, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Identifier, Boolean> eldest) {
            return size() > MAX_CHANNELS;
        }
    };

    private SeenChannels() {
    }

    /** Remember that a crossing on {@code channel} was published this session. */
    public static synchronized void record(Identifier channel) {
        if (channel != null) {
            SEEN.put(channel, Boolean.TRUE);
        }
    }

    /** Whether a crossing on {@code channel} has been seen this session. */
    public static synchronized boolean seen(Identifier channel) {
        return channel != null && SEEN.containsKey(channel);
    }

    /** The channels seen this session, oldest first (a copy, at most {@value #MAX_CHANNELS}). */
    public static synchronized Set<Identifier> seenChannels() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(SEEN.keySet()));
    }

    /**
     * The channels seen this session as a sorted, immutable list of ids (C5) — what the editor's
     * {@code custom_event} picker offers. Channel ids only; at most {@value #MAX_CHANNELS}.
     */
    public static synchronized List<Identifier> sortedIds() {
        List<Identifier> out = new ArrayList<>(SEEN.keySet());
        out.sort(Comparator.comparing(Identifier::toString));
        return List.copyOf(out);
    }

    /** Forget everything (a new server session). */
    public static synchronized void reset() {
        SEEN.clear();
    }
}
