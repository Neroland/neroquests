package za.co.neroland.neroquests.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** J2 (RT-07): a datapack can neither replace nor add NeroQuests-namespace definitions. */
class DefinitionOriginTest {

    @Test
    void datapacksAreRecognisedBySourceOrFolderId() {
        assertTrue(QuestDefinitions.datapackOrigin(true, "anything"));
        assertTrue(QuestDefinitions.datapackOrigin(false, "file/evil_pack"));
        assertFalse(QuestDefinitions.datapackOrigin(false, "neroquests"));
        assertFalse(QuestDefinitions.datapackOrigin(false, "mod_resources"));
        assertFalse(QuestDefinitions.datapackOrigin(false, null));
    }

    @Test
    void theModsOwnFileWinsInItsNamespace() {
        // stack order: lowest priority first; true = from a datapack
        assertEquals(0, QuestDefinitions.pickResource(List.of(false, true), true), "datapack override ignored");
        assertEquals(-1, QuestDefinitions.pickResource(List.of(true), true), "datapack-only spine file dropped");
        assertEquals(1, QuestDefinitions.pickResource(List.of(false, false), true));
        assertEquals(1, QuestDefinitions.pickResource(List.of(false, true), false), "other namespaces: as before");
        assertEquals(0, QuestDefinitions.pickResource(List.of(true), false), "a datapack's own pack namespace loads");
        assertEquals(-1, QuestDefinitions.pickResource(List.of(), false));
    }
}
