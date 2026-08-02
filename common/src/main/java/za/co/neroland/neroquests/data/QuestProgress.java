package za.co.neroland.neroquests.data;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable snapshot of one holder's progress on one quest: a counter per objective plus the
 * moment the quest was completed.
 *
 * <p>Counters are indexed by the objective's position in the quest definition's
 * {@code objectives} list — index 0 is the first objective. The list is sparse-by-growth: it is
 * only as long as the highest objective index touched so far, and any index beyond its end reads
 * as {@code 0}. That keeps stored progress valid when a datapack later appends objectives to a
 * quest (existing counters keep their meaning); reordering or removing objectives in a shipped
 * quest changes what the stored counters mean, so packs should append rather than reshuffle.
 *
 * <p>Instances are values — every mutation on {@link QuestProgressState} replaces the snapshot
 * rather than editing it, so a snapshot handed to a caller can never change underneath them.
 *
 * @param counters    per-objective counters, by objective index
 * @param completedAt epoch millis at which the quest was completed, or {@code 0} while incomplete
 */
public record QuestProgress(List<Integer> counters, long completedAt) {

    /** No objective touched, not complete. */
    public static final QuestProgress EMPTY = new QuestProgress(List.of(), 0L);

    public QuestProgress {
        counters = List.copyOf(counters);
    }

    /** Whether this quest has been marked complete. */
    public boolean isComplete() {
        return completedAt > 0L;
    }

    /** The counter for an objective index; {@code 0} for anything never touched. */
    public int counter(int index) {
        return index >= 0 && index < counters.size() ? counters.get(index) : 0;
    }

    /** How many objective counters are stored (indices beyond this read as {@code 0}). */
    public int counterCount() {
        return counters.size();
    }

    /**
     * A copy with one objective's counter replaced, growing the counter list with zeros as needed.
     * Returns {@code this} when the value is already stored.
     */
    public QuestProgress withCounter(int index, int value) {
        if (index < 0 || counter(index) == value) {
            return this;
        }
        List<Integer> updated = new ArrayList<>(Math.max(counters.size(), index + 1));
        updated.addAll(counters);
        while (updated.size() <= index) {
            updated.add(0);
        }
        updated.set(index, value);
        return new QuestProgress(updated, completedAt);
    }

    /** A copy marked complete at {@code epochMillis}. Returns {@code this} if already so marked. */
    public QuestProgress withCompletedAt(long epochMillis) {
        return completedAt == epochMillis ? this : new QuestProgress(counters, epochMillis);
    }
}
