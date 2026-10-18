package za.co.neroland.neroquests.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.netty.buffer.Unpooled;

import org.junit.jupiter.api.Test;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

/** MC-NEROQUESTS-1: the definitions page round-trips, and a page it cannot read is consumed and rejected, never thrown. */
class QuestDefinitionsPayloadTest {

    private static final QuestDefinitionsPayload.Entry QUEST = new QuestDefinitionsPayload.Entry(
            QuestDefinitionsPayload.Kind.QUEST, Identifier.fromNamespaceAndPath("neroquests", "story/first_steps"),
            "{\"title\":\"quest.neroquests.first_steps\",\"source\":\"spine\"}");
    private static final QuestDefinitionsPayload.Entry CHAPTER = new QuestDefinitionsPayload.Entry(
            QuestDefinitionsPayload.Kind.CHAPTER, Identifier.fromNamespaceAndPath("nerospace", "space"),
            "{\"title\":\"chapter.nerospace.space\",\"source\":\"pack\",\"mod_absent\":true}");

    private static QuestDefinitionsPayload decode(FriendlyByteBuf buf) {
        QuestDefinitionsPayload decoded = QuestDefinitionsPayload.read(buf);
        assertEquals(0, buf.readableBytes(), "decoder must consume the whole payload");
        return decoded;
    }

    @Test
    void pageRoundTrips() {
        QuestDefinitionsPayload page = new QuestDefinitionsPayload(7, 1, 3, "{\"can_author\":false}",
                List.of(QUEST, CHAPTER));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        QuestDefinitionsPayload.write(buf, page);
        QuestDefinitionsPayload decoded = decode(buf);
        assertFalse(decoded.rejected());
        assertEquals(page, decoded);
    }

    @Test
    void paginatedSyncRoundTrips() {
        List<QuestDefinitionsPayload> pages = QuestDefinitionsPayload.paginate(3, "{}", List.of(QUEST, CHAPTER),
                entry -> {
                    throw new AssertionError("nothing is oversized here");
                });
        for (QuestDefinitionsPayload page : pages) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            QuestDefinitionsPayload.write(buf, page);
            assertEquals(page, decode(buf));
        }
    }

    /** The exact Sentry case: a server on the pre-marker format (no editor block) sends to this client. */
    @Test
    void olderServerFormatIsRejectedNotThrown() {
        FriendlyByteBuf old = new FriendlyByteBuf(Unpooled.buffer());
        old.writeVarInt(1);
        old.writeVarInt(0);
        old.writeVarInt(1);
        old.writeVarInt(2);
        for (QuestDefinitionsPayload.Entry entry : List.of(QUEST, CHAPTER)) {
            old.writeByte(entry.kind().ordinal());
            old.writeUtf(entry.id().toString());
            old.writeUtf(entry.json());
        }
        assertTrue(decode(old).rejected());

        // The 0.4.x format (editor block, no marker) is rejected the same way.
        FriendlyByteBuf unmarked = new FriendlyByteBuf(Unpooled.buffer());
        unmarked.writeVarInt(1);
        unmarked.writeVarInt(0);
        unmarked.writeVarInt(1);
        unmarked.writeUtf("{}");
        unmarked.writeVarInt(0);
        assertTrue(decode(unmarked).rejected());
    }

    @Test
    void otherWireVersionGarbageAndTrailingBytesAreRejected() {
        FriendlyByteBuf newer = new FriendlyByteBuf(Unpooled.buffer());
        newer.writeInt(QuestDefinitionsPayload.WIRE_MAGIC);
        newer.writeVarInt(QuestDefinitionsPayload.WIRE_VERSION + 1);
        newer.writeBytes(new byte[] {1, 2, 3, 4, 5});
        assertTrue(decode(newer).rejected());

        FriendlyByteBuf truncated = new FriendlyByteBuf(Unpooled.buffer());
        QuestDefinitionsPayload.write(truncated, new QuestDefinitionsPayload(1, 0, 1, "{}", List.of(QUEST)));
        truncated.writerIndex(truncated.writerIndex() - 4);
        assertTrue(decode(truncated).rejected());

        FriendlyByteBuf trailing = new FriendlyByteBuf(Unpooled.buffer());
        QuestDefinitionsPayload.write(trailing, new QuestDefinitionsPayload(1, 0, 1, "{}", List.of()));
        trailing.writeByte(9);
        assertTrue(decode(trailing).rejected());

        FriendlyByteBuf badKind = new FriendlyByteBuf(Unpooled.buffer());
        badKind.writeInt(QuestDefinitionsPayload.WIRE_MAGIC);
        badKind.writeVarInt(QuestDefinitionsPayload.WIRE_VERSION);
        badKind.writeVarInt(1);
        badKind.writeVarInt(0);
        badKind.writeVarInt(1);
        badKind.writeUtf("{}");
        badKind.writeVarInt(1);
        badKind.writeByte(101);
        assertTrue(decode(badKind).rejected());

        assertTrue(decode(new FriendlyByteBuf(Unpooled.buffer())).rejected());
    }
}
