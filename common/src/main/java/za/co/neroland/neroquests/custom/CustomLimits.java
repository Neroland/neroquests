package za.co.neroland.neroquests.custom;

import za.co.neroland.neroquests.config.NeroQuestsConfig;

/**
 * The bounds on the custom-quest store (D1/D2, §13 flooding): configurable caps plus the fixed per-quest
 * ones. {@link #current()} reads the server config; tests use {@link #DEFAULTS} or their own.
 *
 * @param maxQuests      {@code customQuestMaxTotal}
 * @param maxChapters    {@code customChapterMax}
 * @param maxStacks      {@code customRewardMaxStacks} — item-stack rewards per quest
 * @param maxXp          {@code customRewardMaxXp} — experience per completion
 * @param maxCurrency    {@code customRewardMaxCurrency} — currency per completion
 * @param escrow         {@code customRewardsEscrowed}
 */
public record CustomLimits(int maxQuests, int maxChapters, int maxStacks, int maxXp, long maxCurrency,
                           boolean escrow) {

    /** Objectives per custom quest (fixed). */
    public static final int MAX_OBJECTIVES = 12;

    /** Rewards per custom quest, of every type together (fixed). */
    public static final int MAX_REWARDS = 9;

    /** Smallest and largest count an objective may ask for. */
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 1_000_000;

    /** Node coordinates, in grid units, on both axes. */
    public static final int MIN_COORD = -512;
    public static final int MAX_COORD = 512;

    /** Repeatable cooldown bounds, in seconds (one minute to thirty days). */
    public static final int MIN_COOLDOWN_SECONDS = 60;
    public static final int MAX_COOLDOWN_SECONDS = 30 * 24 * 60 * 60;

    /** Escrowed copies one set-rewards call may deposit. */
    public static final int MAX_ESCROW_COPIES = 64;

    /** The shipped defaults. */
    public static final CustomLimits DEFAULTS = new CustomLimits(500, 20, 9, 500, 1000L, false);

    /** The running server's limits; the defaults if the config cannot be read (e.g. in unit tests). */
    public static CustomLimits current() {
        try {
            return new CustomLimits(
                    NeroQuestsConfig.CUSTOM_QUEST_MAX_TOTAL.get().intValue(),
                    NeroQuestsConfig.CUSTOM_CHAPTER_MAX.get().intValue(),
                    NeroQuestsConfig.CUSTOM_REWARD_MAX_STACKS.get().intValue(),
                    NeroQuestsConfig.CUSTOM_REWARD_MAX_XP.get().intValue(),
                    NeroQuestsConfig.CUSTOM_REWARD_MAX_CURRENCY.get().longValue(),
                    NeroQuestsConfig.CUSTOM_REWARDS_ESCROWED.get().booleanValue());
        } catch (RuntimeException | LinkageError e) {
            return DEFAULTS;
        }
    }

    /** Whether {@code value} is a legal node coordinate. */
    public static boolean coordinateOk(int value) {
        return value >= MIN_COORD && value <= MAX_COORD;
    }

    /** Whether {@code seconds} is a legal repeatable cooldown. */
    public static boolean cooldownOk(int seconds) {
        return seconds >= MIN_COOLDOWN_SECONDS && seconds <= MAX_COOLDOWN_SECONDS;
    }
}
