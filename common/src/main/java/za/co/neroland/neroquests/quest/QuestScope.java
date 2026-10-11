package za.co.neroland.neroquests.quest;

import java.util.Locale;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

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

    /** Lower-case names, read case-insensitively; an unknown name is a decode error, never a throw (AUD-30). */
    public static final Codec<QuestScope> CODEC = Codec.STRING.comapFlatMap(
            QuestScope::parse, scope -> scope.name().toLowerCase(Locale.ROOT));

    private static DataResult<QuestScope> parse(String name) {
        String normalised = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        for (QuestScope scope : values()) {
            if (scope.name().equals(normalised)) {
                return DataResult.success(scope);
            }
        }
        return DataResult.error(() -> "Unknown quest scope '" + name + "'; expected one of player, server");
    }
}
