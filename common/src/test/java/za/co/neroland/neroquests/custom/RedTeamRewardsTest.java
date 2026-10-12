package za.co.neroland.neroquests.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.EditorPermissions;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestSource;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.objective.CollectItemObjective;
import za.co.neroland.neroquests.quest.objective.CustomEventObjective;
import za.co.neroland.neroquests.quest.objective.ItemTarget;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.LootTableReward;
import za.co.neroland.neroquests.quest.reward.XpReward;

/** J2 red-team fixes on the reward side: RT-03 (privilege, daily caps), RT-04 (loot tables), RT-09, RT-15. */
class RedTeamRewardsTest {

    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    private static final long DAY = 86_400_000L;
    private static final long NOON = 20_000L * DAY + DAY / 2;
    private static final ObjectiveSpec STONE = new CollectItemObjective(
            new ItemTarget(Optional.of(Identifier.parse("minecraft:stone")), Optional.empty()), 4, false);

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static EditorPermissions.Access access(boolean creative, int op, boolean allowed, boolean locked) {
        return new EditorPermissions.Access(false, true, creative, op, allowed, locked, true, 2);
    }

    // --- RT-03: Creative alone authors, but does not pay credits or tick manual objectives ---------------------

    @Test
    void creativeAloneAuthorsButIsNotPrivileged() {
        assertTrue(EditorPermissions.decide(access(true, 0, false, false)));
        assertFalse(EditorPermissions.decidePrivileged(access(true, 0, false, false)));
        assertTrue(EditorPermissions.decidePrivileged(access(false, 2, false, false)), "operator level");
        assertTrue(EditorPermissions.decidePrivileged(access(true, 0, true, false)), "allow-list");
        assertFalse(EditorPermissions.decidePrivileged(access(false, 3, true, true)), "the lock beats level 3");
        assertTrue(EditorPermissions.decidePrivileged(access(false, 4, false, true)), "level 4 passes the lock");
        assertFalse(EditorPermissions.decidePrivileged(new EditorPermissions.Access(true, true, false, 4, true, false,
                true, 2)), "never a spectator");
    }

    @Test
    void creativeOnlyManualTickIsRefused() {
        assertFalse(EditGate.mayTickManual(QuestSource.CUSTOM,
                EditorPermissions.decidePrivileged(access(true, 0, false, false)), false));
        assertTrue(EditGate.mayTickManual(QuestSource.CUSTOM,
                EditorPermissions.decidePrivileged(access(false, 2, false, false)), false));
    }

    private static CustomQuestState storeWith(List<RewardSpec> rewards) {
        CustomQuestState state = new CustomQuestState();
        state.putChapter(new CustomChapter("chap2345", "Chapter", Identifier.parse("minecraft:stone"), 0, 1L));
        state.putQuest(CustomQuest.create(Identifier.parse("custom:chap2345/aaaa2222"), "chap2345", "Paid",
                Quest.DEFAULT_ICON, 0, 0, List.of(STONE), Optional.of(A), 1L).withRewards(rewards, List.of(), 2L));
        return state;
    }

    @Test
    void anyChangeToAQuestThatPaysCreditsIsFlagged() {
        CustomQuestState before = storeWith(List.of(new XpReward(10)));
        CustomQuestState addCredits = before.copy();
        CustomQuest quest = addCredits.quest(Identifier.parse("custom:chap2345/aaaa2222")).orElseThrow();
        addCredits.putQuest(quest.withRewards(List.of(new XpReward(10),
                new CurrencyReward(CurrencyReward.DEFAULT_CURRENCY, 100L, false)), List.of(), 3L));
        assertTrue(CustomQuests.touchesCurrency(before, addCredits).isPresent(), "adding credits");

        CustomQuestState paying = storeWith(List.of(new CurrencyReward(CurrencyReward.DEFAULT_CURRENCY, 100L, false)));
        CustomQuestState moved = paying.copy();
        CustomQuest paid = moved.quest(Identifier.parse("custom:chap2345/aaaa2222")).orElseThrow();
        moved.putQuest(paid.withNode(5, 5, 4L));
        assertTrue(CustomQuests.touchesCurrency(paying, moved).isPresent(), "editing a quest that pays credits");

        CustomQuestState xpOnly = before.copy();
        CustomQuest plain = xpOnly.quest(Identifier.parse("custom:chap2345/aaaa2222")).orElseThrow();
        xpOnly.putQuest(plain.withNode(1, 1, 5L));
        assertFalse(CustomQuests.touchesCurrency(before, xpOnly).isPresent(), "items/XP quests stay open to Creative");
        assertFalse(CustomQuests.touchesCurrency(paying, paying.copy()).isPresent(), "nothing changed");
    }

    @Test
    void perRecipientDailyLimitAndUtcRollover() {
        CustomRewardPolicy.DailyBudget budget = new CustomRewardPolicy.DailyBudget();
        for (int i = 0; i < 20; i++) {
            assertTrue(budget.admitRecipient(A, NOON, 20));
        }
        assertFalse(budget.admitRecipient(A, NOON + 1, 20), "21st paid custom completion of the day");
        assertTrue(budget.admitRecipient(B, NOON + 2, 20), "per recipient");
        assertTrue(budget.admitRecipient(A, NOON + DAY, 20), "a new UTC day");
        assertTrue(budget.admitRecipient(A, NOON, 0), "0 = no limit");
        budget.forget(A);
    }

    @Test
    void worldCreditCapIsSharedAndPartial() {
        CustomRewardPolicy.DailyBudget budget = new CustomRewardPolicy.DailyBudget();
        assertEquals(1500L, budget.takeCredits(1500L, NOON, 2000L));
        assertEquals(500L, budget.takeCredits(1000L, NOON, 2000L), "only the rest of the allowance");
        assertEquals(0L, budget.takeCredits(1L, NOON, 2000L), "spent");
        assertEquals(1000L, budget.takeCredits(1000L, NOON + DAY, 2000L), "next UTC day");
        assertEquals(5000L, budget.takeCredits(5000L, NOON, 0L), "0 = no cap");
    }

    // --- RT-04: loot tables --------------------------------------------------------------------------------

    @Test
    void lootTablesMustMatchAPrefix() {
        List<String> prefixes = CustomRewardPolicy.parsePrefixes(CustomRewardPolicy.DEFAULT_LOOT_PREFIXES);
        assertEquals(List.of("minecraft:chests/", "minecraft:gameplay/fishing"), prefixes);
        assertTrue(CustomRewardPolicy.lootTableAllowed(prefixes, Identifier.parse("minecraft:chests/simple_dungeon")));
        assertTrue(CustomRewardPolicy.lootTableAllowed(prefixes, Identifier.parse("minecraft:gameplay/fishing/fish")));
        assertFalse(CustomRewardPolicy.lootTableAllowed(prefixes, Identifier.parse("minecraft:entities/wither")));
        assertFalse(CustomRewardPolicy.lootTableAllowed(prefixes, Identifier.parse("somemod:chests/boss_vault")));
        assertFalse(CustomRewardPolicy.lootTableAllowed(CustomRewardPolicy.parsePrefixes(" , "),
                Identifier.parse("minecraft:chests/simple_dungeon")), "empty setting = none");
        assertEquals(List.of("a:b/"), CustomRewardPolicy.parsePrefixes("  A:B/ ,,"));
    }

    @Test
    void lootTablesTakeAStackSlot() {
        CustomLimits limits = new CustomLimits(500, 20, 2, 500, 1000L, false);
        RewardSpec item = new ItemReward(Identifier.parse("minecraft:stone"), 1);
        RewardSpec loot = new LootTableReward(Identifier.parse("minecraft:chests/simple_dungeon"));
        assertTrue(CustomRules.rewards(List.of(item, loot), limits).isEmpty());
        assertTrue(CustomRules.rewards(List.of(item, loot, loot), limits).isPresent(), "3 slots > 2");
    }

    // --- RT-09: event_scope ----------------------------------------------------------------------------------

    @Test
    void eventScopeIsBoundedAndIdLike() {
        assertTrue(CustomRules.eventScopeOk("nerocolonies:colony/abc-1"));
        assertFalse(CustomRules.eventScopeOk(""));
        assertFalse(CustomRules.eventScopeOk("a".repeat(65)));
        assertFalse(CustomRules.eventScopeOk("has space"));
        assertFalse(CustomRules.eventScopeOk("\u00a7cred"));
        ObjectiveSpec bulky = new CustomEventObjective(Identifier.parse("nerolandcore:test"),
                Optional.of("x".repeat(2700)), CustomEventObjective.Direction.ANY, Optional.empty(), Optional.empty(),
                CustomEventObjective.Audience.EVERYONE, 1);
        assertTrue(CustomRules.objectives(List.of(bulky)).isPresent());
    }

    // --- RT-15: the lock beats level 3 on the admin paths ---------------------------------------------------

    @Test
    void lockRefusesLevelThreePlayersButNotTheConsole() {
        assertTrue(EditGate.lockRefuses(true, true, 3));
        assertFalse(EditGate.lockRefuses(true, true, 4));
        assertFalse(EditGate.lockRefuses(true, false, 4), "the console");
        assertFalse(EditGate.lockRefuses(false, true, 3), "unlocked");
    }
}
