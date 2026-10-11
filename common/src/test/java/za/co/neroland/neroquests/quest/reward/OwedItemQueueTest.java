package za.co.neroland.neroquests.quest.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.data.OwedReward;
import za.co.neroland.neroquests.data.QuestProgressState;

/** AUD-05 / R-E1: kept items and experience — queueing, delivery, overflow, cap, export. No MC bootstrap. */
class OwedItemQueueTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000005");
    private static final Identifier IRON = Identifier.fromNamespaceAndPath("minecraft", "iron_ingot");
    private static final Identifier QUEST = Identifier.fromNamespaceAndPath("neroquests", "test/kept");
    private static final Identifier OTHER = Identifier.fromNamespaceAndPath("neroquests", "test/other");

    /** A receiver with room for {@code room} items, recording what it took. */
    private static final class Inventory implements OwedDelivery.Receiver {
        int room;
        int items;
        int xp;
        final List<Identifier> seen = new ArrayList<>();

        Inventory(int room) {
            this.room = room;
        }

        @Override
        public int acceptItems(Identifier id, int count, CompoundTag components) {
            seen.add(id);
            int taken = Math.min(room, count);
            room -= taken;
            items += taken;
            return taken;
        }

        @Override
        public void acceptXp(int points) {
            xp += points;
        }
    }

    private static OwedReward item(QuestProgressState state, Identifier source, int count) {
        return OwedReward.item(state.nextRequestKey(OwedDelivery.ITEM_KEY_PREFIX, source), IRON, count,
                new CompoundTag(), "quest_reward", source, 1L);
    }

    @Test
    void offlineItemRewardIsKeptThenDeliveredOnce() {
        QuestProgressState state = new QuestProgressState();
        state.addOwed(PLAYER, item(state, QUEST, 16));
        state.addOwed(PLAYER, OwedReward.xp(state.nextRequestKey(OwedDelivery.XP_KEY_PREFIX, QUEST), 50,
                "quest_reward", QUEST, 2L));
        assertEquals(2, OwedDelivery.pendingBodyBound(state, PLAYER));

        Inventory inventory = new Inventory(1000);
        List<OwedDelivery.Delivered> delivered = OwedDelivery.deliverTo(state, PLAYER, null, inventory);
        assertEquals(2, delivered.size());
        assertEquals(16, inventory.items);
        assertEquals(50, inventory.xp);
        assertTrue(state.owed(PLAYER).isEmpty());

        // Idempotent: a second delivery hands over nothing.
        assertTrue(OwedDelivery.deliverTo(state, PLAYER, null, inventory).isEmpty());
        assertEquals(16, inventory.items);
        assertEquals(50, inventory.xp);
    }

    @Test
    void fullInventoryKeepsTheOverflowAndDeliversInPart() {
        QuestProgressState state = new QuestProgressState();
        state.addOwed(PLAYER, item(state, QUEST, 100));

        assertTrue(OwedDelivery.deliverTo(state, PLAYER, null, new Inventory(0)).isEmpty(), "no room: stays");
        assertEquals(100L, state.owed(PLAYER).get(0).amount());

        Inventory some = new Inventory(30);
        List<OwedDelivery.Delivered> part = OwedDelivery.deliverTo(state, PLAYER, null, some);
        assertEquals(1, part.size());
        assertEquals(30L, part.get(0).amount());
        assertEquals(70L, state.owed(PLAYER).get(0).amount(), "the rest stays under the same key");

        OwedDelivery.deliverTo(state, PLAYER, null, new Inventory(1000));
        assertTrue(state.owed(PLAYER).isEmpty());
    }

    @Test
    void deliveryIsOldestFirstAndFiltersBySourceAndSkipsCurrency() {
        QuestProgressState state = new QuestProgressState();
        state.addOwed(PLAYER, new OwedReward("nq:1:" + QUEST, Identifier.fromNamespaceAndPath("nerolandcore",
                "credits"), 10L, "quest_reward", QUEST, 1L, 0));
        state.addOwed(PLAYER, item(state, OTHER, 5));
        state.addOwed(PLAYER, item(state, QUEST, 7));

        Inventory inventory = new Inventory(1000);
        List<OwedDelivery.Delivered> delivered = OwedDelivery.deliverTo(state, PLAYER, QUEST, inventory);
        assertEquals(1, delivered.size());
        assertEquals(7, inventory.items);
        assertEquals(2, state.owed(PLAYER).size(), "currency and the other quest's entry stay");

        Inventory small = new Inventory(3);
        OwedDelivery.deliverTo(state, PLAYER, null, small);
        assertEquals(3, small.items, "oldest item entry first, partly");
        assertTrue(state.owed(PLAYER).get(0).isCurrency());
        assertEquals(2L, state.owed(PLAYER).get(1).amount());
    }

    @Test
    void capCoversItemEntriesToo() {
        QuestProgressState state = new QuestProgressState();
        for (int i = 0; i < 64; i++) {
            assertEquals(0, state.addOwed(PLAYER, item(state, QUEST, i + 1)));
        }
        assertEquals(1, state.addOwed(PLAYER, OwedReward.xp("nq:x:1:" + QUEST, 5, "quest_reward", QUEST, 3L)));
        assertEquals(64, state.owed(PLAYER).size());
        assertEquals(2L, state.owed(PLAYER).get(0).amount(), "the oldest entry is dropped");
    }

    @Test
    void exportAndSaveIncludeItemAndXpEntries() {
        QuestProgressState state = new QuestProgressState();
        CompoundTag components = new CompoundTag();
        components.putString("note", "kept");
        state.addOwed(PLAYER, OwedReward.item("nq:i:9:" + QUEST, IRON, 12, components, "quest_reward", QUEST, 4L));
        state.addOwed(PLAYER, OwedReward.xp("nq:x:9:" + QUEST, 40, "quest_reward", QUEST, 5L));

        JsonObject export = state.export(PLAYER);
        JsonArray rows = export.getAsJsonArray("owed_rewards");
        assertEquals(2, rows.size());
        JsonObject itemRow = rows.get(0).getAsJsonObject();
        assertEquals("item", itemRow.get("type").getAsString());
        assertEquals(IRON.toString(), itemRow.get("item").getAsString());
        assertEquals(12L, itemRow.get("count").getAsLong());
        assertTrue(itemRow.has("components"));
        assertEquals("xp", rows.get(1).getAsJsonObject().get("type").getAsString());
        assertTrue(!export.toString().contains(PLAYER.toString()), "export rows carry no UUID");

        for (OwedReward reward : state.owed(PLAYER)) {
            JsonElement json = OwedReward.CODEC.encodeStart(JsonOps.INSTANCE, reward).getOrThrow();
            assertEquals(reward, OwedReward.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        }

        state.forgetPlayer(PLAYER);
        assertTrue(state.owed(PLAYER).isEmpty(), "erasure removes kept items too");
    }
}
