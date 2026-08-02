package za.co.neroland.neroquests.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.data.QuestProgress;
import za.co.neroland.neroquests.data.QuestProgressState;

/**
 * Server &rarr; client snapshot of quest progress <em>for the receiving player</em>: their own
 * per-quest objective counters and completion timestamps, plus the shared
 * {@code scope: server} section. Sent on join, after a datapack reload, and whenever an engine
 * evaluation pass changed something the player can see.
 *
 * <p><b>Privacy (POPIA/GDPR).</b> A player's payload carries <em>only their own</em> progress plus
 * the server-scoped section, which is world state and holds no identifiers at all — never another
 * player's rows, never a UUID, never a name. Because the snapshot is implicitly "yours", the
 * player's own UUID is not on the wire either: the client already knows who it is.
 *
 * <p><b>Snapshot-as-delta.</b> A change sends the whole (small) snapshot rather than a diff. A
 * player's rows are a handful of quest ids with a few integers each, so a full snapshot costs
 * about as much as a diff would and removes an entire class of client/server drift bugs. The
 * engine already collapses this to at most one payload per player per evaluation pass.
 *
 * <p>TODO: if a pack ever makes the snapshot large enough to matter, switch to true deltas
 * (changed rows only, with a generation counter so the client can detect a missed update and ask
 * for a resync). The wire format is versioned by the payload id, so that is a drop-in change.
 */
public record QuestProgressPayload(List<Row> own, List<Row> server) implements CustomPacketPayload {

    /** An empty snapshot. */
    public static final QuestProgressPayload EMPTY = new QuestProgressPayload(List.of(), List.of());

    public static final Type<QuestProgressPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "quest_progress"));

    public static final StreamCodec<RegistryFriendlyByteBuf, QuestProgressPayload> STREAM_CODEC =
            StreamCodec.of(QuestProgressPayload::write, QuestProgressPayload::read);

    /** Upper bounds, so a malformed packet cannot pre-allocate without limit. */
    private static final int MAX_ROWS = 32_768;
    private static final int MAX_COUNTERS = 256;

    /**
     * One quest's progress on the wire.
     *
     * @param quest       the quest id
     * @param counters    per-objective counters, by objective index
     * @param completedAt epoch millis of completion, or {@code 0} while incomplete
     */
    public record Row(Identifier quest, List<Integer> counters, long completedAt) {

        public QuestProgress toProgress() {
            return new QuestProgress(counters, completedAt);
        }

        public static Row of(Identifier quest, QuestProgress progress) {
            return new Row(quest, progress.counters(), progress.completedAt());
        }
    }

    /** Builds {@code player}'s snapshot: their own rows plus the shared server-scoped section. */
    public static QuestProgressPayload of(MinecraftServer server, ServerPlayer player) {
        QuestProgressState state = QuestProgressState.get(server);
        return new QuestProgressPayload(
                rows(state.playerProgress(player.getUUID())),
                rows(state.allServerProgress()));
    }

    private static List<Row> rows(Map<Identifier, QuestProgress> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        List<Row> out = new ArrayList<>(source.size());
        source.forEach((quest, progress) -> out.add(Row.of(quest, progress)));
        return List.copyOf(out);
    }

    private static void write(RegistryFriendlyByteBuf buf, QuestProgressPayload payload) {
        writeRows(buf, payload.own);
        writeRows(buf, payload.server);
    }

    private static QuestProgressPayload read(RegistryFriendlyByteBuf buf) {
        return new QuestProgressPayload(readRows(buf), readRows(buf));
    }

    private static void writeRows(RegistryFriendlyByteBuf buf, List<Row> rows) {
        buf.writeVarInt(rows.size());
        for (Row row : rows) {
            buf.writeUtf(row.quest().toString());
            List<Integer> counters = row.counters();
            buf.writeVarInt(counters.size());
            for (Integer counter : counters) {
                buf.writeVarInt(counter == null ? 0 : counter);
            }
            buf.writeVarLong(row.completedAt());
        }
    }

    private static List<Row> readRows(RegistryFriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_ROWS);
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Identifier quest = Identifier.tryParse(buf.readUtf());
            int counterCount = Math.min(buf.readVarInt(), MAX_COUNTERS);
            List<Integer> counters = new ArrayList<>(counterCount);
            for (int c = 0; c < counterCount; c++) {
                counters.add(buf.readVarInt());
            }
            long completedAt = buf.readVarLong();
            if (quest != null) {
                rows.add(new Row(quest, List.copyOf(counters), completedAt));
            }
        }
        return List.copyOf(rows);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
