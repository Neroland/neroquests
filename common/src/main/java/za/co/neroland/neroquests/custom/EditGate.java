package za.co.neroland.neroquests.custom;

import java.util.Optional;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.QuestSource;
import za.co.neroland.neroquests.quest.QuestSources;

/**
 * The pure front door of every custom-quest mutation and every {@code manual} completion (§13, J1). It holds
 * the decision order and the rate-limit sizes in one place so they are unit-tested; the callers
 * ({@link CustomQuests} for editor packets, {@code /neroquests manual complete}) feed it values they have just
 * re-derived on the server — the player's real game mode, permission level, the allow-list and the lock via
 * {@link za.co.neroland.neroquests.quest.EditorPermissions}. Nothing a client sends is an input here.
 *
 * <p>Order: authorisation, then the rate limit (a refused player never spends a token), then immutability of
 * the subject id. Admin commands have passed their own trusted-source check and skip the first two steps,
 * never the third: no path may edit a spine or pack id.
 */
public final class EditGate {

    /** Editor requests per player: burst size (per second). */
    public static final int EDITOR_BURST = 20;

    /** Editor requests per player per minute. */
    public static final int EDITOR_PER_MINUTE = 200;

    /** {@code manual} completions per invoker per minute. */
    public static final int MANUAL_PER_MINUTE = 10;

    private EditGate() {
    }

    /** A fresh burst limiter (20 a second). */
    public static RateLimiter editorBurst() {
        return new RateLimiter(EDITOR_BURST, 1_000L);
    }

    /** A fresh per-minute limiter (200 a minute). */
    public static RateLimiter editorMinute() {
        return new RateLimiter(EDITOR_PER_MINUTE, 60_000L);
    }

    /** A fresh {@code manual}-completion limiter (10 a minute). */
    public static RateLimiter manualLimiter() {
        return new RateLimiter(MANUAL_PER_MINUTE, 60_000L);
    }

    /**
     * Why an editor mutation must be refused, as an {@link EditResult} key, or empty when it may proceed.
     *
     * @param admin      an admin command that has passed its own trusted-source check
     * @param authorised {@code EditorPermissions.canAuthor(player)} evaluated <em>now</em> (ignored for admin)
     * @param rateKey    the player's key for the limiters (ignored for admin)
     * @param now        epoch millis
     * @param subject    the quest or chapter id the request names, or null for a create without a subject
     */
    public static Optional<String> refusal(boolean admin, boolean authorised, Object rateKey, long now,
                                           RateLimiter burst, RateLimiter minute, Identifier subject) {
        if (!admin) {
            if (!authorised || rateKey == null) {
                return Optional.of(EditResult.DENIED);
            }
            // Both buckets are asked; a refusal by the first does not spend the second.
            if (!burst.tryAcquire(rateKey, now) || !minute.tryAcquire(rateKey, now)) {
                return Optional.of(EditResult.RATE_LIMITED);
            }
        }
        return subject == null ? Optional.empty() : QuestSources.lockKey(subject);
    }

    /**
     * Who may tick a {@code manual} objective of a quest from {@code source}: an editor-authorised player
     * (re-derived now) only on custom quests; spine and pack progress changes by hand stay with trusted
     * operators (level 3 or the console), like grant/revoke/reset, and are counted.
     */
    public static boolean mayTickManual(QuestSource source, boolean authorised, boolean trustedAdmin) {
        if (trustedAdmin) {
            return true;
        }
        return source == QuestSource.CUSTOM && authorised;
    }

    /**
     * The client's edit mode after a definitions sync: edit mode survives only while the server still says
     * {@code can_author}. The server re-sends that flag whenever it flips ({@link EditorSync#flipped}), so a
     * player who loses Creative mid-edit is dropped to view mode on the next 1 Hz sweep — and their next
     * request is refused regardless, by {@link #refusal}.
     */
    public static boolean editModeAfterSync(boolean editMode, boolean canAuthor) {
        return editMode && canAuthor;
    }
}
