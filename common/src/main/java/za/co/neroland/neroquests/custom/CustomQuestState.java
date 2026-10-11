package za.co.neroland.neroquests.custom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.data.SavedDataRecovery;
import za.co.neroland.neroquests.quest.QuestSource;

/**
 * The custom-quest store (D1): every editor-made chapter and quest of this world, the editor allow-list
 * and the editor lock. Persisted as vanilla {@link SavedData} ({@code neroquests:custom_quests}) on the
 * overworld through NeroQuests' {@link SavedDataRecovery} wrapper (Core's recovering accessor plus a copy of
 * an undecodable primary file), like {@code QuestProgressState}.
 *
 * <p>Bounded by {@link CustomLimits} (enforced by {@link CustomQuests}, the single writer). Custom content
 * feeds the same definitions pipeline as datapacks ({@link CustomContent}); progress for custom quests lives
 * in {@code QuestProgressState} under their {@code custom:} ids.
 *
 * <p><b>Privacy (POPIA/GDPR).</b> Two kinds of identifier live here: each quest's {@code created_by} (the
 * author's game UUID, the only one the quest rows add) and the editor allow-list (game UUIDs an operator
 * added). Both are exported for that player ({@link #export}) and erased for them ({@link #erase}: the
 * author field is cleared — the quest survives — and the allow-list entry removed). Neither is ever logged
 * or resolved to a name. Author text (titles, descriptions, labels) is the author's own content; it is never
 * logged and never sent to telemetry.
 *
 * <p>Server thread only.
 */
public final class CustomQuestState extends SavedData {

    /** Stable, non-identifying label used for the storage file and recovery logs. */
    public static final String NAME = NeroQuestsCommon.MOD_ID + ":custom_quests";

    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "custom_quests");

    public static final SavedDataType<CustomQuestState> TYPE =
            new SavedDataType<>(ID, CustomQuestState::new, codec(), null);

    private final Map<String, CustomChapter> chapters = new LinkedHashMap<>();
    private final Map<Identifier, CustomQuest> quests = new LinkedHashMap<>();
    private final Set<UUID> allowList = new LinkedHashSet<>();
    private boolean locked;

    public CustomQuestState() {
    }

    /** The world's store (overworld data storage, recovering accessor). */
    public static CustomQuestState get(MinecraftServer server) {
        return SavedDataRecovery.get(server.overworld(), TYPE, CustomQuestState::new, NAME);
    }

    /** Refresh Core's last-known-good backup now (after an erasure, so it reaches the second copy too). */
    public void backupNow(MinecraftServer server) {
        SavedDataRecovery.backupNow(server.overworld(), TYPE, this, NAME, server.registryAccess());
    }

    // --- reads -----------------------------------------------------------------

    /** Every custom chapter by slug (unmodifiable view, insertion order). */
    public Map<String, CustomChapter> chapters() {
        return Collections.unmodifiableMap(chapters);
    }

    /** Every custom quest by id (unmodifiable view, insertion order). */
    public Map<Identifier, CustomQuest> quests() {
        return Collections.unmodifiableMap(quests);
    }

    public Optional<CustomChapter> chapter(String slug) {
        return Optional.ofNullable(chapters.get(slug));
    }

    public Optional<CustomQuest> quest(Identifier id) {
        return Optional.ofNullable(quests.get(id));
    }

    /** The quests of one chapter, in insertion order. */
    public List<CustomQuest> questsOf(String slug) {
        List<CustomQuest> out = new ArrayList<>();
        for (CustomQuest quest : quests.values()) {
            if (quest.chapter().equals(slug)) {
                out.add(quest);
            }
        }
        return out;
    }

    /** Whether a quest slug is already used in a chapter. */
    public boolean questSlugTaken(String chapterSlug, String questSlug) {
        return quests.containsKey(Identifier.fromNamespaceAndPath("custom", chapterSlug + "/" + questSlug));
    }

    public boolean isLocked() {
        return locked;
    }

    public boolean isAllowed(UUID player) {
        return player != null && allowList.contains(player);
    }

    public int allowListSize() {
        return allowList.size();
    }

    // --- writes (only through CustomQuests, the single writer) -----------------

    void putChapter(CustomChapter chapter) {
        requireCustom(chapter.id());
        chapters.put(chapter.slug(), chapter);
        setDirty();
    }

    void removeChapter(String slug) {
        if (chapters.remove(slug) != null) {
            setDirty();
        }
    }

    void putQuest(CustomQuest quest) {
        requireCustom(quest.id());
        quests.put(quest.id(), quest);
        setDirty();
    }

    /**
     * J1 defence in depth: the store holds {@code custom:} ids only, so no editor, import or command path that
     * goes through it can ever write a spine or pack id (the definitions merge ignores anything else too).
     */
    static void requireCustom(Identifier id) {
        if (id == null || QuestSource.of(id) != QuestSource.CUSTOM) {
            throw new IllegalArgumentException("the custom quest store holds custom ids only");
        }
    }

    void removeQuest(Identifier id) {
        if (quests.remove(id) != null) {
            setDirty();
        }
    }

    /** @return whether the list changed */
    boolean allow(UUID player) {
        boolean changed = allowList.add(player);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /** @return whether the list changed */
    boolean deny(UUID player) {
        boolean changed = allowList.remove(player);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /** @return whether the state changed */
    boolean setLocked(boolean value) {
        if (locked == value) {
            return false;
        }
        locked = value;
        setDirty();
        return true;
    }

    /** A detached copy of the chapters and quests, for previewing a mutation before it is applied. */
    CustomQuestState copy() {
        CustomQuestState copy = new CustomQuestState();
        copy.chapters.putAll(chapters);
        copy.quests.putAll(quests);
        copy.allowList.addAll(allowList);
        copy.locked = locked;
        return copy;
    }

    /** Take over the chapters and quests of a previewed copy. */
    void adopt(CustomQuestState preview) {
        chapters.clear();
        chapters.putAll(preview.chapters);
        quests.clear();
        quests.putAll(preview.quests);
        setDirty();
    }

    // --- POPIA/GDPR -------------------------------------------------------------

    /**
     * Data access for one player: the custom quests they authored (ids, chapter, their own title and
     * description, timestamps) and whether they are on the editor allow-list. Nobody else's rows and no
     * other UUID.
     */
    public JsonObject export(UUID player) {
        JsonObject root = new JsonObject();
        JsonArray authored = new JsonArray();
        for (CustomQuest quest : quests.values()) {
            if (quest.authoredBy(player)) {
                JsonObject row = new JsonObject();
                row.addProperty("id", quest.id().toString());
                row.addProperty("chapter", quest.chapterId().toString());
                row.addProperty("title", quest.title());
                if (!quest.description().isEmpty()) {
                    row.addProperty("description", quest.description());
                }
                row.addProperty("created_at", quest.createdAt());
                row.addProperty("updated_at", quest.updatedAt());
                authored.add(row);
            }
        }
        root.add("authored_custom_quests", authored);
        root.addProperty("editor_allow_list", isAllowed(player));
        return root;
    }

    /**
     * Erasure for one player: every quest they authored keeps existing with {@code created_by} cleared, and
     * they leave the editor allow-list. Never logs who.
     *
     * @return whether anything changed
     */
    public boolean erase(UUID player) {
        boolean changed = allowList.remove(player);
        for (Map.Entry<Identifier, CustomQuest> entry : quests.entrySet()) {
            if (entry.getValue().authoredBy(player)) {
                entry.setValue(entry.getValue().withoutAuthor());
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    // --- persistence -------------------------------------------------------------

    private static final int FORMAT = 1;

    private static Codec<CustomQuestState> codec() {
        return RecordCodecBuilder.create(inst -> inst.group(
                Codec.INT.optionalFieldOf("format", FORMAT).forGetter(state -> Integer.valueOf(FORMAT)),
                CustomChapter.CODEC.listOf().optionalFieldOf("chapters", List.of())
                        .forGetter(state -> List.copyOf(state.chapters.values())),
                CustomQuest.CODEC.listOf().optionalFieldOf("quests", List.of())
                        .forGetter(state -> List.copyOf(state.quests.values())),
                Codec.STRING.listOf().optionalFieldOf("editor_allow_list", List.of())
                        .forGetter(state -> state.allowList.stream().map(UUID::toString).toList()),
                Codec.BOOL.optionalFieldOf("editor_locked", Boolean.FALSE)
                        .forGetter(state -> Boolean.valueOf(state.locked))
        ).apply(inst, CustomQuestState::fromRows));
    }

    /** The persistence codec, exposed to the package for round-trip tests. */
    static Codec<CustomQuestState> persistenceCodec() {
        return codec();
    }

    private static CustomQuestState fromRows(Integer format, List<CustomChapter> chapters, List<CustomQuest> quests,
                                             List<String> allowList, Boolean locked) {
        CustomQuestState state = new CustomQuestState();
        for (CustomChapter chapter : chapters) {
            state.chapters.put(chapter.slug(), chapter);
        }
        for (CustomQuest quest : quests) {
            if (QuestSource.of(quest.id()) == QuestSource.CUSTOM) { // J1: a foreign id is never loaded
                state.quests.put(quest.id(), quest);
            }
        }
        for (String raw : allowList) {
            try {
                state.allowList.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // malformed row: skipped (never logged — it would be an identifier)
            }
        }
        state.locked = locked.booleanValue();
        return state;
    }
}
