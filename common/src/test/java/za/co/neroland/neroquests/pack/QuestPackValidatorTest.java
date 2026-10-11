package za.co.neroland.neroquests.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** B5: the pack validator accepts the spine and a clean pack, and names every broken rule. */
class QuestPackValidatorTest {

    private static Path resources() {
        String configured = System.getProperty("neroquests.commonResources");
        return configured != null ? Path.of(configured) : Path.of("../../../common/src/main/resources");
    }

    private static void write(Path root, String rel, String text) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    void theSpineValidates() throws IOException {
        List<String> problems = QuestPackValidator.validate(resources(), "neroquests", null);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void aCleanPackValidates(@TempDir Path root) throws IOException {
        write(root, "data/mypack/neroquests/chapters/start.json", "{\"title\":\"chapter.mypack.start.title\","
                + "\"quests\":[{\"quest\":\"mypack:start/first\"},{\"quest\":\"mypack:start/second\",\"x\":2}]}");
        write(root, "data/mypack/neroquests/quests/start/first.json", "{\"title\":\"quest.mypack.start.first.title\","
                + "\"prerequisites\":[\"neroquests:groundwork/first_tools\"],"
                + "\"objectives\":[{\"type\":\"neroquests:craft_item\",\"item\":\"minecraft:furnace\"}]}");
        write(root, "data/mypack/neroquests/quests/start/second.json", "{\"title\":\"quest.mypack.start.second.title\","
                + "\"description\":\"quest.mypack.start.second.desc\",\"prerequisites\":[\"mypack:start/first\"],"
                + "\"objectives\":[{\"type\":\"neroquests:manual\",\"label\":\"quest.mypack.start.second.manual\"}],"
                + "\"rewards\":[{\"type\":\"neroquests:mod_hook\",\"gate\":\"mypack:hook/second\"}]}");
        write(root, "assets/mypack/lang/en_us.json", "{\"chapter.mypack.start.title\":\"Start\","
                + "\"quest.mypack.start.first.title\":\"First\",\"quest.mypack.start.second.title\":\"Second\","
                + "\"quest.mypack.start.second.desc\":\"Do it\"}");
        List<String> problems = QuestPackValidator.validate(root, "mypack", resources());
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void everyBrokenRuleIsNamed(@TempDir Path root) throws IOException {
        write(root, "data/mypack/neroquests/chapters/bad.json", "{\"title\":\"chapter.mypack.bad.title\","
                + "\"quests\":[{\"quest\":\"mypack:bad/gate\"},{\"quest\":\"mypack:bad/world\"}]}");
        write(root, "data/mypack/neroquests/quests/bad/gate.json", "{\"title\":\"quest.mypack.bad.gate.title\","
                + "\"objectives\":[{\"type\":\"neroquests:craft_item\",\"item\":\"minecraft:furnace\"}],"
                + "\"rewards\":[{\"type\":\"neroquests:gate\",\"gate\":\"nerolandcore:deep_space\"}]}");
        write(root, "data/mypack/neroquests/quests/bad/world.json", "{\"title\":\"quest.mypack.bad.world.title\","
                + "\"scope\":\"server\",\"objectives\":[{\"type\":\"neroquests:custom_event\",\"channel\":\"mypack:x\"}]}");
        write(root, "data/mypack/neroquests/quests/bad/cross.json", "{\"title\":\"quest.mypack.bad.cross.title\","
                + "\"prerequisites\":[\"nerospace:star/first\"],"
                + "\"objectives\":[{\"type\":\"neroquests:craft_item\",\"item\":\"minecraft:furnace\"}]}");
        write(root, "data/mypack/neroquests/quests/bad/broken.json", "{\"title\":\"quest.mypack.bad.broken.title\","
                + "\"objectives\":[{\"type\":\"neroquests:craft_item\",\"count\":0}]}");
        write(root, "assets/mypack/lang/en_us.json", "{\"chapter.mypack.bad.title\":\"Bad\"}");
        List<String> problems = QuestPackValidator.validate(root, "mypack", resources());
        String all = String.join("\n", problems);
        assertTrue(all.contains("mypack:bad/gate: dropped — reward type neroquests:gate"), all);
        assertTrue(all.contains("mypack:bad/world: dropped — \"scope\": \"server\""), all);
        assertTrue(all.contains("mypack:bad/cross: dropped — prerequisite nerospace:star/first"), all);
        assertTrue(all.contains("mypack:bad/broken: dropped"), all);
        assertTrue(all.contains("en_us.json has no key quest.mypack.bad.cross.title"), all);
    }

    @Test
    void anEmptyPackSaysSo(@TempDir Path root) throws IOException {
        assertEquals(1, QuestPackValidator.validate(root, "mypack", null).size());
    }
}
