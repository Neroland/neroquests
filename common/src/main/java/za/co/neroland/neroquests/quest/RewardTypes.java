package za.co.neroland.neroquests.quest;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.MapCodec;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.reward.UnknownReward;

/**
 * The static registry of reward kinds — the reward-side twin of {@link ObjectiveTypes}, and
 * a plain {@link ConcurrentHashMap} for the same reasons (see that class).
 *
 * <p>Populate it from {@link QuestTypes#init()} during common init, before
 * {@link QuestDefinitions} reads a single file.
 */
public final class RewardTypes {

    private static final Map<Identifier, RewardType<?>> TYPES = new ConcurrentHashMap<>();

    private RewardTypes() {
    }

    /**
     * Register a reward kind. A second registration for the same id replaces the first and
     * logs a warning (matching Core's registry conventions).
     *
     * @param id    the type id written as the reward's {@code type} field
     * @param codec the codec for the reward body (without the {@code type} field)
     * @return the registered type, for the caller to keep a handle on
     */
    public static <T extends RewardSpec> RewardType<T> register(Identifier id, MapCodec<T> codec) {
        RewardType<T> type = new RewardType<>(id, codec);
        if (TYPES.put(id, type) != null) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Reward type '{}' was replaced by a later registration.", id);
        }
        return type;
    }

    /** The registered type for {@code id}, if any. */
    public static Optional<RewardType<?>> get(Identifier id) {
        return Optional.ofNullable(TYPES.get(id));
    }

    /** Whether {@code id} names a registered reward type. */
    public static boolean isRegistered(Identifier id) {
        return TYPES.containsKey(id);
    }

    /** Every registered reward type id (a snapshot). */
    public static Set<Identifier> ids() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(TYPES.keySet()));
    }

    /**
     * The dispatch target for {@link RewardSpec#CODEC}. An unregistered id yields a codec that
     * decodes to an {@link UnknownReward} instead of failing, so {@link QuestDefinitions} can
     * drop the owning quest with a precise, non-fatal warning.
     */
    public static MapCodec<? extends RewardSpec> codecFor(Identifier id) {
        RewardType<?> type = TYPES.get(id);
        if (type == null) {
            return MapCodec.unit(new UnknownReward(id));
        }
        return type.codec();
    }
}
