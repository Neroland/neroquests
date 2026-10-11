package za.co.neroland.neroquests.quest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * A single quest definition: what it is called, what must be done, and what it pays out.
 *
 * <p>Definitions are datapack-driven — one JSON per quest under
 * {@code data/<namespace>/neroquests/quests/<path>.json}; the {@linkplain #id() id} comes from
 * the file path (namespace + path without the extension), everything else from
 * {@link #DATA_CODEC}. A pack adds, overrides or removes a quest simply by shipping the same
 * id. This mirrors how Neroland Core loads its progression gates.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code title} / {@code description} — translation keys (a literal string also renders,
 *       but ships untranslated);</li>
 *   <li>{@code icon} — the item id shown in the quest book, defaulting to {@link #DEFAULT_ICON};</li>
 *   <li>{@code prerequisites} — quest ids that must be complete before this one unlocks;</li>
 *   <li>{@code objectives} — at least one {@link ObjectiveSpec} (a quest with none is dropped
 *       on load);</li>
 *   <li>{@code rewards} — zero or more {@link RewardSpec}s;</li>
 *   <li>{@code scope} — {@code player} (default) or {@code server};</li>
 *   <li>{@code visible_gate} — an optional Neroland Core gate id; the quest stays hidden until
 *       that gate is open.</li>
 * </ul>
 *
 * <p>Two things are resolved at load rather than read from the file (B1/B3): the quest's
 * {@linkplain #source() source} (from its namespace) and {@link #modAbsent()} — a pack quest kept
 * only because its chapter sets {@code show_when_absent} although the pack's mod is not installed.
 * Such a quest is shown with a "mod not installed" badge and is never completable.
 *
 * <p>Progress state is not part of a definition — it lives per player in the progress store.
 */
public record Quest(Identifier id,
                    String title,
                    String description,
                    Identifier icon,
                    List<Identifier> prerequisites,
                    List<ObjectiveSpec> objectives,
                    List<RewardSpec> rewards,
                    QuestScope scope,
                    Optional<Identifier> visibleGate,
                    boolean modAbsent) {

    /** The icon used when a quest declares none. */
    public static final Identifier DEFAULT_ICON = Identifier.fromNamespaceAndPath("minecraft", "book");

    /** The file-body codec (everything except the id, which is taken from the file path). */
    public static final Codec<Data> DATA_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("title").forGetter(Data::title),
            Codec.STRING.optionalFieldOf("description", "").forGetter(Data::description),
            Identifier.CODEC.optionalFieldOf("icon", DEFAULT_ICON).forGetter(Data::icon),
            Identifier.CODEC.listOf().optionalFieldOf("prerequisites", List.of()).forGetter(Data::prerequisites),
            ObjectiveSpec.CODEC.listOf().fieldOf("objectives").forGetter(Data::objectives),
            RewardSpec.CODEC.listOf().optionalFieldOf("rewards", List.of()).forGetter(Data::rewards),
            QuestScope.CODEC.optionalFieldOf("scope", QuestScope.PLAYER).forGetter(Data::scope),
            Identifier.CODEC.optionalFieldOf("visible_gate").forGetter(Data::visibleGate)
    ).apply(inst, Data::new));

    public Quest(Identifier id, Data data) {
        this(id, data.title(), data.description(), data.icon(), data.prerequisites(),
                data.objectives(), data.rewards(), data.scope(), data.visibleGate(), false);
    }

    /** A quest whose pack is present (the usual case). */
    public Quest(Identifier id, String title, String description, Identifier icon, List<Identifier> prerequisites,
                 List<ObjectiveSpec> objectives, List<RewardSpec> rewards, QuestScope scope,
                 Optional<Identifier> visibleGate) {
        this(id, title, description, icon, prerequisites, objectives, rewards, scope, visibleGate, false);
    }

    /** Where this quest came from (B1), derived from its namespace. */
    public QuestSource source() {
        return QuestSource.of(id);
    }

    /** A copy marked as belonging to a pack whose mod is not installed (B3): badge, never completable. */
    public Quest withModAbsent(boolean absent) {
        return absent == modAbsent ? this : new Quest(id, title, description, icon, prerequisites,
                objectives, rewards, scope, visibleGate, absent);
    }

    /**
     * A stable fingerprint of an objective list (AUD-17): the hash of the list encoded with the
     * objectives' own codec, so type, target, count and every other field take part, in order. Never
     * {@code 0}, which stored rows use for "no fingerprint yet". Stable across restarts (it hashes the
     * encoded JSON text, never identity hash codes).
     */
    public static int objectivesFingerprint(List<ObjectiveSpec> objectives) {
        String canonical;
        try {
            canonical = ObjectiveSpec.CODEC.listOf().encodeStart(JsonOps.INSTANCE, objectives)
                    .result().map(Object::toString).orElse(null);
        } catch (RuntimeException e) {
            canonical = null;
        }
        if (canonical == null) {
            StringBuilder fallback = new StringBuilder();
            for (ObjectiveSpec objective : objectives) {
                fallback.append(objective.typeId()).append('/').append(objective.target()).append(';');
            }
            canonical = fallback.toString();
        }
        int hash = canonical.hashCode();
        return hash == 0 ? 1 : hash;
    }

    /** A copy with a different prerequisite list — used by load-time validation to prune dangling ids. */
    public Quest withPrerequisites(List<Identifier> newPrerequisites) {
        return new Quest(id, title, description, icon, List.copyOf(newPrerequisites),
                objectives, rewards, scope, visibleGate, modAbsent);
    }

    /**
     * Whether this quest may be shown at all: true unless it declares a {@code visible_gate}
     * that is not in {@code openGates}. Pure — the caller supplies the gate set (from Core's
     * {@code ProgressionGates}) so this stays free of server/player plumbing.
     */
    public boolean isVisible(Set<Identifier> openGates) {
        // Prerequisites and availability live in QuestStates (AUD-18): each prerequisite resolves by
        // its own scope, which needs the definitions, not just this quest.
        return visibleGate.isEmpty() || openGates.contains(visibleGate.get());
    }

    /** The decoded JSON body of a quest file, before the id (from the path) is attached. */
    public record Data(String title,
                       String description,
                       Identifier icon,
                       List<Identifier> prerequisites,
                       List<ObjectiveSpec> objectives,
                       List<RewardSpec> rewards,
                       QuestScope scope,
                       Optional<Identifier> visibleGate) {
    }
}
