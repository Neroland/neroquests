package za.co.neroland.neroquests.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestTypes;

/** The quest milestone record gates: chapter, all-chapters and completed-count rungs, all derived. */
class QuestMilestonesTest {

    private static final Identifier A = id("neroquests", "g/a");
    private static final Identifier B = id("neroquests", "g/b");
    private static final Identifier S = id("nerospace", "s/a");
    private static final Identifier ABSENT = id("neroabsent", "q/a");

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static Identifier id(String ns, String path) {
        return Identifier.fromNamespaceAndPath(ns, path);
    }

    private static Quest quest(Identifier qid) {
        Quest.Data data = Quest.DATA_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"title\":\"t\",\"objectives\":[{\"type\":\"neroquests:manual\",\"label\":\"l.l\"}]}")).getOrThrow();
        return new Quest(qid, data.title(), data.description(), data.icon(), List.of(), data.objectives(),
                data.rewards(), QuestScope.PLAYER, Optional.empty());
    }

    private static Chapter chapter(Identifier cid, Identifier... quests) {
        List<Chapter.Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < quests.length; i++) {
            entries.add(new Chapter.Entry(quests[i], i, 0));
        }
        return new Chapter(cid, "c.c", Chapter.DEFAULT_ICON, entries);
    }

    private static final Identifier GROUNDWORK = id("neroquests", "groundwork");
    private static final Identifier SPACE = id("nerospace", "space");
    private static final Identifier GONE = id("neroabsent", "pack");

    private static Map<Identifier, Chapter> chapters() {
        Map<Identifier, Chapter> c = new LinkedHashMap<>();
        c.put(GROUNDWORK, chapter(GROUNDWORK, A, B));
        c.put(SPACE, chapter(SPACE, S));
        c.put(GONE, chapter(GONE, ABSENT));
        return c;
    }

    private static Map<Identifier, Quest> quests() {
        Map<Identifier, Quest> q = new LinkedHashMap<>();
        q.put(A, quest(A));
        q.put(B, quest(B));
        q.put(S, quest(S));
        return q;
    }

    @Test
    void gateIdsAreStable() {
        assertEquals("neroquests:chapter/neroquests/groundwork", QuestMilestones.chapterGate(GROUNDWORK).toString());
        assertEquals("neroquests:completed/25", QuestMilestones.completedGate(25).toString());
        assertEquals("neroquests:all_chapters", QuestMilestones.ALL_CHAPTERS.toString());
        assertEquals("neroquests:first_contract", QuestMilestones.FIRST_CONTRACT.toString());
    }

    @Test
    void chapterGateOpensOnlyWhenEveryLoadedQuestIsDone() {
        List<Identifier> some = QuestMilestones.reached(chapters(), quests(), Set.of(A), Set.of());
        assertTrue(some.isEmpty());
        List<Identifier> one = QuestMilestones.reached(chapters(), quests(), Set.of(A, B), Set.of());
        assertEquals(List.of(QuestMilestones.chapterGate(GROUNDWORK)), one);
    }

    @Test
    void allChaptersIgnoresChaptersWhoseModIsAbsent() {
        List<Identifier> all = QuestMilestones.reached(chapters(), quests(), Set.of(A, B, S), Set.of());
        assertTrue(all.contains(QuestMilestones.chapterGate(SPACE)));
        assertTrue(all.contains(QuestMilestones.ALL_CHAPTERS));
        assertFalse(all.contains(QuestMilestones.chapterGate(GONE)));
        assertTrue(QuestMilestones.reached(Map.of(), quests(), Set.of(A), Set.of()).isEmpty(),
                "no chapters loaded never means every chapter is done");
    }

    @Test
    void completedRungsComeFromTheStoredCompletedSet() {
        Set<Identifier> done = new HashSet<>();
        for (int i = 0; i < 24; i++) {
            done.add(id("x", "q" + i));
        }
        assertFalse(QuestMilestones.reached(Map.of(), Map.of(), done, Set.of()).contains(QuestMilestones.completedGate(25)));
        done.add(id("x", "q24"));
        assertTrue(QuestMilestones.reached(Map.of(), Map.of(), done, Set.of()).contains(QuestMilestones.completedGate(25)));
        for (int i = 25; i < 100; i++) {
            done.add(id("x", "q" + i));
        }
        assertTrue(QuestMilestones.reached(Map.of(), Map.of(), done, Set.of()).contains(QuestMilestones.completedGate(100)));
    }
}
