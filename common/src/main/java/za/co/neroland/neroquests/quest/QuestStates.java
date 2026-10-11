package za.co.neroland.neroquests.quest;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.data.QuestProgress;

/**
 * The one quest-state rule (AUD-18), shared by the engine (what may be worked on), the quest book
 * (what to draw) and the NeroLink snapshot (what the app shows), so the three can never disagree.
 *
 * <h2>Prerequisites resolve by their own scope</h2>
 *
 * <p>A prerequisite is met when it is complete <em>in the scope that owns it</em>: a
 * {@code scope: player} prerequisite must be in the holder's own completed set, a
 * {@code scope: server} prerequisite in the world's. This is independent of the dependent quest's
 * own scope — a player quest may wait on a world milestone, and a server quest on something the
 * evaluating player did. A prerequisite whose definition the caller does not have (the client is
 * never sent a hidden quest, AUD-19) is met if it is complete in either set; both sets come from the
 * server, so that never over-reports.
 *
 * <h2>Check order</h2>
 *
 * <p>Completed, then locked (hidden or prerequisites unmet), then in progress (any counter above
 * zero), then available. A started quest whose prerequisite was revoked therefore reads as locked
 * everywhere.
 *
 * <p>Pure: callers hand over the definitions, both completed sets, the progress row and the open
 * gate set. No server, player or client plumbing.
 */
public final class QuestStates {

    /** The four states a holder sees a quest in. Declared in check order after COMPLETED. */
    public enum State {
        COMPLETED,
        LOCKED,
        IN_PROGRESS,
        AVAILABLE
    }

    private QuestStates() {
    }

    /** Whether one prerequisite is complete in the scope that owns it. */
    public static boolean prerequisiteMet(Identifier prerequisite, Map<Identifier, Quest> definitions,
                                          Set<Identifier> playerCompleted, Set<Identifier> serverCompleted) {
        Quest owner = definitions.get(prerequisite);
        if (owner == null) {
            return playerCompleted.contains(prerequisite) || serverCompleted.contains(prerequisite);
        }
        return owner.scope() == QuestScope.SERVER
                ? serverCompleted.contains(prerequisite)
                : playerCompleted.contains(prerequisite);
    }

    /** Whether every prerequisite of {@code quest} is met, each resolved by its own scope. */
    public static boolean prerequisitesMet(Quest quest, Map<Identifier, Quest> definitions,
                                           Set<Identifier> playerCompleted, Set<Identifier> serverCompleted) {
        for (Identifier prerequisite : quest.prerequisites()) {
            if (!prerequisiteMet(prerequisite, definitions, playerCompleted, serverCompleted)) {
                return false;
            }
        }
        return true;
    }

    /** Visible (its {@code visible_gate} is open) and unlocked: the holder may work on it now. */
    public static boolean isAvailable(Quest quest, Map<Identifier, Quest> definitions,
                                      Set<Identifier> playerCompleted, Set<Identifier> serverCompleted,
                                      Set<Identifier> openGates) {
        return isAvailable(quest, definitions, playerCompleted, serverCompleted, openGates::contains);
    }

    /** {@link #isAvailable(Quest, Map, Set, Set, Set)} with the gate check as a predicate (the client asks Core's mirror). */
    public static boolean isAvailable(Quest quest, Map<Identifier, Quest> definitions,
                                      Set<Identifier> playerCompleted, Set<Identifier> serverCompleted,
                                      Predicate<Identifier> gateOpen) {
        // B3: a quest of a pack whose mod is not installed is shown (badge) but never workable.
        return !quest.modAbsent()
                && isVisible(quest, gateOpen)
                && prerequisitesMet(quest, definitions, playerCompleted, serverCompleted);
    }

    /** Whether {@code quest} may be shown: no {@code visible_gate}, or that gate is open. */
    public static boolean isVisible(Quest quest, Predicate<Identifier> gateOpen) {
        return quest.visibleGate().isEmpty() || gateOpen.test(quest.visibleGate().get());
    }

    /**
     * The state of {@code quest} for one holder.
     *
     * @param progress the row from the section that owns the quest (player or world)
     */
    public static State stateOf(Quest quest, Map<Identifier, Quest> definitions,
                                Set<Identifier> playerCompleted, Set<Identifier> serverCompleted,
                                QuestProgress progress, Set<Identifier> openGates) {
        return stateOf(quest, definitions, playerCompleted, serverCompleted, progress, openGates::contains);
    }

    /** {@link #stateOf(Quest, Map, Set, Set, QuestProgress, Set)} with the gate check as a predicate. */
    public static State stateOf(Quest quest, Map<Identifier, Quest> definitions,
                                Set<Identifier> playerCompleted, Set<Identifier> serverCompleted,
                                QuestProgress progress, Predicate<Identifier> gateOpen) {
        if (progress.isComplete()) {
            return State.COMPLETED;
        }
        if (!isAvailable(quest, definitions, playerCompleted, serverCompleted, gateOpen)) {
            return State.LOCKED;
        }
        for (int index = 0; index < quest.objectives().size(); index++) {
            if (progress.counter(index) > 0) {
                return State.IN_PROGRESS;
            }
        }
        return State.AVAILABLE;
    }
}
