package za.co.neroland.neroquests.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;
import za.co.neroland.neroquests.custom.CustomQuestState;

/** Core export (Core 1.20.1): one document, nothing for a player with no rows, nobody else's rows. */
class QuestCoreExportTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    private static final Identifier QUEST = Identifier.fromNamespaceAndPath("neroquests", "test/export");

    private static JsonObject doc(QuestProgressState state, UUID player) {
        JsonObject json = state.export(player);
        json.add("custom_quests", new CustomQuestState().export(player));
        return json;
    }

    @Test
    void playerWithNoRowsExportsNothing() {
        assertTrue(QuestData.isEmpty(doc(new QuestProgressState(), ALICE)));
    }

    @Test
    void progressMakesTheDocumentNonEmptyAndHoldsNoOtherPlayer() {
        QuestProgressState state = new QuestProgressState();
        state.incrementObjective(ALICE, QUEST, 0, 2);
        JsonObject alice = doc(state, ALICE);
        assertFalse(QuestData.isEmpty(alice));
        assertEquals(1, alice.getAsJsonObject("quests").size());
        assertTrue(QuestData.isEmpty(doc(state, BOB)), "Bob's document never shows Alice's rows");
        String text = alice.toString();
        assertFalse(text.contains(ALICE.toString()), "no UUID in the document");
        assertFalse(text.contains(BOB.toString()));
    }
}
