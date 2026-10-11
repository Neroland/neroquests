package za.co.neroland.neroquests.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

/**
 * J1 (§13 immutability): editor content joins the definitions only as {@code custom:} ids and never replaces a
 * datapack definition, so no editor/import path can add, shadow or alter a spine or pack quest or chapter.
 */
class CustomMergeTest {

    private static final Identifier SPINE = Identifier.parse("neroquests:groundwork/what_this_book_is");
    private static final Identifier PACK = Identifier.parse("nerotech:industrial_record/first_machine");
    private static final Identifier NEW_SPINE = Identifier.parse("neroquests:groundwork/smuggled");
    private static final Identifier CUSTOM = Identifier.parse("custom:abcd2345/efgh6723");

    @Test
    void editorEntriesNeverShadowOrAddNonCustomIds() {
        Map<Identifier, String> base = new LinkedHashMap<>();
        base.put(SPINE, "spine");
        base.put(PACK, "pack");
        Map<Identifier, String> extra = new LinkedHashMap<>();
        extra.put(SPINE, "editor-overwrite");
        extra.put(PACK, "editor-overwrite");
        extra.put(NEW_SPINE, "editor-new");
        extra.put(CUSTOM, "custom");
        Map<Identifier, String> merged = QuestDefinitions.merged(base, extra);
        assertEquals("spine", merged.get(SPINE));
        assertEquals("pack", merged.get(PACK));
        assertFalse(merged.containsKey(NEW_SPINE));
        assertEquals("custom", merged.get(CUSTOM));
        assertEquals(3, merged.size());
    }

    @Test
    void noEditorContentLeavesTheDatapackMapUntouched() {
        Map<Identifier, String> base = Map.of(SPINE, "spine");
        assertSame(base, QuestDefinitions.merged(base, Map.of()));
    }
}
