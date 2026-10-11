package za.co.neroland.neroquests.quest.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import za.co.neroland.nerolandcore.event.ThresholdEvents.ThresholdCrossing;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestTypes;

/** C3: the anonymous completion crossings — channel, scope and value — and the chapter rule. */
class CompletionSignalsTest {

    private static final Identifier A = Identifier.fromNamespaceAndPath("mypack", "x/a");
    private static final Identifier B = Identifier.fromNamespaceAndPath("mypack", "x/b");
    private static final Identifier W = Identifier.fromNamespaceAndPath("mypack", "x/w");

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static Quest quest(Identifier id, QuestScope scope) {
        Quest.Data data = Quest.DATA_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"title\":\"t\",\"objectives\":[{\"type\":\"neroquests:manual\",\"label\":\"l.l\"}]}")).getOrThrow();
        return new Quest(id, data.title(), data.description(), data.icon(), List.of(), data.objectives(),
                data.rewards(), scope, Optional.empty());
    }

    @Test
    void questCrossingNamesTheQuestNeverThePlayer() {
        ThresholdCrossing crossing = CompletionSignals.questCrossing(A, 7L);
        assertEquals(CompletionSignals.QUEST_COMPLETED, crossing.channel());
        assertEquals("neroquests:quest_completed", crossing.channel().toString());
        assertEquals("mypack:x/a", crossing.scope());
        assertEquals(7L, crossing.value());
        assertEquals(7L, crossing.threshold());
        assertTrue(crossing.rising());
        ThresholdCrossing chapter = CompletionSignals.chapterCrossing(Identifier.fromNamespaceAndPath("mypack", "x"), 2L);
        assertEquals("neroquests:chapter_completed", chapter.channel().toString());
        assertEquals("mypack:x", chapter.scope());
        assertEquals(2L, chapter.value());
    }

    @Test
    void chapterCompletesOnlyWhenEveryLoadedQuestIsDone() {
        Map<Identifier, Quest> defs = new LinkedHashMap<>();
        defs.put(A, quest(A, QuestScope.PLAYER));
        defs.put(B, quest(B, QuestScope.PLAYER));
        defs.put(W, quest(W, QuestScope.SERVER));
        Chapter chapter = new Chapter(Identifier.fromNamespaceAndPath("mypack", "x"), "c.c",
                Chapter.DEFAULT_ICON, List.of(new Chapter.Entry(A, 0, 0), new Chapter.Entry(B, 1, 0),
                        new Chapter.Entry(W, 2, 0), new Chapter.Entry(Identifier.fromNamespaceAndPath("mypack", "gone"), 3, 0)));
        assertFalse(CompletionSignals.chapterComplete(chapter, defs, Set.of(A), Set.of(W)));
        assertFalse(CompletionSignals.chapterComplete(chapter, defs, Set.of(A, B), Set.of()),
                "the server quest resolves by the world's completions");
        assertFalse(CompletionSignals.chapterComplete(chapter, defs, Set.of(A, B, W), Set.of()));
        assertTrue(CompletionSignals.chapterComplete(chapter, defs, Set.of(A, B), Set.of(W)),
                "an entry whose quest is not loaded does not block");
        assertFalse(CompletionSignals.chapterComplete(new Chapter(Identifier.fromNamespaceAndPath("mypack", "empty"),
                "c.c", Chapter.DEFAULT_ICON, List.of()), defs, Set.of(A), Set.of()));
    }
}
