package za.co.neroland.neroquests.quest.objective;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;
import za.co.neroland.neroquests.quest.engine.QuestEngine;

/** C5/C6: a manual objective advances only through the command's credit; visit_structure decodes strictly. */
class ManualObjectiveTest {

    private static final Identifier QUEST = Identifier.fromNamespaceAndPath("mypack", "build/tower");
    private static final Identifier OTHER = Identifier.fromNamespaceAndPath("mypack", "build/wall");

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static ObjectiveSpec decode(String json) {
        return ObjectiveSpec.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static ObjectiveContext context(Identifier quest) {
        return new ObjectiveContext(null, null, QuestScope.PLAYER, Set.of(), quest);
    }

    @Test
    void manualNeverAdvancesOnItsOwn() {
        ObjectiveSpec manual = decode("{\"type\":\"neroquests:manual\",\"label\":\"quest.mypack.build.tower.manual\"}");
        assertTrue(manual instanceof ManualObjective);
        ObjectiveContext context = context(QUEST);
        assertTrue(manual.measure(context).isEmpty(), "never measured");
        assertEquals(0, manual.creditCraft(null, 1, context));
        assertEquals(0, manual.creditKill(null, context));
        assertEquals(0, manual.creditThreshold(null, context));
        assertEquals(0, manual.creditEconomy(null, context));
        assertEquals(1, manual.target());
    }

    @Test
    void onlyTheCommandCreditTicksItAndOnlyForItsQuest() {
        ObjectiveSpec manual = decode("{\"type\":\"neroquests:manual\",\"label\":\"l.l\"}");
        ObjectiveSpec other = decode("{\"type\":\"neroquests:quest_complete\",\"quest\":\"mypack:build/wall\"}");
        QuestEngine.Credit credit = ManualObjective.creditFor(QUEST);
        assertEquals(1, credit.of(manual, context(QUEST)));
        assertEquals(0, credit.of(manual, context(OTHER)), "another quest's manual objective is untouched");
        assertEquals(0, credit.of(other, context(QUEST)), "non-manual objectives are untouched");
    }

    @Test
    void manualNeedsALabel() {
        assertTrue(ObjectiveSpec.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"type\":\"neroquests:manual\"}")).error().isPresent());
    }

    @Test
    void visitStructureTakesExactlyOneTarget() {
        assertTrue(decode("{\"type\":\"neroquests:visit_structure\",\"structure\":\"minecraft:village_plains\"}")
                instanceof VisitStructureObjective);
        assertTrue(decode("{\"type\":\"neroquests:visit_structure\",\"tag\":\"minecraft:village\"}")
                instanceof VisitStructureObjective);
        assertTrue(ObjectiveSpec.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"type\":\"neroquests:visit_structure\"}")).error().isPresent());
        assertFalse(ObjectiveSpec.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"type\":\"neroquests:visit_structure\",\"structure\":\"a:b\",\"tag\":\"a:c\"}")).result().isPresent());
    }
}
