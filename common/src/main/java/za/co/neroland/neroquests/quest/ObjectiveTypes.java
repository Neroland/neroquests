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
import za.co.neroland.neroquests.quest.objective.UnknownObjective;

/**
 * The static registry of objective kinds. Deliberately a plain {@link ConcurrentHashMap}
 * rather than a Minecraft registry (the pattern Neroland Core uses for {@code NeroLinkRegistry}):
 * objective types are pure code contracts, must be resolvable before any datapack load and
 * on every loader without registry-freeze timing games.
 *
 * <p>Populate it from {@link QuestTypes#init()} during common init — before
 * {@link QuestDefinitions} reads a single file. An add-on may register its own types the same
 * way; ids are namespaced so collisions are the registering mod's own doing.
 */
public final class ObjectiveTypes {

    private static final Map<Identifier, ObjectiveType<?>> TYPES = new ConcurrentHashMap<>();

    private ObjectiveTypes() {
    }

    /**
     * Register an objective kind. A second registration for the same id replaces the first
     * and logs a warning (matching Core's registry conventions).
     *
     * @param id    the type id written as the objective's {@code type} field
     * @param codec the codec for the objective body (without the {@code type} field)
     * @return the registered type, for the caller to keep a handle on
     */
    public static <T extends ObjectiveSpec> ObjectiveType<T> register(Identifier id, MapCodec<T> codec) {
        ObjectiveType<T> type = new ObjectiveType<>(id, codec);
        if (TYPES.put(id, type) != null) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Objective type '{}' was replaced by a later registration.", id);
        }
        return type;
    }

    /** The registered type for {@code id}, if any. */
    public static Optional<ObjectiveType<?>> get(Identifier id) {
        return Optional.ofNullable(TYPES.get(id));
    }

    /** Whether {@code id} names a registered objective type. */
    public static boolean isRegistered(Identifier id) {
        return TYPES.containsKey(id);
    }

    /** Every registered objective type id (a snapshot). */
    public static Set<Identifier> ids() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(TYPES.keySet()));
    }

    /**
     * The dispatch target for {@link ObjectiveSpec#CODEC}. An unregistered id yields a codec
     * that decodes to an {@link UnknownObjective} instead of failing, so
     * {@link QuestDefinitions} can drop the owning quest with a precise, non-fatal warning.
     */
    public static MapCodec<? extends ObjectiveSpec> codecFor(Identifier id) {
        ObjectiveType<?> type = TYPES.get(id);
        if (type == null) {
            return MapCodec.unit(new UnknownObjective(id));
        }
        return type.codec();
    }
}
