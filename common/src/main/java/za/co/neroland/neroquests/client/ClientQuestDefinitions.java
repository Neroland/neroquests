package za.co.neroland.neroquests.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.network.DefinitionPager;
import za.co.neroland.neroquests.network.QuestDefinitionsPayload;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestSource;

/**
 * The client's mirror of the server's quest and chapter definitions, populated by
 * {@link QuestDefinitionsPayload}.
 *
 * <p>Mirrors Neroland Core's {@code ClientGates}: pure data with no client-only imports, so it is
 * safe to reference from common code and it never drags rendering classes onto a dedicated server.
 * The client never loads quest datapacks itself — a dedicated server's packs do not exist here —
 * and it never decides anything: this is a read-only copy of what the server said.
 *
 * <p>Written only by the payload handler on the client thread and read by the (later) quest book;
 * the maps are replaced wholesale and published through a {@code volatile} field, so a reader
 * always sees one complete, internally consistent snapshot.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> definitions are pack content only — no player data lives here.
 */
public final class ClientQuestDefinitions {

    /** Definitions and their derived views, published as one immutable unit. */
    private record Snapshot(Map<Identifier, Quest> quests, Map<Identifier, Chapter> chapters, JsonObject editor,
                            Map<Identifier, JsonObject> customExtras) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), new JsonObject(), Map.of());
    }

    /** Server-resolved fields of custom entries kept for the editor (D2); everything else is in the records. */
    private static final Set<String> CUSTOM_EXTRA_FIELDS =
            Set.of("repeatable", "cooldown_seconds", "escrowed", "escrow_copies", "order");

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    /** Bumped on every completed sync (and on clear), so the book can notice changes (E1). */
    private static volatile int generation;

    /** Collects the pages of one definitions sync (AUD-21); the mirror swaps only when all have arrived. */
    private static final DefinitionPager.Assembler<QuestDefinitionsPayload.Entry> PAGES =
            new DefinitionPager.Assembler<>();

    private ClientQuestDefinitions() {
    }

    // --- payload handling ---------------------------------------------------

    /**
     * Take one page of a definitions sync. When it completes the sync, the mirror is replaced with the
     * server's latest snapshot in one step. An entry that fails to decode is logged against its id and
     * skipped — a single bad definition never costs the rest.
     */
    public static void accept(QuestDefinitionsPayload payload) {
        String editor = payload.editor();
        PAGES.accept(payload.syncId(), payload.page(), payload.pages(), payload.entries())
                .ifPresent(entries -> replace(entries, editor));
    }

    private static void replace(List<QuestDefinitionsPayload.Entry> entries, String editorJson) {
        Map<Identifier, Quest> quests = new LinkedHashMap<>();
        Map<Identifier, Chapter> chapters = new LinkedHashMap<>();
        Map<Identifier, JsonObject> extras = new LinkedHashMap<>();
        for (QuestDefinitionsPayload.Entry entry : entries) {
            JsonElement body = parse(entry);
            if (body == null) {
                continue;
            }
            // B3: the server-resolved "mod not installed" flag rides next to the body.
            boolean absent = QuestDefinitionsPayload.modAbsent(body);
            if (QuestSource.of(entry.id()) == QuestSource.CUSTOM && body.isJsonObject()) {
                JsonObject extra = new JsonObject();
                body.getAsJsonObject().entrySet().stream()
                        .filter(member -> CUSTOM_EXTRA_FIELDS.contains(member.getKey()))
                        .forEach(member -> extra.add(member.getKey(), member.getValue()));
                extras.put(entry.id(), extra);
            }
            if (entry.kind() == QuestDefinitionsPayload.Kind.QUEST) {
                decode(entry, body, Quest.DATA_CODEC, "quest")
                        .ifPresent(data -> quests.put(entry.id(), new Quest(entry.id(), data).withModAbsent(absent)));
            } else {
                decode(entry, body, Chapter.DATA_CODEC, "chapter")
                        .ifPresent(data -> chapters.put(entry.id(), new Chapter(entry.id(), data).withModAbsent(absent)));
            }
        }
        snapshot = new Snapshot(Collections.unmodifiableMap(quests), Collections.unmodifiableMap(chapters),
                parseEditor(editorJson), Collections.unmodifiableMap(extras));
        generation++;
    }

    private static JsonObject parseEditor(String json) {
        if (json == null || json.isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    /**
     * Drop everything. Called when the client leaves a world or server, so definitions from one
     * session can never be shown in the next (or on a server that has no NeroQuests content).
     */
    public static void clear() {
        PAGES.clear();
        snapshot = Snapshot.EMPTY;
        generation++;
    }

    private static JsonElement parse(QuestDefinitionsPayload.Entry entry) {
        try {
            return JsonParser.parseString(entry.json());
        } catch (RuntimeException e) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Ignoring unreadable synced definition {}", entry.id(), e);
            return null;
        }
    }

    private static <T> Optional<T> decode(QuestDefinitionsPayload.Entry entry, JsonElement body,
                                          com.mojang.serialization.Codec<T> codec, String kind) {
        try {
            return codec.parse(JsonOps.INSTANCE, body)
                    .resultOrPartial(error -> NeroQuestsCommon.LOGGER.warn(
                            "[NeroQuests] Ignoring synced {} {}: {}", kind, entry.id(), error));
        } catch (RuntimeException e) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Ignoring unreadable synced {} {}", kind, entry.id(), e);
            return Optional.empty();
        }
    }

    // --- accessors ----------------------------------------------------------

    /** Every synced quest, keyed by id, in the server's dependency order. */
    public static Map<Identifier, Quest> quests() {
        return snapshot.quests();
    }

    /** Every synced chapter, keyed by id. */
    public static Map<Identifier, Chapter> chapters() {
        return snapshot.chapters();
    }

    /** The synced quest with this id, if the server sent one. */
    public static Optional<Quest> quest(Identifier id) {
        return Optional.ofNullable(snapshot.quests().get(id));
    }

    /** The synced chapter with this id, if the server sent one. */
    public static Optional<Chapter> chapter(Identifier id) {
        return Optional.ofNullable(snapshot.chapters().get(id));
    }

    /** Every synced chapter, in id order. */
    public static Collection<Chapter> allChapters() {
        return snapshot.chapters().values();
    }

    /** The quests of a chapter, in the order the chapter lists them (its layout order). */
    public static List<Quest> questsOf(Identifier chapterId) {
        Chapter chapter = snapshot.chapters().get(chapterId);
        if (chapter == null) {
            return List.of();
        }
        Map<Identifier, Quest> quests = snapshot.quests();
        List<Quest> out = new ArrayList<>(chapter.quests().size());
        for (Chapter.Entry entry : chapter.quests()) {
            Quest quest = quests.get(entry.quest());
            if (quest != null) {
                out.add(quest);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Whether the server said this player may author custom quests (D2/E1). A UI hint only: the server
     * re-checks every editor request.
     */
    public static boolean canAuthor() {
        JsonElement flag = snapshot.editor().get("can_author");
        try {
            return flag != null && flag.isJsonPrimitive() && flag.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A copy of this player's editor block from the last sync (caps, counts, authored ids; see EditorSync). */
    public static JsonObject editorInfo() {
        return snapshot.editor().deepCopy();
    }

    /** Whether this player wrote the custom quest {@code id} (the book shows "you", else "another player"). */
    public static boolean authoredByMe(Identifier id) {
        JsonElement authored = snapshot.editor().get("authored");
        if (authored == null || !authored.isJsonArray()) {
            return false;
        }
        String wanted = id.toString();
        for (JsonElement element : authored.getAsJsonArray()) {
            if (element.isJsonPrimitive() && wanted.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /** Server-resolved fields of a custom quest or chapter (repeatable, cooldown, escrow, order); empty if none. */
    public static JsonObject customExtra(Identifier id) {
        JsonObject extra = snapshot.customExtras().get(id);
        return extra == null ? new JsonObject() : extra.deepCopy();
    }

    /** A counter bumped by every completed definitions sync; the book compares it to notice a new sync. */
    public static int generation() {
        return generation;
    }

    /**
     * The custom-event channels the server has seen this session (authors only, from the editor block's
     * {@code channels}), for the editor's channel picker. Empty when not sent.
     */
    public static List<Identifier> seenChannels() {
        JsonElement channels = snapshot.editor().get("channels");
        if (channels == null || !channels.isJsonArray()) {
            return List.of();
        }
        List<Identifier> out = new ArrayList<>();
        for (JsonElement element : channels.getAsJsonArray()) {
            Identifier id = element.isJsonPrimitive() ? Identifier.tryParse(element.getAsString()) : null;
            if (id != null) {
                out.add(id);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Whether the server has sent any quest content at all. */
    public static boolean isEmpty() {
        return snapshot.quests().isEmpty() && snapshot.chapters().isEmpty();
    }
}
