package za.co.neroland.neroquests.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroquests.compat.neroeconomy.EconomyCompat;

/** C4/C6: an economy objective's "earned" never counts NeroQuests' own payouts. */
class EconomySelfPaidTest {

    @Test
    void ownPayoutsAreExcluded() {
        assertFalse(EconomyCompat.countsAsEarned("neroquests"));
        assertFalse(EconomyCompat.countsAsEarned(null));
        assertTrue(EconomyCompat.countsAsEarned("nerocolonies"));
        assertTrue(EconomyCompat.countsAsEarned("neroeconomy"));
    }

    @Test
    void reasonsMatchTheClaim() {
        assertTrue("quest_reward".equals(EconomyCompat.REASON_QUEST));
        assertTrue("daily_contract".equals(EconomyCompat.REASON_CONTRACT));
    }
}
