package za.co.neroland.neroquests.network;

import java.util.ArrayList;
import java.util.List;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * Server &rarr; client: quests that just became available to <em>the receiving player</em>, as one batch
 * (everything one sync unlocked). The client shows them as the top-left "Quest Unlocked" notification.
 *
 * <p>Ids only: the client already holds the definitions it may see (sent just before this), so title and
 * icon come from there, and a quest the client was not sent is simply not shown. {@code total} is the full
 * batch size; at most {@link #MAX_IDS} ids travel, the rest are only counted ("+N more").
 *
 * <p><b>Privacy (POPIA/GDPR):</b> addressed to one player and about that player only. No UUID, name or
 * other player's data is on the wire.
 */
public record QuestUnlockedPayload(List<Identifier> quests, int total) implements CustomPacketPayload {

    /** Most ids carried by one payload. */
    public static final int MAX_IDS = 64;

    //? if >=1.20.5 {
    public static final Type<QuestUnlockedPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "quest_unlocked"));
    //?} else {
    /*public static final Type<QuestUnlockedPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "quest_unlocked"), QuestUnlockedPayload.class);
    *///?}

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestUnlockedPayload> STREAM_CODEC =
            StreamCodec.of(QuestUnlockedPayload::write, QuestUnlockedPayload::read);

    /** A batch of unlocked quest ids (the first {@link #MAX_IDS} are kept, all are counted). */
    public static QuestUnlockedPayload of(List<Identifier> unlocked) {
        List<Identifier> ids = unlocked.size() > MAX_IDS ? unlocked.subList(0, MAX_IDS) : unlocked;
        return new QuestUnlockedPayload(List.copyOf(ids), unlocked.size());
    }

    private static void write(RegistryFriendlyByteBuf buf, QuestUnlockedPayload payload) {
        buf.writeVarInt(payload.quests.size());
        for (Identifier quest : payload.quests) {
            buf.writeUtf(quest.toString());
        }
        buf.writeVarInt(payload.total);
    }

    private static QuestUnlockedPayload read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_IDS) {
            throw new DecoderException("Bad quest unlock count " + count);
        }
        List<Identifier> quests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Identifier quest = Identifier.tryParse(buf.readUtf(256));
            if (quest != null) {
                quests.add(quest);
            }
        }
        int total = buf.readVarInt();
        return new QuestUnlockedPayload(List.copyOf(quests), Math.max(total, count));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
