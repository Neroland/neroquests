package za.co.neroland.neroquests.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.network.QuestDefinitionsPayload;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.Quest;

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
    private record Snapshot(Map<Identifier, Quest> quests, Map<Identifier, Chapter> chapters) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());
    }

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private ClientQuestDefinitions() {
    }

    // --- payload handling ---------------------------------------------------

    /**
     * Replace the mirror with the server's latest snapshot. An entry that fails to decode is
     * logged against its id and skipped — a single bad definition never costs the rest.
     */
    public static void accept(QuestDefinitionsPayload payload) {
        Map<Identifier, Quest> quests = new LinkedHashMap<>(payload.quests().size());
        for (QuestDefinitionsPayload.Entry entry : payload.quests()) {
            decode(entry, Quest.DATA_CODEC, "quest")
                    .ifPresent(data -> quests.put(entry.id(), new Quest(entry.id(), data)));
        }
        Map<Identifier, Chapter> chapters = new LinkedHashMap<>(payload.chapters().size());
        for (QuestDefinitionsPayload.Entry entry : payload.chapters()) {
            decode(entry, Chapter.DATA_CODEC, "chapter")
                    .ifPresent(data -> chapters.put(entry.id(), new Chapter(entry.id(), data)));
        }
        snapshot = new Snapshot(Collections.unmodifiableMap(quests), Collections.unmodifiableMap(chapters));
    }

    /**
     * Drop everything. Called when the client leaves a world or server, so definitions from one
     * session can never be shown in the next (or on a server that has no NeroQuests content).
     */
    public static void clear() {
        snapshot = Snapshot.EMPTY;
    }

    private static <T> Optional<T> decode(QuestDefinitionsPayload.Entry entry,
                                          com.mojang.serialization.Codec<T> codec, String kind) {
        try {
            return codec.parse(JsonOps.INSTANCE, JsonParser.parseString(entry.json()))
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

    /** Whether the server has sent any quest content at all. */
    public static boolean isEmpty() {
        return snapshot.quests().isEmpty() && snapshot.chapters().isEmpty();
    }
}
