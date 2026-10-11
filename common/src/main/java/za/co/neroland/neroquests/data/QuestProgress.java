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
 * as {@code 0}. Any change to a quest's objective list (appending included) changes its fingerprint,
 * and an incomplete row counted against the old list is reset on the next load rather than letting
 * old counters satisfy new objectives.
 *
 * <p>Instances are values — every mutation on {@link QuestProgressState} replaces the snapshot
 * rather than editing it, so a snapshot handed to a caller can never change underneath them.
 *
 * <p><b>Fingerprint (AUD-17).</b> Each row also remembers the fingerprint of the objective list it
 * was counted against. When a reload changes a quest's objectives, an incomplete row whose
 * fingerprint no longer matches is reset (its counters meant something else), while a complete row
 * stays complete without paying again. {@code orphanSince} stamps when the row's quest stopped being
 * defined, so orphan rows can follow retention.
 *
 * @param counters    per-objective counters, by objective index
 * @param completedAt epoch millis at which the quest was completed, or {@code 0} while incomplete
 * @param fingerprint the objective fingerprint the counters belong to, or {@code 0} when unknown
 *                    (rows written before fingerprints existed; stamped on the next reconcile)
 * @param orphanSince epoch millis since when the quest has not been defined, or {@code 0}
 */
public record QuestProgress(List<Integer> counters, long completedAt, int fingerprint, long orphanSince) {

    /** No objective touched, not complete. */
    public static final QuestProgress EMPTY = new QuestProgress(List.of(), 0L);

    public QuestProgress {
        counters = List.copyOf(counters);
    }

    /** A row with no fingerprint and no orphan stamp (the client view, and legacy rows). */
    public QuestProgress(List<Integer> counters, long completedAt) {
        this(counters, completedAt, 0, 0L);
    }

    /** A copy carrying {@code value} as its objective fingerprint. */
    public QuestProgress withFingerprint(int value) {
        return fingerprint == value ? this : new QuestProgress(counters, completedAt, value, orphanSince);
    }

    /** A copy with its orphan stamp set ({@code 0} clears it). */
    public QuestProgress withOrphanSince(long epochMillis) {
        return orphanSince == epochMillis ? this : new QuestProgress(counters, completedAt, fingerprint, epochMillis);
    }

    /** A copy with every counter cleared (completion, fingerprint and orphan stamp kept). */
    public QuestProgress withoutCounters() {
        return counters.isEmpty() ? this : new QuestProgress(List.of(), completedAt, fingerprint, orphanSince);
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
        return new QuestProgress(updated, completedAt, fingerprint, orphanSince);
    }

    /** A copy marked complete at {@code epochMillis}. Returns {@code this} if already so marked. */
    public QuestProgress withCompletedAt(long epochMillis) {
        return completedAt == epochMillis ? this : new QuestProgress(counters, epochMillis, fingerprint, orphanSince);
    }
}
