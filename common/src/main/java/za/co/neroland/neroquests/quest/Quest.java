package za.co.neroland.neroquests.quest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.Codec;
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
                    Optional<Identifier> visibleGate) {

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
                data.objectives(), data.rewards(), data.scope(), data.visibleGate());
    }

    /** A copy with a different prerequisite list — used by load-time validation to prune dangling ids. */
    public Quest withPrerequisites(List<Identifier> newPrerequisites) {
        return new Quest(id, title, description, icon, List.copyOf(newPrerequisites),
                objectives, rewards, scope, visibleGate);
    }

    /**
     * Whether this quest may be shown at all: true unless it declares a {@code visible_gate}
     * that is not in {@code openGates}. Pure — the caller supplies the gate set (from Core's
     * {@code ProgressionGates}) so this stays free of server/player plumbing.
     */
    public boolean isVisible(Set<Identifier> openGates) {
        return visibleGate.isEmpty() || openGates.contains(visibleGate.get());
    }

    /** Whether every prerequisite appears in {@code completedQuests}. Pure. */
    public boolean prerequisitesMet(Set<Identifier> completedQuests) {
        return completedQuests.containsAll(prerequisites);
    }

    /**
     * Whether this quest is both {@linkplain #isVisible visible} and
     * {@linkplain #prerequisitesMet unlocked} — i.e. a player may work on it now. Pure; says
     * nothing about whether it is already complete.
     */
    public boolean isAvailable(Set<Identifier> completedQuests, Set<Identifier> openGates) {
        return isVisible(openGates) && prerequisitesMet(completedQuests);
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
