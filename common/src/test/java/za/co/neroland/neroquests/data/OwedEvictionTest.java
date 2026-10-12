package za.co.neroland.neroquests.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.reward.OwedDelivery;

/** J2: RT-10 (custom rewards never evict spine/pack/refund entries) and RT-17 (take before hand-over). */
class OwedEvictionTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
    private static final Identifier IRON = Identifier.parse("minecraft:iron_ingot");
    private static final Identifier SPINE = Identifier.parse("neroquests:groundwork/first");
    private static final Identifier CUSTOM = Identifier.parse("custom:chap2345/aaaa2222");

    private static int next;

    private static OwedReward item(Identifier source, String reason) {
        next++;
        return OwedReward.item("nq:i:" + next + ":" + source, IRON, 1, new CompoundTag(), reason, source, next);
    }

    @Test
    void customRewardsCannotEvictProtectedEntries() {
        QuestProgressState state = new QuestProgressState();
        for (int i = 0; i < 32; i++) {
            state.addOwed(PLAYER, item(SPINE, "quest_reward"));
            state.addOwed(PLAYER, item(CUSTOM, QuestProgressState.REASON_ESCROW_REFUND));
        }
        assertTrue(state.isOwedFull(PLAYER));
        List<OwedReward> before = state.owed(PLAYER);
        assertEquals(1, state.addOwed(PLAYER, item(CUSTOM, "quest_reward")), "the new custom entry is dropped");
        assertEquals(before, state.owed(PLAYER), "nothing protected was evicted");
    }

    @Test
    void protectedEntriesEvictCustomRewardsFirst() {
        QuestProgressState state = new QuestProgressState();
        OwedReward oldestSpine = item(SPINE, "quest_reward");
        state.addOwed(PLAYER, oldestSpine);
        OwedReward custom = item(CUSTOM, "quest_reward");
        state.addOwed(PLAYER, custom);
        for (int i = 0; i < 62; i++) {
            state.addOwed(PLAYER, item(SPINE, "quest_reward"));
        }
        assertEquals(1, state.addOwed(PLAYER, item(SPINE, "quest_reward")));
        assertFalse(state.owed(PLAYER).contains(custom), "the custom reward made room");
        assertTrue(state.owed(PLAYER).contains(oldestSpine), "the oldest spine entry stayed");
        assertEquals(1, state.addOwed(PLAYER, item(SPINE, "quest_reward")));
        assertFalse(state.owed(PLAYER).contains(oldestSpine), "without custom entries the oldest goes, as before");
    }

    @Test
    void customRewardsMayEvictOlderCustomRewards() {
        QuestProgressState state = new QuestProgressState();
        OwedReward firstCustom = item(CUSTOM, "quest_reward");
        state.addOwed(PLAYER, firstCustom);
        for (int i = 0; i < 63; i++) {
            state.addOwed(PLAYER, item(SPINE, "quest_reward"));
        }
        assertEquals(1, state.addOwed(PLAYER, item(CUSTOM, "quest_reward")));
        assertFalse(state.owed(PLAYER).contains(firstCustom));
        assertEquals(64, state.owed(PLAYER).size());
    }

    @Test
    void itemsLeaveTheQueueBeforeHandOverAndComeBackOnFailure() {
        QuestProgressState state = new QuestProgressState();
        OwedReward first = item(SPINE, "quest_reward").withAmount(10);
        OwedReward second = item(SPINE, "quest_reward");
        state.addOwed(PLAYER, first);
        state.addOwed(PLAYER, second);
        boolean[] sawEntryDuringHandOver = {true};
        assertThrows(IllegalStateException.class, () -> OwedDelivery.deliverTo(state, PLAYER, null,
                new OwedDelivery.Receiver() {
                    @Override
                    public int acceptItems(Identifier id, int count, CompoundTag components) {
                        sawEntryDuringHandOver[0] = state.owed(PLAYER).contains(first);
                        throw new IllegalStateException("inventory blew up");
                    }

                    @Override
                    public void acceptXp(int points) {
                    }
                }));
        assertFalse(sawEntryDuringHandOver[0], "the entry was out of the queue while being handed over");
        assertEquals(List.of(first, second), state.owed(PLAYER), "restored in its old place");
    }

    @Test
    void aPartialHandOverPutsTheRestBackInPlace() {
        QuestProgressState state = new QuestProgressState();
        OwedReward first = item(SPINE, "quest_reward").withAmount(10);
        OwedReward second = item(SPINE, "quest_reward");
        state.addOwed(PLAYER, first);
        state.addOwed(PLAYER, second);
        int index = state.takeOwedByKey(PLAYER, first.requestKey());
        assertEquals(0, index);
        assertEquals(-1, state.takeOwedByKey(PLAYER, first.requestKey()), "cannot be taken twice");
        state.settleTaken(PLAYER, index, first, first.withAmount(4));
        assertEquals(4L, state.owed(PLAYER).get(0).amount());
        assertEquals(second, state.owed(PLAYER).get(1));
    }
}
