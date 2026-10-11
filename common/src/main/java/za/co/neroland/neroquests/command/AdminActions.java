package za.co.neroland.neroquests.command;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * Counts the operator mutations {@code /neroquests} performs (AUD-01): one counter per kind for the
 * running JVM, logged as a count only. Never the invoker, never the target player, never the quest —
 * an audit trail of <em>how often</em> progress was edited by hand without becoming a record of who
 * (POPIA/GDPR data minimisation).
 *
 * <p>Server thread only (commands run there).
 */
public final class AdminActions {

    /** The kinds of operator mutation counted. */
    public enum Kind {
        GRANT,
        REVOKE,
        REVOKE_REPAY,
        RESET_PLAYER,
        EXPORT,
        MANUAL_COMPLETE,
        /** One applied custom-quest editor mutation (D2), counted without a log line. */
        EDITOR_MUTATION,
        EDITOR_ALLOW,
        EDITOR_DENY,
        EDITOR_LOCK,
        EDITOR_UNLOCK,
        CUSTOM_EXPORT,
        CUSTOM_IMPORT,
        CUSTOM_DELETE
    }

    private static final Map<Kind, Integer> COUNTS = new EnumMap<>(Kind.class);

    private AdminActions() {
    }

    /**
     * Count one mutation and log the running total for its kind.
     *
     * @return the new count for {@code kind}
     */
    public static synchronized int record(Kind kind) {
        int count = COUNTS.getOrDefault(kind, Integer.valueOf(0)).intValue() + 1;
        COUNTS.put(kind, Integer.valueOf(count));
        NeroQuestsCommon.LOGGER.info("[NeroQuests] Admin action {}: {} this run.",
                kind.name().toLowerCase(Locale.ROOT), Integer.valueOf(count));
        return count;
    }

    /**
     * Count one mutation without a log line — for frequent, low-stakes kinds (an editor drag) whose running
     * total {@code /neroquests reload-check} shows instead.
     *
     * @return the new count for {@code kind}
     */
    public static synchronized int recordQuiet(Kind kind) {
        int count = COUNTS.getOrDefault(kind, Integer.valueOf(0)).intValue() + 1;
        COUNTS.put(kind, Integer.valueOf(count));
        return count;
    }

    /** The count for one kind so far (0 if none). */
    public static synchronized int count(Kind kind) {
        return COUNTS.getOrDefault(kind, Integer.valueOf(0)).intValue();
    }
}
