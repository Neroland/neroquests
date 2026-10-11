package za.co.neroland.neroquests.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.objective.CollectItemObjective;
import za.co.neroland.neroquests.quest.objective.ItemTarget;

/**
 * J1 (§13 immutability): the custom store — the only thing the editor, {@code custom import} and the custom
 * commands write — can never hold a spine or pack id, whether written through the single writer or smuggled
 * into the save file.
 */
class ImmutabilityTest {

    private static final ObjectiveSpec STONE = new CollectItemObjective(
            new ItemTarget(Optional.of(Identifier.parse("minecraft:stone")), Optional.empty()), 4, false);

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static CustomQuest row(String id) {
        return CustomQuest.create(Identifier.parse(id), "chap2345", "T", Quest.DEFAULT_ICON, 0, 0, List.of(STONE),
                Optional.empty(), 1L);
    }

    @Test
    void theSingleWriterRefusesSpineAndPackIds() {
        CustomQuestState state = new CustomQuestState();
        state.putChapter(new CustomChapter("chap2345", "C", Identifier.parse("minecraft:stone"), 0, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> state.putQuest(row("neroquests:groundwork/what_this_book_is")));
        assertThrows(IllegalArgumentException.class,
                () -> state.putQuest(row("nerotech:industrial_record/first_machine")));
        state.putQuest(row("custom:chap2345/aaaa2222"));
        assertEquals(1, state.quests().size());
        assertTrue(state.quests().keySet().stream().allMatch(id -> "custom".equals(id.getNamespace())));
    }

    @Test
    void aForeignRowInTheSaveFileIsNeverLoaded() {
        CustomQuestState state = new CustomQuestState();
        state.putChapter(new CustomChapter("chap2345", "C", Identifier.parse("minecraft:stone"), 0, 1L));
        state.putQuest(row("custom:chap2345/aaaa2222"));
        JsonElement encoded = CustomQuestState.persistenceCodec().encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        JsonElement tampered = JsonParser.parseString(encoded.toString()
                .replace("custom:chap2345/aaaa2222", "neroquests:groundwork/what_this_book_is"));
        CustomQuestState decoded = CustomQuestState.persistenceCodec().parse(JsonOps.INSTANCE, tampered).getOrThrow();
        assertTrue(decoded.quests().isEmpty());
        assertTrue(CustomContent.build(decoded).quests().isEmpty());
    }
}
