package za.co.neroland.neroquests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.contract.DailyContract;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.objective.CustomEventObjective;
import za.co.neroland.neroquests.quest.objective.EconomyObjective;
import za.co.neroland.neroquests.quest.objective.GateOpenObjective;
import za.co.neroland.neroquests.quest.objective.ReachDimensionObjective;
import za.co.neroland.neroquests.quest.objective.UnknownObjective;
import za.co.neroland.neroquests.quest.reward.UnknownReward;

/**
 * Loads every bundled quest, chapter and daily-contract JSON by file path, decodes it with the real
 * codecs and checks the cross-file rules the loader would otherwise only report at runtime (AUD-27).
 * Pure data: no Minecraft bootstrap, no server.
 */
class BundledContentTest {

    private static final String NAMESPACE = NeroQuestsCommon.MOD_ID;

    private static Map<Identifier, Quest> quests;
    private static Map<Identifier, Chapter> chapters;
    private static Map<Identifier, DailyContract> contracts;
    private static JsonObject lang;
    private static final List<String> DECODE_ERRORS = new ArrayList<>();

    @BeforeAll
    static void load() throws IOException {
        QuestTypes.init();
        Path resources = resources();
        Path data = resources.resolve("data/" + NAMESPACE + "/neroquests");
        quests = decodeAll(data.resolve("quests"), Quest.DATA_CODEC, Quest::new);
        chapters = decodeAll(data.resolve("chapters"), Chapter.DATA_CODEC, Chapter::new);
        contracts = decodeAll(data.resolve("contracts"), DailyContract.Data.CODEC, DailyContract::new);
        lang = parse(resources.resolve("assets/" + NAMESPACE + "/lang/en_us.json")).getAsJsonObject();
    }

    private static Path resources() {
        String configured = System.getProperty("neroquests.commonResources");
        return configured != null ? Path.of(configured) : Path.of("../../../common/src/main/resources");
    }

    private static JsonElement parse(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Decode every {@code *.json} under {@code dir}; the id is the namespaced path without the extension. */
    private static <D, T> Map<Identifier, T> decodeAll(Path dir, Codec<D> codec, BiFunction<Identifier, D, T> build)
            throws IOException {
        Map<Identifier, T> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            String rel = dir.relativize(file).toString().replace('\\', '/');
            Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, rel.substring(0, rel.length() - ".json".length()));
            DataResult<D> result = codec.parse(JsonOps.INSTANCE, parse(file));
            result.error().ifPresent(error -> DECODE_ERRORS.add(id + ": " + error.message()));
            result.result().ifPresent(decoded -> out.put(id, build.apply(id, decoded)));
        }
        return out;
    }

    private static void assertNone(List<String> problems, String what) {
        assertTrue(problems.isEmpty(), what + ":\n  " + String.join("\n  ", problems));
    }

    @Test
    void contentIsBundled() {
        assertFalse(quests.isEmpty(), "no bundled quests found");
        assertFalse(chapters.isEmpty(), "no bundled chapters found");
    }

    /** I1: the spine is 28 quests after the three colony quests moved to the NeroColonies pack. */
    @Test
    void spineCountAfterColonyQuestsMoved() {
        assertEquals(28, quests.size(), "spine quest count");
        for (String moved : List.of("breathing_room", "full_larder", "settled_ground")) {
            assertFalse(quests.containsKey(Identifier.fromNamespaceAndPath(NAMESPACE, "space_race/" + moved)),
                    moved + " now lives in the NeroColonies pack");
            assertFalse(lang.has("quest.neroquests.space_race." + moved + ".title"), moved + " lang key left behind");
        }
        assertTrue(quests.containsKey(Identifier.fromNamespaceAndPath(NAMESPACE, "groundwork/what_this_book_is")));
    }

    @Test
    void everyFileDecodesWithoutError() {
        assertNone(DECODE_ERRORS, "decode errors (a partial decode would silently damage the quest)");
    }

    @Test
    void everyObjectiveAndRewardTypeIsKnown() {
        List<String> problems = new ArrayList<>();
        quests.forEach((id, quest) -> {
            for (ObjectiveSpec objective : quest.objectives()) {
                if (objective instanceof UnknownObjective unknown) {
                    problems.add(id + ": unknown objective type " + unknown.typeId());
                }
            }
            for (RewardSpec reward : quest.rewards()) {
                if (reward instanceof UnknownReward) {
                    problems.add(id + ": unknown reward type " + reward.typeId());
                }
            }
            if (quest.objectives().isEmpty()) {
                problems.add(id + ": no objectives");
            }
        });
        assertNone(problems, "unknown types");
    }

    @Test
    void everyPrerequisiteResolves() {
        List<String> problems = new ArrayList<>();
        quests.forEach((id, quest) -> {
            for (Identifier prerequisite : quest.prerequisites()) {
                if (!quests.containsKey(prerequisite)) {
                    problems.add(id + " -> " + prerequisite);
                }
                if (prerequisite.equals(id)) {
                    problems.add(id + " lists itself");
                }
            }
        });
        assertNone(problems, "dangling prerequisites");
    }

    @Test
    void everyChapterEntryExistsAndBelongsToOneChapter() {
        List<String> problems = new ArrayList<>();
        Map<Identifier, Identifier> owner = new HashMap<>();
        chapters.forEach((id, chapter) -> {
            for (Chapter.Entry entry : chapter.quests()) {
                if (!quests.containsKey(entry.quest())) {
                    problems.add(id + " lists missing quest " + entry.quest());
                }
                Identifier previous = owner.put(entry.quest(), id);
                if (previous != null && !previous.equals(id)) {
                    problems.add(entry.quest() + " is in both " + previous + " and " + id);
                }
            }
        });
        assertNone(problems, "chapter entries");
    }

    @Test
    void everyTextKeyIsTranslated() {
        List<String> problems = new ArrayList<>();
        quests.forEach((id, quest) -> {
            requireKey(problems, id, quest.title());
            if (!quest.description().isEmpty()) {
                requireKey(problems, id, quest.description());
            }
        });
        chapters.forEach((id, chapter) -> requireKey(problems, id, chapter.title()));
        contracts.forEach((id, contract) -> requireKey(problems, id, contract.title()));
        assertNone(problems, "missing en_us.json keys");
    }

    private static void requireKey(List<String> problems, Identifier owner, String key) {
        if (!lang.has(key)) {
            problems.add(owner + ": " + key);
        }
    }

    @Test
    void noWorldAudienceEventOnPlayerScopeQuest() {
        List<String> problems = new ArrayList<>();
        quests.forEach((id, quest) -> {
            if (quest.scope() != QuestScope.PLAYER) {
                return;
            }
            for (ObjectiveSpec objective : quest.objectives()) {
                if (objective instanceof CustomEventObjective event
                        && event.audience() == CustomEventObjective.Audience.WORLD) {
                    problems.add(id + ": custom_event " + event.channel() + " has audience world (the default)");
                }
            }
        });
        assertNone(problems, "custom_event audience world can never credit a player-scope quest");
    }

    /**
     * Spine honesty (AUD-11/AUD-12): a quest that can only be finished with another mod present — a
     * gate it does not open itself, another mod's dimension, a world-event channel or the economy — must
     * carry a {@code visible_gate}, so a pack without that mod never shows it as permanently stuck. A
     * quest whose prerequisite is hidden must be hidden too, or it would sit locked forever.
     */
    @Test
    void questsThatNeedAnotherModAreHiddenUntilItShowsUp() {
        List<String> problems = new ArrayList<>();
        quests.forEach((id, quest) -> {
            if (quest.visibleGate().isPresent()) {
                return;
            }
            for (ObjectiveSpec objective : quest.objectives()) {
                boolean external = objective instanceof GateOpenObjective
                        || objective instanceof CustomEventObjective
                        || objective instanceof EconomyObjective
                        || (objective instanceof ReachDimensionObjective reach
                                && !"minecraft".equals(reach.dimension().getNamespace()));
                if (external) {
                    problems.add(id + ": " + objective.typeId() + " needs another mod but the quest has no visible_gate");
                }
            }
            for (Identifier prerequisite : quest.prerequisites()) {
                Quest before = quests.get(prerequisite);
                if (before != null && before.visibleGate().isPresent()) {
                    problems.add(id + ": prerequisite " + prerequisite + " is hidden but this quest is not");
                }
            }
        });
        assertNone(problems, "quests that would read as permanently stuck");
    }
}
