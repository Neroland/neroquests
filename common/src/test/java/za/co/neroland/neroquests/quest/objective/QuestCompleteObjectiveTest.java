package za.co.neroland.neroquests.quest.objective;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;

/** R3 follow-up to AUD-18: a quest_complete target resolves by its own scope, like a prerequisite. */
class QuestCompleteObjectiveTest {

    private static final Identifier PLAYER_TARGET = id("player_target");
    private static final Identifier WORLD_TARGET = id("world_target");

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("neroquests", "test/" + path);
    }

    private static Quest quest(Identifier id, QuestScope scope) {
        String json = "{\"title\":\"t\",\"objectives\":[{\"type\":\"neroquests:quest_complete\","
                + "\"quest\":\"neroquests:a\"}]}";
        Quest.Data data = Quest.DATA_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        return new Quest(id, data.title(), data.description(), data.icon(), List.of(), data.objectives(),
                data.rewards(), scope, Optional.empty());
    }

    private static Map<Identifier, Quest> definitions() {
        Map<Identifier, Quest> out = new LinkedHashMap<>();
        out.put(PLAYER_TARGET, quest(PLAYER_TARGET, QuestScope.PLAYER));
        out.put(WORLD_TARGET, quest(WORLD_TARGET, QuestScope.SERVER));
        return out;
    }

    /** The context a quest of {@code askingScope} would get: completedQuests is that scope's set. */
    private static ObjectiveContext context(QuestScope askingScope, Set<Identifier> player, Set<Identifier> world) {
        Set<Identifier> resolved = askingScope == QuestScope.SERVER ? world : player;
        return new ObjectiveContext(null, null, askingScope, resolved, id("asking"), player, world, definitions());
    }

    private static int measure(Identifier target, ObjectiveContext context) {
        return new QuestCompleteObjective(target).measure(context).orElseThrow();
    }

    @Test
    void playerQuestCanWaitOnAWorldQuest() {
        // A player quest asking for a server-scope quest: met by the world's completion.
        assertEquals(1, measure(WORLD_TARGET, context(QuestScope.PLAYER, Set.of(), Set.of(WORLD_TARGET))));
        assertEquals(0, measure(WORLD_TARGET, context(QuestScope.PLAYER, Set.of(WORLD_TARGET), Set.of())));
    }

    @Test
    void serverQuestCanWaitOnThePlayersQuest() {
        // A server quest asking for a player-scope quest: met by the evaluating player's completion.
        assertEquals(1, measure(PLAYER_TARGET, context(QuestScope.SERVER, Set.of(PLAYER_TARGET), Set.of())));
        assertEquals(0, measure(PLAYER_TARGET, context(QuestScope.SERVER, Set.of(), Set.of(PLAYER_TARGET))));
    }

    @Test
    void sameScopeBehavesAsBefore() {
        assertEquals(1, measure(PLAYER_TARGET, context(QuestScope.PLAYER, Set.of(PLAYER_TARGET), Set.of())));
        assertEquals(1, measure(WORLD_TARGET, context(QuestScope.SERVER, Set.of(), Set.of(WORLD_TARGET))));
        assertEquals(0, measure(PLAYER_TARGET, context(QuestScope.PLAYER, Set.of(), Set.of())));
    }

    @Test
    void legacyConstructorUsesTheResolvedSet() {
        ObjectiveContext legacy = new ObjectiveContext(null, null, QuestScope.PLAYER, Set.of(WORLD_TARGET), id("asking"));
        assertEquals(1, measure(WORLD_TARGET, legacy));
    }
}
