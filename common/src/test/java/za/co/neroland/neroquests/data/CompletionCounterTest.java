package za.co.neroland.neroquests.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

/** C3: the bounded world completion counters behind the anonymous completion crossings. */
class CompletionCounterTest {

    private static final Identifier QUEST = Identifier.fromNamespaceAndPath("mypack", "x/q");
    private static final Identifier CHAPTER = Identifier.fromNamespaceAndPath("mypack", "x");

    @Test
    void eachCompletionCountsOnce() {
        QuestProgressState state = new QuestProgressState();
        assertEquals(0L, state.questCompletions(QUEST));
        assertEquals(1L, state.recordQuestCompletion(QUEST));
        assertEquals(2L, state.recordQuestCompletion(QUEST));
        assertEquals(2L, state.questCompletions(QUEST));
        assertEquals(1L, state.recordChapterCompletion(CHAPTER));
        assertEquals(0L, state.chapterCompletions(QUEST), "quest and chapter counters are separate");
    }

    @Test
    void countersPersistAndHoldNoPlayerData() {
        QuestProgressState state = new QuestProgressState();
        UUID player = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
        state.markComplete(player, QUEST, 1L);
        state.recordQuestCompletion(QUEST);
        state.recordQuestCompletion(QUEST);
        state.recordChapterCompletion(CHAPTER);
        JsonElement json = QuestProgressState.persistenceCodec().encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        String counters = json.getAsJsonObject().get("quest_completions").toString()
                + json.getAsJsonObject().get("chapter_completions").toString();
        assertFalse(counters.contains(player.toString()), "world counters must never carry a player id");
        QuestProgressState decoded = QuestProgressState.persistenceCodec().parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(2L, decoded.questCompletions(QUEST));
        assertEquals(1L, decoded.chapterCompletions(CHAPTER));
        decoded.forgetPlayer(player);
        assertEquals(2L, decoded.questCompletions(QUEST), "erasure leaves anonymous world counts alone");
    }

    @Test
    void countersAreBounded() {
        QuestProgressState state = new QuestProgressState();
        for (int i = 0; i < QuestProgressState.MAX_COMPLETION_COUNTERS; i++) {
            state.recordQuestCompletion(Identifier.fromNamespaceAndPath("mypack", "q" + i));
        }
        Identifier extra = Identifier.fromNamespaceAndPath("mypack", "overflow");
        assertEquals(1L, state.recordQuestCompletion(extra));
        assertEquals(1L, state.recordQuestCompletion(extra), "beyond the cap nothing new is stored");
        assertEquals(0L, state.questCompletions(extra));
    }
}
