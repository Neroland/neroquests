package za.co.neroland.neroquests.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.resources.Identifier;

/**
 * J1 (Stage C gap): a payout reaches the disk with its request key before the economy is asked, so a crash
 * between the economy paying and our save is settled on the next start with the same key — never paid twice,
 * never lost.
 */
class PayoutJournalTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final Identifier CREDITS = Identifier.parse("nerolandcore:credits");
    private static final Identifier QUEST = Identifier.parse("neroquests:groundwork/what_this_book_is");

    @TempDir
    Path dir;

    private static OwedReward payout(String key, long amount) {
        return new OwedReward(key, CREDITS, amount, "quest_reward", QUEST, 1_000L, 0);
    }

    /** A stand-in for NeroEconomy's keyed payout: a key it has applied answers DUPLICATE (= ok, nothing moves). */
    private static final class Economy {
        final Set<String> applied = new HashSet<>();
        final Map<UUID, Long> balance = new HashMap<>();
        boolean refusing;

        boolean pay(UUID player, OwedReward reward) {
            if (applied.contains(reward.requestKey())) {
                return true; // DUPLICATE
            }
            if (refusing) {
                return false;
            }
            applied.add(reward.requestKey());
            balance.merge(player, Long.valueOf(reward.amount()), Long::sum);
            return true;
        }
    }

    private static PayoutJournal.Settler settler(Economy economy, List<OwedReward> owed) {
        return new PayoutJournal.Settler() {
            @Override
            public boolean retry(UUID player, OwedReward reward) {
                return economy.pay(player, reward);
            }

            @Override
            public void keep(UUID player, OwedReward reward) {
                owed.add(reward);
            }
        };
    }

    @Test
    void anInFlightPayoutSurvivesARestartWithItsKey() {
        Path file = dir.resolve("data").resolve(PayoutJournal.FILE_NAME);
        PayoutJournal journal = new PayoutJournal(file);
        assertTrue(journal.begin(PLAYER, payout("nq:17:" + QUEST, 25L)));
        assertTrue(Files.isRegularFile(file), "on disk before the economy is asked");
        // The JVM dies here. A fresh server reads the same file.
        List<PayoutJournal.Entry> left = new PayoutJournal(file).pending();
        assertEquals(1, left.size());
        assertEquals(PLAYER, left.get(0).player());
        assertEquals("nq:17:" + QUEST, left.get(0).reward().requestKey());
        assertEquals(25L, left.get(0).reward().amount());
        assertEquals(QUEST, left.get(0).reward().source());
    }

    @Test
    void aReturnedCallLeavesNothingBehind() {
        Path file = dir.resolve(PayoutJournal.FILE_NAME);
        PayoutJournal journal = new PayoutJournal(file);
        journal.begin(PLAYER, payout("nq:1:a", 5L));
        journal.begin(OTHER, payout("nq:2:b", 5L));
        journal.end("nq:1:a");
        assertEquals(1, new PayoutJournal(file).pending().size());
        journal.end("nq:2:b");
        assertFalse(Files.exists(file), "an empty journal is no file at all");
        assertTrue(new PayoutJournal(file).pending().isEmpty());
    }

    @Test
    void crashAfterTheEconomyPaidIsSettledAsDuplicateNotPaidTwice() {
        Path file = dir.resolve(PayoutJournal.FILE_NAME);
        Economy economy = new Economy();
        OwedReward reward = payout("nq:40:" + QUEST, 30L);
        new PayoutJournal(file).begin(PLAYER, reward);
        assertTrue(economy.pay(PLAYER, reward)); // applied ... then the crash, before end() and before our save
        List<OwedReward> owed = new ArrayList<>();
        assertEquals(1, PayoutJournal.recover(new PayoutJournal(file), settler(economy, owed)));
        assertEquals(30L, economy.balance.get(PLAYER).longValue(), "paid exactly once");
        assertTrue(owed.isEmpty(), "DUPLICATE counts as paid");
        assertTrue(new PayoutJournal(file).pending().isEmpty());
    }

    @Test
    void crashBeforeTheEconomyPaidIsPaidOnRecovery() {
        Path file = dir.resolve(PayoutJournal.FILE_NAME);
        Economy economy = new Economy();
        new PayoutJournal(file).begin(PLAYER, payout("nq:41:" + QUEST, 12L));
        List<OwedReward> owed = new ArrayList<>();
        PayoutJournal.recover(new PayoutJournal(file), settler(economy, owed));
        assertEquals(12L, economy.balance.get(PLAYER).longValue());
        assertTrue(owed.isEmpty());
    }

    @Test
    void stillRefusedOnRecoveryIsKeptOwedUnderTheSameKey() {
        Path file = dir.resolve(PayoutJournal.FILE_NAME);
        Economy economy = new Economy();
        economy.refusing = true;
        new PayoutJournal(file).begin(PLAYER, payout("nq:42:" + QUEST, 9L));
        List<OwedReward> owed = new ArrayList<>();
        PayoutJournal.recover(new PayoutJournal(file), settler(economy, owed));
        assertEquals(1, owed.size());
        assertEquals("nq:42:" + QUEST, owed.get(0).requestKey());
        assertTrue(new PayoutJournal(file).pending().isEmpty(), "moved into the owed queue");
        // The later owed retry uses the same key: once the economy accepts, it pays once, then answers DUPLICATE.
        economy.refusing = false;
        assertTrue(economy.pay(PLAYER, owed.get(0)));
        assertTrue(economy.pay(PLAYER, owed.get(0)));
        assertEquals(9L, economy.balance.get(PLAYER).longValue());
    }

    @Test
    void erasureDropsThePlayersEntriesAndUnreadableFilesAreEmpty() throws IOException {
        Path file = dir.resolve(PayoutJournal.FILE_NAME);
        PayoutJournal journal = new PayoutJournal(file);
        journal.begin(PLAYER, payout("nq:1:a", 5L));
        journal.begin(OTHER, payout("nq:2:b", 5L));
        journal.erase(PLAYER);
        List<PayoutJournal.Entry> left = new PayoutJournal(file).pending();
        assertEquals(1, left.size());
        assertEquals(OTHER, left.get(0).player());
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains(PLAYER.toString()));
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        assertTrue(new PayoutJournal(file).pending().isEmpty());
    }
}
