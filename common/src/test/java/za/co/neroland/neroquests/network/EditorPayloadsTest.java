package za.co.neroland.neroquests.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

import org.junit.jupiter.api.Test;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.custom.EditResult;

/** E1: the editor packets round-trip and refuse out-of-bounds input on decode. */
class EditorPayloadsTest {

    private static final Identifier CHAPTER = Identifier.fromNamespaceAndPath("custom", "abcd2345");
    private static final Identifier QUEST = Identifier.fromNamespaceAndPath("custom", "abcd2345/efgh6723");

    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T value) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buf, value);
        T decoded = codec.decode(buf);
        assertEquals(0, buf.readableBytes(), "decoder must consume the whole payload");
        return decoded;
    }

    @Test
    void everyRequestRoundTrips() {
        Optional<Identifier> icon = Optional.of(Identifier.withDefaultNamespace("stone"));
        List<Object> values = List.of(
                roundTrip(EditorPayloads.CreateChapter.STREAM_CODEC, new EditorPayloads.CreateChapter(1, "Mine", icon)),
                roundTrip(EditorPayloads.UpdateChapter.STREAM_CODEC,
                        new EditorPayloads.UpdateChapter(2, CHAPTER, "Mine", Optional.empty(), -3)),
                roundTrip(EditorPayloads.DeleteChapter.STREAM_CODEC, new EditorPayloads.DeleteChapter(3, CHAPTER)),
                roundTrip(EditorPayloads.CreateQuest.STREAM_CODEC,
                        new EditorPayloads.CreateQuest(4, CHAPTER, "Q", "[{\"type\":\"x:y\"}]", -512, 512)),
                roundTrip(EditorPayloads.UpdateQuest.STREAM_CODEC,
                        new EditorPayloads.UpdateQuest(5, QUEST, "Q", "Long text", icon)),
                roundTrip(EditorPayloads.DeleteQuest.STREAM_CODEC, new EditorPayloads.DeleteQuest(6, QUEST)),
                roundTrip(EditorPayloads.MoveNode.STREAM_CODEC, new EditorPayloads.MoveNode(7, QUEST, 3, -4)),
                roundTrip(EditorPayloads.SetPrerequisites.STREAM_CODEC,
                        new EditorPayloads.SetPrerequisites(8, QUEST, List.of(CHAPTER, QUEST))),
                roundTrip(EditorPayloads.SetObjectives.STREAM_CODEC, new EditorPayloads.SetObjectives(9, QUEST, "[]")),
                roundTrip(EditorPayloads.SetRewards.STREAM_CODEC, new EditorPayloads.SetRewards(10, QUEST, "[]", 2)),
                roundTrip(EditorPayloads.SetRepeatable.STREAM_CODEC,
                        new EditorPayloads.SetRepeatable(11, QUEST, true, 3600)));
        assertEquals(new EditorPayloads.CreateChapter(1, "Mine", icon), values.get(0));
        assertEquals(new EditorPayloads.UpdateChapter(2, CHAPTER, "Mine", Optional.empty(), -3), values.get(1));
        assertEquals(new EditorPayloads.MoveNode(7, QUEST, 3, -4), values.get(6));
        assertEquals(new EditorPayloads.SetPrerequisites(8, QUEST, List.of(CHAPTER, QUEST)), values.get(7));
        assertEquals(new EditorPayloads.SetRepeatable(11, QUEST, true, 3600), values.get(10));
    }

    @Test
    void overlongTextIsRefusedBothWays() {
        String tooLong = "x".repeat(EditorPayloads.MAX_TITLE + 1);
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        assertThrows(EncoderException.class, () -> EditorPayloads.CreateChapter.STREAM_CODEC.encode(out,
                new EditorPayloads.CreateChapter(1, tooLong, Optional.empty())));

        // A hand-made packet that skips the encoder's check is still refused by the decoder.
        FriendlyByteBuf forged = new FriendlyByteBuf(Unpooled.buffer());
        forged.writeVarInt(1);
        forged.writeUtf(tooLong, Short.MAX_VALUE);
        forged.writeBoolean(false);
        assertThrows(DecoderException.class, () -> EditorPayloads.CreateChapter.STREAM_CODEC.decode(forged));

        FriendlyByteBuf json = new FriendlyByteBuf(Unpooled.buffer());
        json.writeVarInt(1);
        json.writeUtf(QUEST.toString());
        json.writeUtf("[" + " ".repeat(EditorPayloads.MAX_JSON) + "]", EditorPayloads.MAX_JSON * 2);
        assertThrows(DecoderException.class, () -> EditorPayloads.SetObjectives.STREAM_CODEC.decode(json));
    }

    @Test
    void listSizesAreBounded() {
        List<Identifier> many = new ArrayList<>();
        for (int index = 0; index <= EditorPayloads.MAX_PREREQUISITES; index++) {
            many.add(Identifier.fromNamespaceAndPath("custom", "a/q" + index));
        }
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        assertThrows(EncoderException.class, () -> EditorPayloads.SetPrerequisites.STREAM_CODEC.encode(out,
                new EditorPayloads.SetPrerequisites(1, QUEST, many)));

        FriendlyByteBuf forged = new FriendlyByteBuf(Unpooled.buffer());
        forged.writeVarInt(1);
        forged.writeUtf(QUEST.toString());
        forged.writeVarInt(many.size());
        many.forEach(id -> forged.writeUtf(id.toString()));
        assertThrows(DecoderException.class, () -> EditorPayloads.SetPrerequisites.STREAM_CODEC.decode(forged));

        FriendlyByteBuf negative = new FriendlyByteBuf(Unpooled.buffer());
        negative.writeVarInt(1);
        negative.writeUtf(QUEST.toString());
        negative.writeVarInt(-1);
        assertThrows(DecoderException.class, () -> EditorPayloads.SetPrerequisites.STREAM_CODEC.decode(negative));
    }

    @Test
    void idsMustParse() {
        FriendlyByteBuf forged = new FriendlyByteBuf(Unpooled.buffer());
        forged.writeVarInt(1);
        forged.writeUtf("Not An Id!");
        assertThrows(DecoderException.class, () -> EditorPayloads.DeleteQuest.STREAM_CODEC.decode(forged));

        FriendlyByteBuf longId = new FriendlyByteBuf(Unpooled.buffer());
        longId.writeVarInt(1);
        longId.writeUtf("custom:" + "a".repeat(EditorPayloads.MAX_ID), Short.MAX_VALUE);
        assertThrows(DecoderException.class, () -> EditorPayloads.DeleteQuest.STREAM_CODEC.decode(longId));
    }

    @Test
    void resultCarriesKeyAndArgsOnly() {
        EditorPayloads.Result refused = roundTrip(EditorPayloads.Result.STREAM_CODEC,
                EditorPayloads.Result.of(5, EditResult.error(EditResult.CAP_XP, "500")));
        assertEquals(5, refused.requestId());
        assertEquals(EditResult.CAP_XP, refused.key());
        assertEquals(List.of("500"), refused.args());
        assertEquals(EditResult.CAP_XP, refused.toEditResult().key());

        EditorPayloads.Result saved = roundTrip(EditorPayloads.Result.STREAM_CODEC,
                EditorPayloads.Result.of(6, EditResult.ok(QUEST)));
        assertTrue(saved.ok());
        assertEquals(Optional.of(QUEST), saved.id());
        assertNull(saved.toEditResult().key());

        FriendlyByteBuf forged = new FriendlyByteBuf(Unpooled.buffer());
        forged.writeVarInt(1);
        forged.writeBoolean(false);
        forged.writeBoolean(false);
        forged.writeUtf("§cNot a key");
        forged.writeVarInt(0);
        assertThrows(DecoderException.class, () -> EditorPayloads.Result.STREAM_CODEC.decode(forged));

        FriendlyByteBuf tooMany = new FriendlyByteBuf(Unpooled.buffer());
        tooMany.writeVarInt(1);
        tooMany.writeBoolean(false);
        tooMany.writeBoolean(false);
        tooMany.writeUtf(EditResult.INVALID);
        tooMany.writeVarInt(EditorPayloads.MAX_ARGS + 1);
        assertThrows(DecoderException.class, () -> EditorPayloads.Result.STREAM_CODEC.decode(tooMany));
    }

    @Test
    void jsonListsMustBeArrays() {
        assertNull(EditorRequests.parseArray("{}"));
        assertNull(EditorRequests.parseArray("not json"));
        assertNull(EditorRequests.parseArray(""));
        assertEquals(2, EditorRequests.parseArray("[{},{}]").size());
    }
}
