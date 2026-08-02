package za.co.neroland.neroquests.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;

/**
 * Server &rarr; client snapshot of every quest and chapter definition the client may need to
 * render. Sent on join and after a datapack reload.
 *
 * <h2>Why definitions travel at all</h2>
 *
 * <p>Quests and chapters are datapack content, and datapacks live on the <em>server</em> — a
 * client connected to a dedicated server has no copy of them, so without this payload the quest
 * book would have nothing to draw. In singleplayer the integrated server sends the snapshot to
 * its own client, which is harmless and keeps exactly one code path for both cases.
 *
 * <h2>Wire format</h2>
 *
 * <p>Each entry is {@code (id, body-json)}: the id (which comes from the file path, not the file
 * body) plus the definition encoded with the very same {@link Quest#DATA_CODEC} /
 * {@link Chapter#DATA_CODEC} the datapack loader uses. Re-using the data codecs rather than
 * inventing a parallel binary format means the two representations can never drift, and it keeps
 * objective/reward polymorphism working through the existing {@code type} dispatch. The client
 * has the same objective/reward type registry (both are registered from common init on both
 * sides), and definitions that referenced unregistered types were already dropped server-side by
 * {@link QuestDefinitions}'s validation, so decoding here is expected to succeed for every entry;
 * one that does not is logged against its id and skipped rather than failing the packet.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> definitions are pack content — titles, icons, objective and
 * reward specifications. This payload carries no player identity, no progress and no world data.
 * It is identical for every recipient.
 *
 * <p>TODO: the snapshot is whole-set. A pack large enough to approach the ~1 MiB custom-payload
 * ceiling would need chunking or a per-chapter fetch; that is not worth the complexity until a
 * pack actually gets there.
 */
public record QuestDefinitionsPayload(List<Entry> quests, List<Entry> chapters)
        implements CustomPacketPayload {

    /** An empty snapshot — what a server with no quest content sends. */
    public static final QuestDefinitionsPayload EMPTY =
            new QuestDefinitionsPayload(List.of(), List.of());

    public static final Type<QuestDefinitionsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "quest_definitions"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestDefinitionsPayload> STREAM_CODEC =
            StreamCodec.of(QuestDefinitionsPayload::write, QuestDefinitionsPayload::read);

    /** Upper bound on one encoded definition body, well above any sane quest JSON. */
    private static final int MAX_BODY_CHARS = 262_144;

    /** Upper bound on entry counts, so a malformed packet cannot pre-allocate without limit. */
    private static final int MAX_ENTRIES = 32_768;

    /** One definition on the wire: its id (from the file path) plus its encoded body. */
    public record Entry(Identifier id, String json) {
    }

    /** Builds the snapshot from the definitions currently loaded for {@code server}. */
    public static QuestDefinitionsPayload of(MinecraftServer server) {
        Map<Identifier, Quest> quests = QuestDefinitions.questsForServer(server);
        Map<Identifier, Chapter> chapters = QuestDefinitions.chaptersForServer(server);

        List<Entry> questEntries = new ArrayList<>(quests.size());
        for (Quest quest : quests.values()) {
            encode(Quest.DATA_CODEC, toData(quest), quest.id(), "quest")
                    .ifPresent(json -> questEntries.add(new Entry(quest.id(), json)));
        }
        List<Entry> chapterEntries = new ArrayList<>(chapters.size());
        for (Chapter chapter : chapters.values()) {
            encode(Chapter.DATA_CODEC, toData(chapter), chapter.id(), "chapter")
                    .ifPresent(json -> chapterEntries.add(new Entry(chapter.id(), json)));
        }
        return new QuestDefinitionsPayload(List.copyOf(questEntries), List.copyOf(chapterEntries));
    }

    /** The codec-shaped body of a loaded quest (the inverse of {@link Quest#Quest(Identifier, Quest.Data)}). */
    private static Quest.Data toData(Quest quest) {
        return new Quest.Data(quest.title(), quest.description(), quest.icon(), quest.prerequisites(),
                quest.objectives(), quest.rewards(), quest.scope(), quest.visibleGate());
    }

    /** The codec-shaped body of a loaded chapter. */
    private static Chapter.Data toData(Chapter chapter) {
        return new Chapter.Data(chapter.title(), chapter.icon(), chapter.quests());
    }

    private static <T> java.util.Optional<String> encode(com.mojang.serialization.Codec<T> codec, T value,
                                                         Identifier id, String kind) {
        return codec.encodeStart(JsonOps.INSTANCE, value)
                .resultOrPartial(error -> NeroQuestsCommon.LOGGER.warn(
                        "[NeroQuests] Could not encode {} {} for client sync: {}", kind, id, error))
                .map(JsonElement::toString);
    }

    private static void write(RegistryFriendlyByteBuf buf, QuestDefinitionsPayload payload) {
        writeEntries(buf, payload.quests);
        writeEntries(buf, payload.chapters);
    }

    private static QuestDefinitionsPayload read(RegistryFriendlyByteBuf buf) {
        return new QuestDefinitionsPayload(readEntries(buf), readEntries(buf));
    }

    private static void writeEntries(RegistryFriendlyByteBuf buf, List<Entry> entries) {
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeUtf(entry.id().toString());
            buf.writeUtf(entry.json(), MAX_BODY_CHARS);
        }
    }

    private static List<Entry> readEntries(RegistryFriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Identifier id = Identifier.tryParse(buf.readUtf());
            String json = buf.readUtf(MAX_BODY_CHARS);
            if (id != null) {
                entries.add(new Entry(id, json));
            }
        }
        return List.copyOf(entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
