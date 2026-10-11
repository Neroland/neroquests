package za.co.neroland.neroquests.quest;

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

import za.co.neroland.neroquests.quest.QuestDefinitions.ValidationIssue;
import za.co.neroland.neroquests.quest.reward.ModHookReward;

/** B1–B3 / B6 and the C3 mod_hook rule: validation per source, pack presence, the reserved namespace. */
class PackValidationTest {

    /** "mypack" and "nerospace" are mods; only "mypack" is loaded. "myserver" is a plain datapack. */
    private static final QuestDefinitions.Presence PRESENCE = new QuestDefinitions.Presence() {
        @Override
        public boolean modLoaded(String namespace) {
            return "mypack".equals(namespace) || "neroquests".equals(namespace);
        }

        @Override
        public boolean isModId(String namespace) {
            return Set.of("mypack", "nerospace", "neroquests").contains(namespace);
        }
    };

    private static final String OBJECTIVE = "{\"type\":\"neroquests:collect_item\",\"item\":\"minecraft:stone\"}";

    @BeforeAll
    static void registerTypes() {
        QuestTypes.init();
    }

    private static Identifier id(String full) {
        return Identifier.parse(full);
    }

    /** A quest with key-shaped text, one objective and the given extra JSON members (may be empty). */
    private static Quest quest(String full, String extra) {
        Identifier id = id(full);
        String key = PackRules.recommendedKey("quest", id, "title");
        String json = "{\"title\":\"" + key + "\",\"objectives\":[" + OBJECTIVE + "]"
                + (extra.isEmpty() ? "" : "," + extra) + "}";
        return new Quest(id, Quest.DATA_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow());
    }

    private static Chapter chapter(String full, String flags, String... quests) {
        Identifier id = id(full);
        StringBuilder entries = new StringBuilder();
        for (String quest : quests) {
            if (entries.length() > 0) {
                entries.append(',');
            }
            entries.append("{\"quest\":\"").append(quest).append("\"}");
        }
        String json = "{\"title\":\"" + PackRules.recommendedKey("chapter", id, "title") + "\",\"quests\":["
                + entries + "]" + (flags.isEmpty() ? "" : "," + flags) + "}";
        return new Chapter(id, Chapter.DATA_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow());
    }

    private static QuestDefinitions.Validated validate(List<Quest> quests, List<Chapter> chapters) {
        Map<Identifier, Quest> q = new LinkedHashMap<>();
        quests.forEach(quest -> q.put(quest.id(), quest));
        Map<Identifier, Chapter> c = new LinkedHashMap<>();
        chapters.forEach(chapter -> c.put(chapter.id(), chapter));
        return QuestDefinitions.validate(q, c, PRESENCE);
    }

    private static Optional<ValidationIssue> dropped(QuestDefinitions.Validated result, String full) {
        return result.issues().stream()
                .filter(issue -> issue.id().equals(id(full)) && issue.severity() == ValidationIssue.Severity.DROPPED)
                .findFirst();
    }

    @Test
    void sourcesComeFromTheNamespace() {
        assertEquals(QuestSource.SPINE, QuestSource.of(id("neroquests:a")));
        assertEquals(QuestSource.PACK, QuestSource.of(id("nerospace:a")));
        assertEquals(QuestSource.PACK, QuestSource.of(id("myserver:a")));
        assertEquals(QuestSource.CUSTOM, QuestSource.of(id("custom:a")));
        assertEquals(Optional.of(QuestSources.LOCKED_SPINE), QuestSources.lockKey(id("neroquests:a")));
        assertEquals(Optional.of(QuestSources.LOCKED_PACK), QuestSources.lockKey(id("mypack:a")));
        assertEquals(Optional.empty(), QuestSources.lockKey(id("custom:a")));
        assertFalse(QuestSources.isMutable(id("neroquests:a")));
        assertTrue(QuestSources.isMutable(id("custom:a")));
    }

    @Test
    void summaryListsEverySource() {
        List<String> summary = QuestSources.summary(List.of(quest("neroquests:a", ""), quest("mypack:x/b", ""),
                quest("mypack:x/c", ""), quest("myserver:d", "")));
        assertEquals(List.of("spine 1", "pack mypack 2", "pack myserver 1", "custom 0"), summary);
    }

    @Test
    void spineIsExemptFromPackRules() {
        Quest spine = quest("neroquests:s", "\"scope\":\"server\",\"rewards\":[{\"type\":\"neroquests:gate\","
                + "\"gate\":\"nerolandcore:deep_space\"}],\"prerequisites\":[\"nerospace:elsewhere\"]");
        QuestDefinitions.Validated result = validate(List.of(spine), List.of());
        assertTrue(result.quests().containsKey(spine.id()));
    }

    @Test
    void packGateRewardIsDroppedWithTheRule() {
        Quest gate = quest("mypack:x/gate", "\"rewards\":[{\"type\":\"neroquests:gate\",\"gate\":\"nerolandcore:deep_space\"}]");
        Quest reputation = quest("mypack:x/rep", "\"rewards\":[{\"type\":\"neroquests:reputation\","
                + "\"faction\":\"nerofactions:miners_union\",\"amount\":5}]");
        QuestDefinitions.Validated result = validate(List.of(gate, reputation), List.of());
        assertTrue(result.quests().isEmpty());
        assertTrue(dropped(result, "mypack:x/gate").orElseThrow().detail()
                .contains("neroquests:gate is only allowed in quests of the 'neroquests' namespace"));
        assertTrue(dropped(result, "mypack:x/rep").orElseThrow().detail().contains("neroquests:reputation"));
    }

    @Test
    void serverScopeNeedsTheChapterOptIn() {
        Quest world = quest("mypack:x/world", "\"scope\":\"server\"");
        QuestDefinitions.Validated without = validate(List.of(world), List.of(chapter("mypack:x", "", "mypack:x/world")));
        assertTrue(dropped(without, "mypack:x/world").orElseThrow().detail().contains("allow_server_scope"));
        QuestDefinitions.Validated with = validate(List.of(world),
                List.of(chapter("mypack:x", "\"allow_server_scope\":true", "mypack:x/world")));
        assertTrue(with.quests().containsKey(world.id()));
        // A chapter of another namespace cannot opt someone else's quest in.
        QuestDefinitions.Validated foreign = validate(List.of(world),
                List.of(chapter("myserver:x", "\"allow_server_scope\":true", "mypack:x/world")));
        assertTrue(dropped(foreign, "mypack:x/world").isPresent());
    }

    @Test
    void crossNamespaceDependenciesAreDropped() {
        Quest spineDep = quest("mypack:x/ok", "\"prerequisites\":[\"neroquests:groundwork/first_tools\"]");
        Quest ownDep = quest("mypack:x/own", "\"prerequisites\":[\"mypack:x/ok\"]");
        Quest crossPrereq = quest("mypack:x/cross", "\"prerequisites\":[\"nerospace:star/first\"]");
        Quest crossObjective = new Quest(id("mypack:x/cross_obj"), Quest.DATA_CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"title\":\"quest.mypack.x.cross_obj.title\",\"objectives\":[{\"type\":"
                        + "\"neroquests:quest_complete\",\"quest\":\"myserver:other\"}]}")).getOrThrow());
        QuestDefinitions.Validated result = validate(List.of(spineDep, ownDep, crossPrereq, crossObjective), List.of());
        assertTrue(result.quests().containsKey(ownDep.id()));
        assertTrue(dropped(result, "mypack:x/cross").orElseThrow().detail().contains("outside the pack's namespace"));
        assertTrue(dropped(result, "mypack:x/cross_obj").orElseThrow().detail().contains("quest_complete"));
    }

    @Test
    void literalTextIsDropped() {
        Quest literal = new Quest(id("mypack:x/literal"), Quest.DATA_CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"title\":\"Mine Some Stone\",\"objectives\":[" + OBJECTIVE + "]}"))
                .getOrThrow());
        QuestDefinitions.Validated result = validate(List.of(literal), List.of());
        assertTrue(dropped(result, "mypack:x/literal").orElseThrow().detail().contains("translation key"));
        assertTrue(PackRules.isKey("quest.mypack.x.literal.title"));
        assertFalse(PackRules.isKey("Mine Some Stone"));
        assertFalse(PackRules.isKey("nodot"));
    }

    @Test
    void customNamespaceFromADatapackIsDropped() {
        Quest custom = quest("custom:x/sneaky", "");
        Chapter chapter = chapter("custom:x", "", "custom:x/sneaky");
        QuestDefinitions.Validated result = validate(List.of(custom), List.of(chapter));
        assertTrue(result.quests().isEmpty());
        assertTrue(result.chapters().isEmpty());
        assertTrue(dropped(result, "custom:x/sneaky").orElseThrow().detail().contains("reserved"));
        assertTrue(dropped(result, "custom:x").isPresent());
    }

    @Test
    void absentModPackLoadsNothing() {
        Quest a = quest("nerospace:star/a", "");
        Quest b = quest("nerospace:star/b", "");
        QuestDefinitions.Validated result = validate(List.of(a, b),
                List.of(chapter("nerospace:star", "", "nerospace:star/a", "nerospace:star/b")));
        assertTrue(result.quests().isEmpty());
        assertTrue(result.chapters().isEmpty());
        assertEquals(Integer.valueOf(2), result.absentPacks().get("nerospace"));
        assertTrue(result.issues().isEmpty(), "an absent pack is not an error: " + result.issues());
    }

    @Test
    void showWhenAbsentKeepsTheChapterWithABadge() {
        Quest a = quest("nerospace:star/a", "");
        Quest hidden = quest("nerospace:other/b", "");
        QuestDefinitions.Validated result = validate(List.of(a, hidden),
                List.of(chapter("nerospace:star", "\"show_when_absent\":true", "nerospace:star/a")));
        Quest kept = result.quests().get(a.id());
        assertTrue(kept != null && kept.modAbsent());
        assertTrue(result.chapters().get(id("nerospace:star")).modAbsent());
        assertFalse(result.quests().containsKey(hidden.id()));
        assertEquals(Integer.valueOf(1), result.absentPacks().get("nerospace"));
        assertFalse(QuestStates.isAvailable(kept, result.quests(), Set.of(), Set.of(), Set.of()),
                "a badge quest is never workable");
    }

    @Test
    void serverDatapackNamespaceAlwaysLoads() {
        Quest quest = quest("myserver:local/q", "");
        QuestDefinitions.Validated result = validate(List.of(quest), List.of(chapter("myserver:local", "", "myserver:local/q")));
        assertTrue(result.quests().containsKey(quest.id()));
        assertFalse(result.quests().get(quest.id()).modAbsent());
        assertTrue(result.absentPacks().isEmpty());
    }

    @Test
    void packChapterMayNotListAnotherNamespace() {
        Quest spine = quest("neroquests:s", "");
        Quest own = quest("mypack:x/own", "");
        QuestDefinitions.Validated result = validate(List.of(spine, own),
                List.of(chapter("mypack:x", "", "neroquests:s", "mypack:x/own")));
        List<Chapter.Entry> entries = result.chapters().get(id("mypack:x")).quests();
        assertEquals(1, entries.size());
        assertEquals(own.id(), entries.get(0).quest());
    }

    @Test
    void modHookRules() {
        Quest good = quest("mypack:x/hook", "\"rewards\":[{\"type\":\"neroquests:mod_hook\",\"gate\":\"mypack:hook/badge\"}]");
        Quest foreign = quest("mypack:x/foreign", "\"rewards\":[{\"type\":\"neroquests:mod_hook\","
                + "\"gate\":\"nerospace:hook/badge\"}]");
        Quest notHook = quest("mypack:x/nothook", "\"rewards\":[{\"type\":\"neroquests:mod_hook\","
                + "\"gate\":\"mypack:first_sale\"}]");
        QuestDefinitions.Validated result = validate(List.of(good, foreign, notHook), List.of());
        assertTrue(result.quests().containsKey(good.id()));
        assertTrue(dropped(result, "mypack:x/foreign").orElseThrow().detail().contains("own namespace"));
        assertTrue(dropped(result, "mypack:x/nothook").orElseThrow().detail().contains("hook/"));
        assertTrue(ModHookReward.invalidFor(id("custom:x/q"), id("custom:hook/a")).isPresent());
        assertTrue(ModHookReward.invalidFor(id("mypack:x/q"), id("mypack:hook/")).isPresent());
        assertTrue(ModHookReward.invalidFor(id("neroquests:x/q"), id("neroquests:hook/a")).isEmpty());
    }
}
