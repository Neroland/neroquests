package za.co.neroland.neroquests.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

/** Developer-pass owed-queue fixes (AUD-05/AUD-22 rows): cap reporting, permanent flag, quiet updates. */
class OwedQueueTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final Identifier CREDITS = Identifier.fromNamespaceAndPath("nerolandcore", "credits");
    private static final Identifier SOURCE = Identifier.fromNamespaceAndPath("neroquests", "test/owed");

    private static OwedReward owed(long amount) {
        return new OwedReward("nq:" + amount + ":" + SOURCE, CREDITS, amount, "quest_reward", SOURCE, 1L, 0);
    }

    @Test
    void capReportsHowManyWereDropped() {
        QuestProgressState state = new QuestProgressState();
        for (int i = 1; i <= 64; i++) {
            assertEquals(0, state.addOwed(PLAYER, owed(i)));
        }
        assertEquals(1, state.addOwed(PLAYER, owed(65)));
        assertEquals(64, state.owed(PLAYER).size());
        assertEquals(2L, state.owed(PLAYER).get(0).amount(), "the oldest entry is the one dropped");
    }

    @Test
    void permanentFlagRoundTripsAndDefaultsToFalse() {
        OwedReward permanent = owed(5).asPermanent().withRequestKey("nq:9:" + SOURCE);
        JsonElement json = OwedReward.CODEC.encodeStart(JsonOps.INSTANCE, permanent).getOrThrow();
        OwedReward decoded = OwedReward.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(permanent, decoded);
        OwedReward legacy = OwedReward.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"currency\":\"nerolandcore:credits\",\"amount\":3,\"source\":\"neroquests:x\"}")).getOrThrow();
        assertFalse(legacy.permanent());
        assertTrue(legacy.requestKey().isEmpty());
        assertEquals(1, legacy.retried().attempts());
        assertTrue(permanent.retried().permanent(), "retrying keeps the permanent mark");
    }

    @Test
    void refusedRetryDoesNotCountAsActivity() throws InterruptedException {
        QuestProgressState state = new QuestProgressState();
        OwedReward reward = owed(7);
        state.addOwed(PLAYER, reward);
        long stamped = state.export(PLAYER).get("last_updated").getAsLong();
        Thread.sleep(5L);
        state.updateOwed(PLAYER, reward, reward.retried(), false);
        assertEquals(stamped, state.export(PLAYER).get("last_updated").getAsLong());
        assertEquals(1, state.owed(PLAYER).get(0).attempts());
    }
}
