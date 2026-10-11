package za.co.neroland.neroquests.quest.engine;

/**
 * One economy happening reported by an optional economy mod, already reduced to the three things a
 * {@code neroquests:economy} objective matches on. Built by the economy compat bridge and handed to
 * {@link QuestTriggers#economyActivity}; never stored.
 *
 * <p>Privacy (POPIA/GDPR): there is deliberately no counterparty, price or balance here — only what
 * happened, to which item, and how much. The player it belongs to travels separately and is never
 * logged.
 *
 * @param kind   what happened
 * @param itemId the item's id as a string ({@code namespace:path}), or {@code null} for credits earned
 * @param amount units sold/bought, or credits earned; always at least 1
 */
public record EconomyActivity(Kind kind, String itemId, long amount) {

    /** What the player did. */
    public enum Kind {
        /** The player's side of a settled sale. */
        SOLD,
        /** The player's side of a settled purchase. */
        BOUGHT,
        /** Credits paid to the player by another mod's budgeted payout (never NeroQuests' own). */
        EARNED
    }
}
