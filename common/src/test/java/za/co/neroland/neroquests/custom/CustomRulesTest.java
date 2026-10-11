package za.co.neroland.neroquests.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestDefinitions.ValidationIssue;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.objective.CollectItemObjective;
import za.co.neroland.neroquests.quest.objective.EconomyObjective;
import za.co.neroland.neroquests.quest.objective.ItemTarget;
import za.co.neroland.neroquests.quest.objective.ManualObjective;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.GateReward;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.LootTableReward;
import za.co.neroland.neroquests.quest.reward.ModHookReward;
import za.co.neroland.neroquests.quest.reward.ReputationReward;
import za.co.neroland.neroquests.quest.reward.XpReward;

/** D4: custom caps and types, cycles, and the SPINE/PACK → CUSTOM dependency refusal through the real pipeline. */
class CustomRulesTest {

    private static final QuestDefinitions.Presence PRESENCE = new QuestDefinitions.Presence() {
        @Override
        public boolean modLoaded(String namespace) {
            return "neroquests".equals(namespace);
        }

        @Override
        public boolean isModId(String namespace) {
            return "neroquests".equals(namespace);
        }
    };

    private static final ObjectiveSpec STONE =
            new CollectItemObjective(new ItemTarget(Optional.of(id("minecraft:stone")), Optional.empty()), 1, false);

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static Identifier id(String full) {
        return Identifier.parse(full);
    }

    private static Quest custom(String full, List<Identifier> prerequisites, List<ObjectiveSpec> objectives,
                                List<RewardSpec> rewards) {
        return new Quest(id(full), "A title", "", Quest.DEFAULT_ICON, prerequisites, objectives, rewards,
                QuestScope.PLAYER, Optional.empty());
    }

    private static Quest custom(String full, List<RewardSpec> rewards) {
        return custom(full, List.of(), List.of(STONE), rewards);
    }

    @Test
    void customQuestsUseOnlyTheCustomRewardTypes() {
        CustomLimits limits = CustomLimits.DEFAULTS;
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(new ItemReward(id("minecraft:stone"), 1),
                new LootTableReward(id("minecraft:chests/simple_dungeon")), new XpReward(5),
                new CurrencyReward(CurrencyReward.DEFAULT_CURRENCY, 10, false))), limits).isEmpty());
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(new GateReward(id("neroquests:g")))),
                limits).isPresent());
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(new ReputationReward(id("x:f"), 1))),
                limits).isPresent());
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(new ModHookReward(id("custom:hook/x")))),
                limits).isPresent());
    }

    @Test
    void capsAreEnforced() {
        CustomLimits limits = CustomLimits.DEFAULTS;
        List<RewardSpec> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(new XpReward(1));
        }
        assertTrue(CustomRules.violation(custom("custom:c/a", ten), limits).isPresent(), "more than 9 rewards");
        List<RewardSpec> stacks = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            stacks.add(new ItemReward(id("minecraft:stone"), 1));
        }
        assertTrue(CustomRules.violation(custom("custom:c/a", stacks), limits).isEmpty());
        assertTrue(CustomRules.violation(custom("custom:c/a", stacks),
                new CustomLimits(500, 20, 8, 500, 1000L, false)).isPresent(), "customRewardMaxStacks");
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(new ItemReward(id("minecraft:stone"), 65))),
                limits).isPresent(), "one stack each");
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(new XpReward(300), new XpReward(201))),
                limits).isPresent(), "customRewardMaxXp");
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(
                new CurrencyReward(CurrencyReward.DEFAULT_CURRENCY, 1001, false))), limits).isPresent());
        List<ObjectiveSpec> thirteen = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            thirteen.add(STONE);
        }
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(), thirteen, List.of()), limits).isPresent());
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(), List.of(), List.of()), limits).isPresent());
        ObjectiveSpec huge = new CollectItemObjective(new ItemTarget(Optional.of(id("minecraft:stone")),
                Optional.empty()), 1_000_001, false);
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(), List.of(huge), List.of()), limits)
                .isPresent(), "count 1..1,000,000");
    }

    @Test
    void onlyTheCustomObjectiveTypes() {
        ObjectiveSpec economy = new EconomyObjective(EconomyObjective.Event.EARNED, Optional.empty(),
                Optional.empty(), 5);
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(), List.of(economy), List.of()),
                CustomLimits.DEFAULTS).isPresent());
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(), List.of(new ManualObjective("Build it")),
                List.of()), CustomLimits.DEFAULTS).isEmpty());
        assertTrue(CustomRules.violation(custom("custom:c/a", List.of(), List.of(new ManualObjective("§cx")),
                List.of()), CustomLimits.DEFAULTS).isPresent(), "unsanitised label");
    }

    @Test
    void textMustBeSanitisedAndShort() {
        Quest dirty = new Quest(id("custom:c/a"), "§kHi", "", Quest.DEFAULT_ICON, List.of(), List.of(STONE),
                List.of(), QuestScope.PLAYER, Optional.empty());
        assertTrue(CustomRules.violation(dirty, CustomLimits.DEFAULTS).isPresent());
        Quest longTitle = new Quest(id("custom:c/a"), "x".repeat(65), "", Quest.DEFAULT_ICON, List.of(),
                List.of(STONE), List.of(), QuestScope.PLAYER, Optional.empty());
        assertTrue(CustomRules.violation(longTitle, CustomLimits.DEFAULTS).isPresent());
        Quest server = new Quest(id("custom:c/a"), "Fine", "", Quest.DEFAULT_ICON, List.of(), List.of(STONE),
                List.of(), QuestScope.SERVER, Optional.empty());
        assertTrue(CustomRules.violation(server, CustomLimits.DEFAULTS).isPresent(), "player scope only");
    }

    private static QuestDefinitions.Validated validate(List<Quest> packs, List<Quest> customs,
                                                       List<Chapter> customChapters) {
        Map<Identifier, Quest> q = new LinkedHashMap<>();
        packs.forEach(quest -> q.put(quest.id(), quest));
        Map<Identifier, Quest> c = new LinkedHashMap<>();
        customs.forEach(quest -> c.put(quest.id(), quest));
        Map<Identifier, Chapter> ch = new LinkedHashMap<>();
        customChapters.forEach(chapter -> ch.put(chapter.id(), chapter));
        return QuestDefinitions.validate(q, Map.of(), PRESENCE, new QuestDefinitions.Extra(c, ch));
    }

    private static boolean dropped(QuestDefinitions.Validated result, String full) {
        return result.issues().stream().anyMatch(issue -> issue.id().equals(id(full))
                && issue.severity() == ValidationIssue.Severity.DROPPED);
    }

    private static Quest spine(String full, List<Identifier> prerequisites, List<ObjectiveSpec> objectives) {
        return new Quest(id(full), "quest.neroquests.x.title", "", Quest.DEFAULT_ICON, prerequisites, objectives,
                List.of(), QuestScope.PLAYER, Optional.empty());
    }

    @Test
    void customMayDependOnSpineButNeverTheReverse() {
        Quest spineQuest = spine("neroquests:intro/a", List.of(), List.of(STONE));
        Quest customQuest = custom("custom:c/a", List.of(id("neroquests:intro/a")), List.of(STONE), List.of());
        QuestDefinitions.Validated ok = validate(List.of(spineQuest), List.of(customQuest), List.of());
        assertTrue(ok.quests().containsKey(id("custom:c/a")));

        Quest spineOnCustom = spine("neroquests:intro/b", List.of(id("custom:c/a")), List.of(STONE));
        QuestDefinitions.Validated bad = validate(List.of(spineQuest, spineOnCustom), List.of(customQuest), List.of());
        assertTrue(dropped(bad, "neroquests:intro/b"), "spine prerequisite on a custom quest");

        Quest spineChain = spine("neroquests:intro/c", List.of(), List.of(new QuestCompleteObjective(id("custom:c/a"))));
        assertTrue(dropped(validate(List.of(spineChain), List.of(customQuest), List.of()), "neroquests:intro/c"),
                "spine quest_complete on a custom quest");

        Quest packOnCustom = new Quest(id("myserver:p"), "quest.myserver.p.title", "", Quest.DEFAULT_ICON,
                List.of(id("custom:c/a")), List.of(STONE), List.of(), QuestScope.PLAYER, Optional.empty());
        assertTrue(dropped(validate(List.of(packOnCustom), List.of(customQuest), List.of()), "myserver:p"),
                "pack prerequisite on a custom quest");
    }

    @Test
    void customCyclesAreRejected() {
        Quest a = custom("custom:c/a", List.of(id("custom:c/b")), List.of(STONE), List.of());
        Quest b = custom("custom:c/b", List.of(id("custom:c/a")), List.of(STONE), List.of());
        QuestDefinitions.Validated result = validate(List.of(), List.of(a, b), List.of());
        assertFalse(result.quests().containsKey(a.id()));
        assertFalse(result.quests().containsKey(b.id()));

        Map<Identifier, Set<Identifier>> graph = CustomGraph.dependencies(List.of(
                custom("custom:c/x", List.of(), List.of(STONE), List.of()),
                custom("custom:c/y", List.of(id("custom:c/x")), List.of(STONE), List.of()),
                custom("custom:c/z", List.of(), List.of(new QuestCompleteObjective(id("custom:c/y"))), List.of())));
        assertTrue(CustomGraph.createsCycle(graph, id("custom:c/x"), List.of(id("custom:c/z"))),
                "x -> z -> (quest_complete) y -> x");
        assertFalse(CustomGraph.createsCycle(graph, id("custom:c/z"), List.of(id("custom:c/x"))));
        assertTrue(CustomGraph.createsCycle(graph, id("custom:c/x"), List.of(id("custom:c/x"))), "self");
    }

    @Test
    void chaptersListOnlyTheirOwnSource() {
        Quest customQuest = custom("custom:c/a", List.of());
        Chapter customChapter = new Chapter(id("custom:c"), "Mine", Chapter.DEFAULT_ICON,
                List.of(new Chapter.Entry(id("custom:c/a"), 0, 0), new Chapter.Entry(id("neroquests:intro/a"), 1, 0)));
        Quest spineQuest = spine("neroquests:intro/a", List.of(), List.of(STONE));
        QuestDefinitions.Validated result = validate(List.of(spineQuest), List.of(customQuest), List.of(customChapter));
        Chapter kept = result.chapters().get(id("custom:c"));
        assertEquals(1, kept.quests().size(), "a custom chapter lists only custom quests");
        assertEquals(id("custom:c/a"), kept.quests().get(0).quest());
    }

    @Test
    void customNamespaceStillClosedToDatapacks() {
        Quest fromDatapack = custom("custom:c/a", List.of());
        Map<Identifier, Quest> q = new LinkedHashMap<>();
        q.put(fromDatapack.id(), fromDatapack);
        QuestDefinitions.Validated result = QuestDefinitions.validate(q, Map.of(), PRESENCE);
        assertTrue(dropped(result, "custom:c/a"));
    }
}
