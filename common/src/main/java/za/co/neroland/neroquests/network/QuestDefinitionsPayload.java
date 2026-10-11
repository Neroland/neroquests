package za.co.neroland.neroquests.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestSource;

/**
 * Server &rarr; client: one page of the quest and chapter definitions a player may see. Sent on
 * join, after a datapack reload, and when a gate change alters which quests the player may see.
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
 * <p>Each entry is {@code (kind, id, body-json)}: quest or chapter, the id (which comes from the
 * file path, not the file body) plus the definition encoded with the very same
 * {@link Quest#DATA_CODEC} / {@link Chapter#DATA_CODEC} the datapack loader uses. Re-using the data
 * codecs rather than inventing a parallel binary format means the two representations can never
 * drift, and it keeps objective/reward polymorphism working through the existing {@code type}
 * dispatch. Definitions that referenced unregistered types were already dropped server-side by
 * {@link QuestDefinitions}'s validation, so decoding is expected to succeed for every entry; one that
 * does not is logged against its id and skipped by the client.
 *
 * <h2>Paging (AUD-21)</h2>
 *
 * <p>A sync is split into pages of at most {@link DefinitionPager#MAX_PAGE_BYTES} encoded bytes, each
 * carrying the sync id, its page index and the page count; the client swaps its mirror only once every
 * page of a sync has arrived. Encoding never throws: an entry that cannot fit a page on its own is
 * skipped and logged once by id. Readers reject negative or oversized counts (AUD-35) rather than
 * truncating, which would leave the rest of the buffer misread.
 *
 * <h2>Server-resolved fields (B1/B3)</h2>
 *
 * <p>Next to the file body each entry's JSON carries two fields the server resolves at load and a
 * pack can never set: {@value #SOURCE_FIELD} ({@code spine}, {@code pack} or {@code custom}) and, only
 * when true, {@value #MOD_ABSENT_FIELD} — the definition belongs to a pack whose mod is not installed
 * (kept by {@code show_when_absent}; render a "mod not installed" badge, never completable). The data
 * codecs ignore unknown fields, so these ride along without a format change.
 *
 * <p>Custom (editor) entries (D1/D2) carry a few more server-resolved fields the same way: a quest
 * {@code repeatable}, {@code cooldown_seconds}, {@code escrowed} and {@code escrow_copies}; a chapter
 * {@code order}. Their {@code title}/{@code description} are author-written plain text, to be rendered
 * literally ({@code QuestText}) — never as translation keys.
 *
 * <h2>Editor block (D2/E1)</h2>
 *
 * <p>Every page also carries {@link #editor()}: a small JSON object about the <em>receiving</em> player only —
 * {@code can_author} (a UI hint; the server re-checks every request), and for authors the caps, counts and
 * lock/escrow state, plus the ids of the custom quests that player wrote (so the book can say "you"). No
 * UUID and no name. See {@code EditorSync}.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> definitions are pack content — titles, icons, objective and
 * reward specifications. This payload carries no player identity, no progress and no world data.
 * Which quests a page holds depends on the recipient's open gates (hidden quests are not sent,
 * AUD-19), but nothing in it names anyone.
 */
public record QuestDefinitionsPayload(int syncId, int page, int pages, String editor, List<Entry> entries)
        implements CustomPacketPayload {

    public static final Type<QuestDefinitionsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "quest_definitions"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestDefinitionsPayload> STREAM_CODEC =
            StreamCodec.of(QuestDefinitionsPayload::write, QuestDefinitionsPayload::read);

    /** Synced field: the definition's {@link QuestSource} as its lower-case name. */
    public static final String SOURCE_FIELD = "source";

    /** Synced field, present only when true: the definition's pack mod is not installed (B3). */
    public static final String MOD_ABSENT_FIELD = "mod_absent";

    /** Upper bound on the per-player editor block (JSON). */
    public static final int MAX_EDITOR_CHARS = 65_536;

    /** Upper bound on one encoded definition body, well above any sane quest JSON. */
    public static final int MAX_BODY_CHARS = 262_144;

    /** Upper bound on entries in one page, so a malformed packet cannot pre-allocate without limit. */
    private static final int MAX_ENTRIES = 32_768;

    /** What a wire entry holds. */
    public enum Kind {
        QUEST,
        CHAPTER
    }

    /** One definition on the wire: its kind, its id (from the file path) and its encoded body. */
    public record Entry(Kind kind, Identifier id, String json) {

        /** Encoded size on the wire: kind byte + id + body. */
        public int encodedSize() {
            return 1 + DefinitionPager.utfSize(id.toString()) + DefinitionPager.utfSize(json);
        }
    }

    // --- building -----------------------------------------------------------

    /** A loaded quest as a wire entry, or empty (logged) if it cannot be encoded. */
    public static Optional<Entry> quest(Quest quest) {
        return quest(quest, null);
    }

    /** {@link #quest(Quest)} with extra server-resolved fields merged into the body ({@code null} for none). */
    public static Optional<Entry> quest(Quest quest, JsonObject extra) {
        Quest.Data data = new Quest.Data(quest.title(), quest.description(), quest.icon(), quest.prerequisites(),
                quest.objectives(), quest.rewards(), quest.scope(), quest.visibleGate());
        return encode(Quest.DATA_CODEC, data, quest.id(), Kind.QUEST, quest.source(), quest.modAbsent(), extra);
    }

    /** A chapter as a wire entry (with the given entry list), or empty (logged) if it cannot be encoded. */
    public static Optional<Entry> chapter(Chapter chapter, List<Chapter.Entry> visibleEntries) {
        return chapter(chapter, visibleEntries, null);
    }

    /** {@link #chapter(Chapter, List)} with extra server-resolved fields ({@code null} for none). */
    public static Optional<Entry> chapter(Chapter chapter, List<Chapter.Entry> visibleEntries, JsonObject extra) {
        Chapter.Data data = chapter.toData(visibleEntries);
        return encode(Chapter.DATA_CODEC, data, chapter.id(), Kind.CHAPTER, chapter.source(), chapter.modAbsent(),
                extra);
    }

    /** Whether a synced entry body carries {@value #MOD_ABSENT_FIELD}{@code : true}. Never throws. */
    public static boolean modAbsent(JsonElement body) {
        if (body == null || !body.isJsonObject()) {
            return false;
        }
        JsonElement flag = body.getAsJsonObject().get(MOD_ABSENT_FIELD);
        try {
            return flag != null && flag.isJsonPrimitive() && flag.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Split {@code entries} into pages of one sync. Never throws; an entry too large for a page (or for
     * {@link #MAX_BODY_CHARS}) is handed to {@code oversized} and left out. Always at least one page.
     */
    public static List<QuestDefinitionsPayload> paginate(int syncId, List<Entry> entries, Consumer<Entry> oversized) {
        return paginate(syncId, "", entries, oversized);
    }

    /**
     * {@link #paginate(int, List, Consumer)} carrying {@code editor} (the receiving player's editor block) on
     * every page. An editor block over {@link #MAX_EDITOR_CHARS} is replaced by an empty one.
     */
    public static List<QuestDefinitionsPayload> paginate(int syncId, String editor, List<Entry> entries,
                                                         Consumer<Entry> oversized) {
        String block = editor == null || editor.length() > MAX_EDITOR_CHARS ? "" : editor;
        int budget = DefinitionPager.MAX_PAGE_BYTES - DefinitionPager.HEADER_BYTES - DefinitionPager.utfSize(block);
        List<Entry> fitting = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (entry.json().length() > MAX_BODY_CHARS) {
                oversized.accept(entry);
            } else {
                fitting.add(entry);
            }
        }
        List<List<Entry>> split = DefinitionPager.split(fitting, Entry::encodedSize, budget, oversized);
        List<QuestDefinitionsPayload> out = new ArrayList<>(split.size());
        for (int index = 0; index < split.size(); index++) {
            out.add(new QuestDefinitionsPayload(syncId, index, split.size(), block, split.get(index)));
        }
        return List.copyOf(out);
    }

    private static <T> Optional<Entry> encode(Codec<T> codec, T value, Identifier id, Kind kind,
                                              QuestSource source, boolean modAbsent, JsonObject extra) {
        try {
            return codec.encodeStart(JsonOps.INSTANCE, value)
                    .resultOrPartial(error -> NeroQuestsCommon.LOGGER.warn(
                            "[NeroQuests] Could not encode {} {} for client sync: {}",
                            kind.name().toLowerCase(java.util.Locale.ROOT), id, error))
                    .map(json -> {
                        if (json.isJsonObject()) {
                            JsonObject object = json.getAsJsonObject();
                            object.addProperty(SOURCE_FIELD, source.wireName());
                            if (modAbsent) {
                                object.addProperty(MOD_ABSENT_FIELD, Boolean.TRUE);
                            }
                            if (extra != null) {
                                extra.entrySet().forEach(member -> object.add(member.getKey(), member.getValue()));
                            }
                        }
                        return json;
                    })
                    .map(JsonElement::toString)
                    .map(json -> new Entry(kind, id, json));
        } catch (RuntimeException e) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Could not encode {} {} for client sync.",
                    kind.name().toLowerCase(java.util.Locale.ROOT), id, e);
            return Optional.empty();
        }
    }

    // --- wire ---------------------------------------------------------------

    private static void write(RegistryFriendlyByteBuf buf, QuestDefinitionsPayload payload) {
        buf.writeVarInt(payload.syncId);
        buf.writeVarInt(payload.page);
        buf.writeVarInt(payload.pages);
        buf.writeUtf(payload.editor, MAX_EDITOR_CHARS);
        buf.writeVarInt(payload.entries.size());
        for (Entry entry : payload.entries) {
            buf.writeByte(entry.kind().ordinal());
            buf.writeUtf(entry.id().toString());
            buf.writeUtf(entry.json(), MAX_BODY_CHARS);
        }
    }

    private static QuestDefinitionsPayload read(RegistryFriendlyByteBuf buf) {
        int syncId = buf.readVarInt();
        int page = buf.readVarInt();
        int pages = buf.readVarInt();
        if (pages < 1 || pages > DefinitionPager.MAX_PAGES || page < 0 || page >= pages) {
            throw new DecoderException("Bad quest definitions page " + page + "/" + pages);
        }
        String editor = buf.readUtf(MAX_EDITOR_CHARS);
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new DecoderException("Bad quest definitions entry count " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        Kind[] kinds = Kind.values();
        for (int i = 0; i < count; i++) {
            int kind = buf.readUnsignedByte();
            if (kind >= kinds.length) {
                throw new DecoderException("Bad quest definitions entry kind " + kind);
            }
            Identifier id = Identifier.tryParse(buf.readUtf());
            String json = buf.readUtf(MAX_BODY_CHARS);
            if (id != null) {
                // An unparseable id costs only its own entry: the buffer position is still exact.
                entries.add(new Entry(kinds[kind], id, json));
            }
        }
        return new QuestDefinitionsPayload(syncId, page, pages, editor, List.copyOf(entries));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
