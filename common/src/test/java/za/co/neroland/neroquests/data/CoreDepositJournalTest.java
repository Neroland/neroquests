package za.co.neroland.neroquests.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.resources.Identifier;

/** J2 (RT-14): Core deposits are journaled too, and a left-over one is never repeated (Core takes no key). */
class CoreDepositJournalTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @TempDir
    Path dir;

    @Test
    void aLeftOverCoreDepositIsCountedNotRepeated() {
        Path file = dir.resolve(PayoutJournal.FILE_NAME);
        PayoutJournal journal = new PayoutJournal(file);
        OwedReward economy = new OwedReward("nq:1:neroquests:a", Identifier.parse("nerolandcore:credits"), 10L,
                "quest_reward", Identifier.parse("neroquests:a"), 1L, 0);
        OwedReward core = new OwedReward("nq:2:neroquests:b", Identifier.parse("mypack:scrip"), 5L,
                "quest_reward", Identifier.parse("neroquests:b"), 2L, 0);
        assertTrue(journal.begin(PLAYER, economy));
        assertTrue(journal.begin(new PayoutJournal.Entry(PLAYER, core, true)));

        PayoutJournal reopened = new PayoutJournal(file); // as after a crash
        assertEquals(List.of(new PayoutJournal.Entry(PLAYER, economy, false),
                new PayoutJournal.Entry(PLAYER, core, true)), reopened.pending(), "the path survives the file");
        List<String> retried = new ArrayList<>();
        List<String> unsettled = new ArrayList<>();
        int found = PayoutJournal.recover(reopened, new PayoutJournal.Settler() {
            @Override
            public boolean retry(UUID player, OwedReward reward) {
                retried.add(reward.requestKey());
                return true;
            }

            @Override
            public void keep(UUID player, OwedReward reward) {
                throw new AssertionError("nothing is kept");
            }

            @Override
            public void unsettled(UUID player, OwedReward reward) {
                unsettled.add(reward.requestKey());
            }
        });
        assertEquals(2, found);
        assertEquals(List.of(economy.requestKey()), retried, "only the keyed payout is asked again");
        assertEquals(List.of(core.requestKey()), unsettled);
        assertTrue(reopened.pending().isEmpty());
    }
}
