package za.co.neroland.neroquests.quest;

import java.util.Locale;

import com.mojang.serialization.Codec;

/**
 * Who owns a quest's progress.
 *
 * <ul>
 *   <li>{@link #PLAYER} — every player tracks and completes the quest independently;</li>
 *   <li>{@link #SERVER} — one shared instance for the whole world (the first player to
 *       finish it completes it for everyone).</li>
 * </ul>
 *
 * <p>Mirrors Neroland Core's {@code GateScope} so quest scope and gate scope read the
 * same way in JSON (lower-case names).
 */
public enum QuestScope {
    PLAYER,
    SERVER;

    public static final Codec<QuestScope> CODEC = Codec.STRING.xmap(
            s -> QuestScope.valueOf(s.trim().toUpperCase(Locale.ROOT)),
            scope -> scope.name().toLowerCase(Locale.ROOT));
}
