package za.co.neroland.neroquests.quest.reward;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * Once-per-server-run logging for rewards that degrade instead of failing — a missing item id, an
 * economy or reputation provider that no sibling mod has registered, gate writes turned off in the
 * config.
 *
 * <p>Rewards fire on every completion, so an unconditional log line would repeat for every player
 * who finishes the same quest. Each distinct situation is therefore keyed and reported once, in the
 * spirit of {@code MissingContent} on the objective side.
 *
 * <p><b>POPIA/GDPR:</b> keys and messages carry resource ids and amounts only — never player names
 * or UUIDs.
 */
public final class RewardLog {

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private RewardLog() {
    }

    /** Log {@code format} at INFO the first time {@code key} is seen. */
    public static void infoOnce(String key, String format, Object... args) {
        if (SEEN.add(key)) {
            NeroQuestsCommon.LOGGER.info(format, args);
        }
    }

    /** Log {@code format} at WARN the first time {@code key} is seen. */
    public static void warnOnce(String key, String format, Object... args) {
        if (SEEN.add(key)) {
            NeroQuestsCommon.LOGGER.warn(format, args);
        }
    }

    /** Log {@code format} at DEBUG the first time {@code key} is seen. */
    public static void debugOnce(String key, String format, Object... args) {
        if (SEEN.add(key)) {
            NeroQuestsCommon.LOGGER.debug(format, args);
        }
    }

    /** Forget every reported situation, so a fresh world/datapack load reports its own gaps. */
    public static void reset() {
        SEEN.clear();
    }
}
