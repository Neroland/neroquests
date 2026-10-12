package za.co.neroland.neroquests.custom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.config.NeroQuestsConfig;

/**
 * J2 (RT-03, RT-04): what custom (editor-made) quests may pay beyond the per-quest caps of {@link CustomLimits}.
 *
 * <ul>
 *   <li><b>Loot tables</b> — a {@code loot_table} reward may only name a table whose id starts with one of the
 *       {@code customLootTablePrefixes} (default {@code minecraft:chests/} and {@code minecraft:gameplay/fishing});
 *       each one also takes a slot of {@code customRewardMaxStacks}.</li>
 *   <li><b>Daily totals</b> — {@code customCurrencyDailyCap} credits per UTC day for the whole world, and
 *       {@code customRewardsPerPlayerDaily} paid custom completions per recipient per UTC day.</li>
 * </ul>
 *
 * <p>Pure apart from reading the config; the {@link DailyBudget} takes the clock from its caller.
 *
 * <p>Privacy (POPIA/GDPR): the per-recipient tally is held <b>in memory only</b> — the player's game UUID and a
 * count, for the current UTC day — never saved, never logged, cleared at the UTC day boundary, on server stop
 * and by an erasure request.
 */
public final class CustomRewardPolicy {

    /** The shipped {@code customLootTablePrefixes}. */
    public static final String DEFAULT_LOOT_PREFIXES = "minecraft:chests/,minecraft:gameplay/fishing";

    /** Most prefixes read from the setting (the rest are ignored). */
    public static final int MAX_PREFIXES = 64;

    /** Milliseconds in a UTC day. */
    private static final long DAY_MILLIS = 86_400_000L;

    /** The running server's daily tallies. */
    public static final DailyBudget BUDGET = new DailyBudget();

    private CustomRewardPolicy() {
    }

    /** The prefixes in a comma-separated setting: trimmed, lower-cased, empty entries dropped, at most 64. */
    public static List<String> parsePrefixes(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split(",")) {
            String prefix = part.trim().toLowerCase(Locale.ROOT);
            if (!prefix.isEmpty() && out.size() < MAX_PREFIXES) {
                out.add(prefix);
            }
        }
        return List.copyOf(out);
    }

    /** Whether {@code table} starts with one of {@code prefixes} (no prefixes: nothing is allowed). */
    public static boolean lootTableAllowed(List<String> prefixes, Identifier table) {
        if (table == null) {
            return false;
        }
        String id = table.toString();
        for (String prefix : prefixes) {
            if (id.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The running server's loot-table prefixes (the defaults if the config cannot be read, e.g. in tests). */
    public static List<String> lootPrefixes() {
        try {
            return parsePrefixes(NeroQuestsConfig.CUSTOM_LOOT_TABLE_PREFIXES.get());
        } catch (RuntimeException | LinkageError e) {
            return parsePrefixes(DEFAULT_LOOT_PREFIXES);
        }
    }

    /** {@code customCurrencyDailyCap} (2000 if the config cannot be read). */
    public static long currencyDailyCap() {
        try {
            return NeroQuestsConfig.CUSTOM_CURRENCY_DAILY_CAP.get().longValue();
        } catch (RuntimeException | LinkageError e) {
            return 2000L;
        }
    }

    /** {@code customRewardsPerPlayerDaily} (20 if the config cannot be read). */
    public static int perPlayerDaily() {
        try {
            return NeroQuestsConfig.CUSTOM_REWARDS_PER_PLAYER_DAILY.get().intValue();
        } catch (RuntimeException | LinkageError e) {
            return 20;
        }
    }

    /** The UTC day number of an epoch-millis instant. */
    public static long utcDay(long epochMillis) {
        return Math.floorDiv(epochMillis, DAY_MILLIS);
    }

    /**
     * The per-UTC-day tallies of custom-quest payouts: world-wide credits and paid completions per recipient.
     * Rolls over by itself at the first call of a new UTC day. Memory only; thread-safe.
     */
    public static final class DailyBudget {

        private long day = Long.MIN_VALUE;
        private long credits;
        private final Map<UUID, Integer> completions = new HashMap<>();

        /**
         * Count one paid custom completion for {@code player}; {@code false} (nothing counted) when they already
         * had {@code limit} today. A {@code limit} of 0 or less means no limit (nothing is counted either).
         */
        public synchronized boolean admitRecipient(UUID player, long now, int limit) {
            roll(now);
            if (limit <= 0 || player == null) {
                return true;
            }
            int used = completions.getOrDefault(player, Integer.valueOf(0)).intValue();
            if (used >= limit) {
                return false;
            }
            completions.put(player, Integer.valueOf(used + 1));
            return true;
        }

        /**
         * Take up to {@code amount} credits from today's world-wide allowance of {@code cap} (0 or less: no cap).
         *
         * @return the credits that may be paid now (0 when the allowance is spent)
         */
        public synchronized long takeCredits(long amount, long now, long cap) {
            roll(now);
            if (amount <= 0L) {
                return 0L;
            }
            if (cap <= 0L) {
                return amount;
            }
            long granted = Math.min(amount, Math.max(0L, cap - credits));
            credits += granted;
            return granted;
        }

        /** Credits paid today so far (for reload-check). */
        public synchronized long creditsToday(long now) {
            roll(now);
            return credits;
        }

        /** Erasure: forget this player's tally. */
        public synchronized void forget(UUID player) {
            completions.remove(player);
        }

        /** Server stop: forget everything. */
        public synchronized void clear() {
            day = Long.MIN_VALUE;
            credits = 0L;
            completions.clear();
        }

        private void roll(long now) {
            long today = utcDay(now);
            if (today != day) {
                day = today;
                credits = 0L;
                completions.clear();
            }
        }
    }
}
