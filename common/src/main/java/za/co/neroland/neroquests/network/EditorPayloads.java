package za.co.neroland.neroquests.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.custom.EditResult;

/**
 * The quest editor's wire format (E1): one serverbound payload per {@code CustomQuests} method, plus the
 * clientbound {@link Result} toast. Every payload carries a client-chosen {@code requestId} that the result
 * echoes, so the screen can tell which of its requests a reply belongs to (the first-run flow needs the id of
 * the quest it just created).
 *
 * <p><b>Nothing here decides anything.</b> A payload is a request; the server handler
 * ({@link EditorRequests}) re-checks the player and hands the fields to the server API, which re-derives the
 * editor permission, rate-limits, validates and answers with an {@link EditResult}.
 *
 * <p><b>Bounds on decode</b> (a malformed packet is a {@link DecoderException}, which drops the connection as
 * any other corrupt packet would): every string has a hard length cap well above what the server API accepts
 * after sanitising (so a legitimate request is never cut, and the server still gives the real refusal), every
 * list has a size cap, and every resource id must parse. Objectives and rewards travel as the codec JSON the
 * server decodes ({@code [{"type": "neroquests:collect_item", ...}]}), capped in characters here and in
 * elements by the handler.
 *
 * <p>The codecs work on a plain {@link FriendlyByteBuf} (no registry access is needed), which also keeps them
 * unit-testable without a game bootstrap.
 */
public final class EditorPayloads {

    /** Longest resource id accepted on the wire. */
    public static final int MAX_ID = 256;
    /** Longest raw title (the server keeps at most 64 code points after sanitising). */
    public static final int MAX_TITLE = 256;
    /** Longest raw description (the server keeps at most 512 code points after sanitising). */
    public static final int MAX_DESCRIPTION = 2048;
    /** Longest objectives / rewards JSON. */
    public static final int MAX_JSON = 32_768;
    /** Most prerequisites one request may list (the server API's own cap). */
    public static final int MAX_PREREQUISITES = 16;
    /** Longest refusal key and argument in a result, and the most arguments. */
    public static final int MAX_KEY = 128;
    public static final int MAX_ARG = 256;
    public static final int MAX_ARGS = 8;

    /** A translation key the result may carry: lower-case dotted segments only. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)+");

    private EditorPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "editor/" + path));
    }

    // --- wire helpers (bounded) ------------------------------------------------------------------

    static String readText(FriendlyByteBuf buf, int max) {
        return buf.readUtf(max);
    }

    static void writeText(FriendlyByteBuf buf, String value, int max) {
        String text = value == null ? "" : value;
        if (text.length() > max) {
            throw new EncoderException("Editor text over " + max + " characters");
        }
        buf.writeUtf(text, max);
    }

    static Identifier readId(FriendlyByteBuf buf) {
        String raw = buf.readUtf(MAX_ID);
        Identifier id = Identifier.tryParse(raw);
        if (id == null) {
            throw new DecoderException("Editor request carries an unparsable id");
        }
        return id;
    }

    static void writeId(FriendlyByteBuf buf, Identifier id) {
        buf.writeUtf(id.toString(), MAX_ID);
    }

    static Optional<Identifier> readOptionalId(FriendlyByteBuf buf) {
        return buf.readBoolean() ? Optional.of(readId(buf)) : Optional.empty();
    }

    static void writeOptionalId(FriendlyByteBuf buf, Optional<Identifier> id) {
        buf.writeBoolean(id.isPresent());
        id.ifPresent(value -> writeId(buf, value));
    }

    static int readCount(FriendlyByteBuf buf, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw new DecoderException("Editor list size " + count + " outside 0.." + max);
        }
        return count;
    }

    // --- chapters ---------------------------------------------------------------------------------

    /** {@code CustomQuests.createChapter(title, icon)}. */
    public record CreateChapter(int requestId, String title, Optional<Identifier> icon) implements CustomPacketPayload {
        public static final Type<CreateChapter> TYPE = payloadType("create_chapter");
        public static final StreamCodec<FriendlyByteBuf, CreateChapter> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.requestId);
                    writeText(buf, p.title, MAX_TITLE);
                    writeOptionalId(buf, p.icon);
                },
                buf -> new CreateChapter(buf.readVarInt(), readText(buf, MAX_TITLE), readOptionalId(buf)));

        @Override
        public Type<CreateChapter> type() {
            return TYPE;
        }
    }

    /** {@code CustomQuests.updateChapter(chapter, title, icon, order)}. */
    public record UpdateChapter(int requestId, Identifier chapter, String title, Optional<Identifier> icon, int order)
            implements CustomPacketPayload {
        public static final Type<UpdateChapter> TYPE = payloadType("update_chapter");
        public static final StreamCodec<FriendlyByteBuf, UpdateChapter> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.requestId);
                    writeId(buf, p.chapter);
                    writeText(buf, p.title, MAX_TITLE);
                    writeOptionalId(buf, p.icon);
                    buf.writeVarInt(p.order);
                },
                buf -> new UpdateChapter(buf.readVarInt(), readId(buf), readText(buf, MAX_TITLE), readOptionalId(buf),
                        buf.readVarInt()));

        @Override
        public Type<UpdateChapter> type() {
            return TYPE;
        }
    }

    /** {@code CustomQuests.deleteChapter(chapter)}. */
    public record DeleteChapter(int requestId, Identifier chapter) implements CustomPacketPayload {
        public static final Type<DeleteChapter> TYPE = payloadType("delete_chapter");
        public static final StreamCodec<FriendlyByteBuf, DeleteChapter> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.requestId);
                    writeId(buf, p.chapter);
                },
                buf -> new DeleteChapter(buf.readVarInt(), readId(buf)));

        @Override
        public Type<DeleteChapter> type() {
            return TYPE;
        }
    }

    // --- quests -----------------------------------------------------------------------------------

    /** {@code CustomQuests.createQuest(chapter, title, objectives, x, y)}; objectives as codec JSON. */
    public record CreateQuest(int requestId, Identifier chapter, String title, String objectivesJson, int x, int y)
            implements CustomPacketPayload {
        public static final Type<CreateQuest> TYPE = payloadType("create_quest");
        public static final StreamCodec<FriendlyByteBuf, CreateQuest> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.requestId);
                    writeId(buf, p.chapter);
                    writeText(buf, p.title, MAX_TITLE);
                    writeText(buf, p.objectivesJson, MAX_JSON);
                    buf.writeVarInt(p.x);
                    buf.writeVarInt(p.y);
                },
                buf -> new CreateQuest(buf.readVarInt(), readId(buf), readText(buf, MAX_TITLE), readText(buf, MAX_JSON),
                        buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<CreateQuest> type() {
            return TYPE;
        }
    }

    /** {@code CustomQuests.updateQuest(quest, title, description, icon)}. */
    public record UpdateQuest(int requestId, Identifier quest, String title, String description,
                              Optional<Identifier> icon) implements CustomPacketPayload {
        public static final Type<UpdateQuest> TYPE = payloadType("update_quest");
        public static final StreamCodec<FriendlyByteBuf, UpdateQuest> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.requestId);
                    writeId(buf, p.quest);
                    writeText(buf, p.title, MAX_TITLE);
                    writeText(buf, p.description, MAX_DESCRIPTION);
                    writeOptionalId(buf, p.icon);
                },
                buf -> new UpdateQuest(buf.readVarInt(), readId(buf), readText(buf, MAX_TITLE),
                        readText(buf, MAX_DESCRIPTION), readOptionalId(buf)));

        @Override
        public Type<UpdateQuest> type() {
            return TYPE;
        }
    }

    /** {@code CustomQuests.deleteQuest(quest)}. */
    public record DeleteQuest(int requestId, Identifier quest) implements CustomPacketPayload {
        public static final Type<DeleteQuest> TYPE = payloadType("delete_quest");
        public static final StreamCodec<FriendlyByteBuf, DeleteQuest> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.requestId);
                    writeId(buf, p.quest);
                },
                buf -> new DeleteQuest(buf.readVarInt(), readId(buf)));

        @Override
        public Type<DeleteQuest> type() {
            return TYPE;
        }
    }

    /** {@code CustomQuests.moveNode(quest, x, y)} (grid units; the server checks the range). */
    public record MoveNode(int requestId, Identifier quest, int x, int y) implements CustomPacketPayload {
        public static final Type<MoveNode> TYPE = payloadType("move_node");
        public static final StreamCodec<FriendlyByteBuf, MoveNode> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.requestId);
                    writeId(buf, p.quest);
                    buf.writeVarInt(p.x);
                    buf.writeVarInt(p.y);
                },
                buf -> new MoveNode(buf.readVarInt(), readId(buf), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<MoveNode> type() {
            return TYPE;
        }
    }

    /** {@code CustomQuests.setPrerequisites(quest, prerequisites)} — the full new list (≤ 16). */
    public record SetPrerequisites(int requestId, Identifier quest, List<Identifier> prerequisites)
            implements CustomPacketPayload {
        public static final Type<SetPrerequisites> TYPE = payloadType("set_prerequisites");
        public static final StreamCodec<FriendlyByteBuf, SetPrerequisites> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    if (p.prerequisites.size() > MAX_PREREQUISITES) {
                        throw new EncoderException("Too many prerequisites");
                    }
                    buf.writeVarInt(p.requestId);
                    writeId(buf, p.quest);
                    buf.writeVarInt(p.prerequisites.size());
                    p.prerequisites.forEach(id -> writeId(buf, id));
                },
                buf -> {
                    int requestId = buf.readVarInt();
                    Identifier quest = readId(buf);
                    int count = readCount(buf, MAX_PREREQUISITES);
                    List<Identifier> ids = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        ids.add(readId(buf));
                    }
                    return new SetPrerequisites(requestId, quest, List.copyOf(ids));
                });

        @Override
        public Type<SetPrerequisites> type() {
            return TYPE;
        }
    }

    /** {@code CustomQuests.setObjectives(quest, objectives)}; objectives as codec JSON. */
    public record SetObjectives(int requestId, Identifier quest, String objectivesJson) implements CustomPacketPayload {
        public static final Type<SetObjectives> TYPE = payloadType("set_objectives");
        public static final StreamCodec<FriendlyByteBuf, SetObjectives> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.requestId);
                    writeId(buf, p.quest);
                    writeText(buf, p.objectivesJson, MAX_JSON);
                },
                buf -> new SetObjectives(buf.readVarInt(), readId(buf), readText(buf, MAX_JSON)));

        @Override
        public Type<SetObjectives> type() {
            return TYPE;
        }
    }

    /** {@code CustomQuests.setRewards(quest, rewards, escrowCopies)}; rewards as codec JSON. */
    public record SetRewards(int requestId, Identifier quest, String rewardsJson, int escrowCopies)
            implements CustomPacketPayload {
        public static final Type<SetRewards> TYPE = payloadType("set_rewards");
        public static final StreamCodec<FriendlyByteBuf, SetRewards> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.requestId);
                    writeId(buf, p.quest);
                    writeText(buf, p.rewardsJson, MAX_JSON);
                    buf.writeVarInt(p.escrowCopies);
                },
                buf -> new SetRewards(buf.readVarInt(), readId(buf), readText(buf, MAX_JSON), buf.readVarInt()));

        @Override
        public Type<SetRewards> type() {
            return TYPE;
        }
    }

    /** {@code CustomQuests.setRepeatable(quest, repeatable, cooldownSeconds)}. */
    public record SetRepeatable(int requestId, Identifier quest, boolean repeatable, int cooldownSeconds)
            implements CustomPacketPayload {
        public static final Type<SetRepeatable> TYPE = payloadType("set_repeatable");
        public static final StreamCodec<FriendlyByteBuf, SetRepeatable> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.requestId);
                    writeId(buf, p.quest);
                    buf.writeBoolean(p.repeatable);
                    buf.writeVarInt(p.cooldownSeconds);
                },
                buf -> new SetRepeatable(buf.readVarInt(), readId(buf), buf.readBoolean(), buf.readVarInt()));

        @Override
        public Type<SetRepeatable> type() {
            return TYPE;
        }
    }

    // --- the reply --------------------------------------------------------------------------------

    /**
     * Server &rarr; client: the outcome of one editor request, shown as a toast. Carries only what
     * {@link EditResult} carries — a translation key plus plain arguments (ids and numbers), never author
     * text and never a player.
     */
    public record Result(int requestId, boolean ok, Optional<Identifier> id, String key, List<String> args)
            implements CustomPacketPayload {
        public static final Type<Result> TYPE = payloadType("result");
        public static final StreamCodec<FriendlyByteBuf, Result> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.requestId);
                    buf.writeBoolean(p.ok);
                    writeOptionalId(buf, p.id);
                    writeText(buf, p.key, MAX_KEY);
                    int count = Math.min(MAX_ARGS, p.args.size());
                    buf.writeVarInt(count);
                    for (int index = 0; index < count; index++) {
                        String arg = p.args.get(index);
                        writeText(buf, arg.length() > MAX_ARG ? arg.substring(0, MAX_ARG) : arg, MAX_ARG);
                    }
                },
                buf -> {
                    int requestId = buf.readVarInt();
                    boolean ok = buf.readBoolean();
                    Optional<Identifier> id = readOptionalId(buf);
                    String key = readText(buf, MAX_KEY);
                    if (!key.isEmpty() && !KEY.matcher(key).matches()) {
                        throw new DecoderException("Editor result carries a malformed key");
                    }
                    int count = readCount(buf, MAX_ARGS);
                    List<String> args = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        args.add(readText(buf, MAX_ARG));
                    }
                    return new Result(requestId, ok, id, key, List.copyOf(args));
                });

        /** The wire form of a server-side result. */
        public static Result of(int requestId, EditResult result) {
            return new Result(requestId, result.ok(), result.id(), result.key() == null ? "" : result.key(),
                    result.args());
        }

        /** Back to an {@link EditResult} (for the message). */
        public EditResult toEditResult() {
            if (ok) {
                return new EditResult(true, id, null, List.of());
            }
            return new EditResult(false, id, key.isEmpty() ? EditResult.BAD_REQUEST : key, args);
        }

        @Override
        public Type<Result> type() {
            return TYPE;
        }
    }
}
