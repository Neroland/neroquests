package za.co.neroland.neroquests.quest;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * A page of the quest book: a titled group of quests plus their layout positions.
 *
 * <p>Definitions are datapack-driven — one JSON per chapter under
 * {@code data/<namespace>/neroquests/chapters/<path>.json}; the {@linkplain #id() id} comes
 * from the file path, everything else from {@link #DATA_CODEC}.
 *
 * <p>The chapter stores <em>positions only</em>. Dependency lines between nodes are derived
 * from each quest's {@code prerequisites} at render time, so a pack can never draw a line that
 * contradicts the actual progression graph.
 *
 * <p>Two pack-facing flags (B2/B3), both default {@code false} and ignored for the spine:
 * <ul>
 *   <li>{@code show_when_absent} — keep this chapter (and the quests it lists) when the pack's mod is
 *       not installed; its quests are shown with a "mod not installed" badge and never complete
 *       ({@link #modAbsent()} is then {@code true});</li>
 *   <li>{@code allow_server_scope} — the quests this chapter lists may use {@code "scope": "server"}.</li>
 * </ul>
 */
public record Chapter(Identifier id, String title, Identifier icon, List<Entry> quests,
                      boolean showWhenAbsent, boolean allowServerScope, boolean modAbsent) {

    /** The icon used when a chapter declares none. */
    public static final Identifier DEFAULT_ICON = Identifier.fromNamespaceAndPath("minecraft", "written_book");

    /** The file-body codec (everything except the id, which is taken from the file path). */
    public static final Codec<Data> DATA_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.fieldOf("title").forGetter(Data::title),
            Identifier.CODEC.optionalFieldOf("icon", DEFAULT_ICON).forGetter(Data::icon),
            Entry.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(Data::quests),
            Codec.BOOL.optionalFieldOf("show_when_absent", false).forGetter(Data::showWhenAbsent),
            Codec.BOOL.optionalFieldOf("allow_server_scope", false).forGetter(Data::allowServerScope)
    ).apply(inst, Data::new));

    public Chapter(Identifier id, Data data) {
        this(id, data.title(), data.icon(), data.quests(), data.showWhenAbsent(), data.allowServerScope(), false);
    }

    /** A chapter with both pack flags off, its mod present. */
    public Chapter(Identifier id, String title, Identifier icon, List<Entry> quests) {
        this(id, title, icon, quests, false, false, false);
    }

    /** Where this chapter came from (B1), derived from its namespace. */
    public QuestSource source() {
        return QuestSource.of(id);
    }

    /** The file body of this chapter with another entry list (client sync). */
    public Data toData(List<Entry> entries) {
        return new Data(title, icon, entries, showWhenAbsent, allowServerScope);
    }

    /** A copy with a different entry list — used by load-time validation to prune bad entries. */
    public Chapter withQuests(List<Entry> newQuests) {
        return new Chapter(id, title, icon, List.copyOf(newQuests), showWhenAbsent, allowServerScope, modAbsent);
    }

    /** A copy marked as belonging to a pack whose mod is not installed (B3). */
    public Chapter withModAbsent(boolean absent) {
        return absent == modAbsent ? this
                : new Chapter(id, title, icon, quests, showWhenAbsent, allowServerScope, absent);
    }

    /** The layout position of {@code questId} in this chapter, if it appears here. */
    public Optional<Entry> entry(Identifier questId) {
        return quests.stream().filter(e -> e.quest().equals(questId)).findFirst();
    }

    /**
     * One quest node placed on a chapter page: which quest, and where. Coordinates are
     * abstract grid units (not pixels) so the book can scale them; both default to 0.
     */
    public record Entry(Identifier quest, int x, int y) {

        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Identifier.CODEC.fieldOf("quest").forGetter(Entry::quest),
                Codec.INT.optionalFieldOf("x", 0).forGetter(Entry::x),
                Codec.INT.optionalFieldOf("y", 0).forGetter(Entry::y)
        ).apply(inst, Entry::new));
    }

    /** The decoded JSON body of a chapter file, before the id (from the path) is attached. */
    public record Data(String title, Identifier icon, List<Entry> quests, boolean showWhenAbsent,
                       boolean allowServerScope) {

        /** A body with both pack flags off. */
        public Data(String title, Identifier icon, List<Entry> quests) {
            this(title, icon, quests, false, false);
        }
    }
}
