package za.co.neroland.neroquests.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.objective.UnknownObjective;
import za.co.neroland.neroquests.quest.reward.UnknownReward;

/**
 * Validates one quest pack the way a running server would (B5), without a server: every chapter and
 * quest JSON under {@code <root>/data/<namespace>/neroquests/} is decoded with the real codecs, run
 * through the real load pipeline ({@link QuestDefinitions#validate} — reserved namespace, pack rules,
 * prerequisites, cycles, chapter entries) as if the pack's mod were installed, and every title,
 * description and chapter title key is looked up in {@code <root>/assets/<namespace>/lang/en_us.json}.
 *
 * <p>Pure JVM: no Minecraft bootstrap. NeroQuests' own tests use it for the spine and for the sibling
 * packs (H11); a sibling repo copies the Gson-only version from {@code wiki/Shipping-a-Quest-Pack.md}
 * instead, so it needs no dependency on NeroQuests.
 */
public final class QuestPackValidator {

    private QuestPackValidator() {
    }

    /**
     * Every problem in the pack at {@code root} for {@code namespace}, empty when it would load cleanly.
     *
     * @param root      a resources root (the folder holding {@code data/} and {@code assets/})
     * @param namespace the pack's namespace (the mod id)
     * @param spineRoot NeroQuests' own resources root, so prerequisites on spine quests resolve; or
     *                  {@code null} to accept any {@code neroquests:} prerequisite as present
     */
    public static List<String> validate(Path root, String namespace, Path spineRoot) throws IOException {
        QuestTypes.init();
        List<String> problems = new ArrayList<>();
        Map<Identifier, Quest> quests = new LinkedHashMap<>();
        Map<Identifier, Chapter> chapters = new LinkedHashMap<>();
        Path data = root.resolve("data/" + namespace + "/neroquests");
        decodeAll(data.resolve("quests"), namespace, Quest.DATA_CODEC, Quest::new, quests, problems);
        decodeAll(data.resolve("chapters"), namespace, Chapter.DATA_CODEC, Chapter::new, chapters, problems);
        if (quests.isEmpty() && chapters.isEmpty() && problems.isEmpty()) {
            problems.add("no quest or chapter files under " + root.relativize(data));
            return problems;
        }
        if (spineRoot != null && !NeroQuestsCommon.MOD_ID.equals(namespace)) {
            Path spine = spineRoot.resolve("data/" + NeroQuestsCommon.MOD_ID + "/neroquests");
            Map<Identifier, Quest> spineQuests = new LinkedHashMap<>();
            decodeAll(spine.resolve("quests"), NeroQuestsCommon.MOD_ID, Quest.DATA_CODEC, Quest::new, spineQuests,
                    new ArrayList<>());
            spineQuests.forEach(quests::putIfAbsent);
        }
        QuestDefinitions.Validated result = QuestDefinitions.validate(quests, chapters, new QuestDefinitions.Presence() {
            @Override
            public boolean modLoaded(String candidate) {
                return true; // validate the pack as if its mod is installed
            }

            @Override
            public boolean isModId(String candidate) {
                return true;
            }
        });
        for (QuestDefinitions.ValidationIssue issue : result.issues()) {
            if (!issue.id().getNamespace().equals(namespace)) {
                continue;
            }
            if (spineRoot == null && issue.detail().startsWith("unknown prerequisite " + NeroQuestsCommon.MOD_ID + ":")) {
                continue; // spine not supplied: a spine prerequisite is assumed present
            }
            problems.add(issue.id() + ": " + issue.severity().name().toLowerCase(java.util.Locale.ROOT) + " — "
                    + issue.detail());
        }
        // An unknown type id decodes (forward compatibility) but can never be measured or paid: a typo.
        quests.forEach((id, quest) -> {
            if (!id.getNamespace().equals(namespace)) {
                return;
            }
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
        });
        Path langFile = root.resolve("assets/" + namespace + "/lang/en_us.json");
        if (!Files.isRegularFile(langFile)) {
            problems.add("missing " + root.relativize(langFile));
            return problems;
        }
        JsonObject lang = JsonParser.parseString(Files.readString(langFile, StandardCharsets.UTF_8)).getAsJsonObject();
        quests.forEach((id, quest) -> {
            if (!id.getNamespace().equals(namespace)) {
                return;
            }
            requireKey(problems, lang, id, quest.title());
            if (!quest.description().isEmpty()) {
                requireKey(problems, lang, id, quest.description());
            }
        });
        chapters.forEach((id, chapter) -> requireKey(problems, lang, id, chapter.title()));
        return problems;
    }

    private static void requireKey(List<String> problems, JsonObject lang, Identifier owner, String key) {
        if (!lang.has(key)) {
            problems.add(owner + ": en_us.json has no key " + key);
        }
    }

    private static <D, T> void decodeAll(Path dir, String namespace, Codec<D> codec, BiFunction<Identifier, D, T> build,
                                         Map<Identifier, T> out, List<String> problems) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            String rel = dir.relativize(file).toString().replace('\\', '/');
            Identifier id = Identifier.tryParse(namespace + ":" + rel.substring(0, rel.length() - ".json".length()));
            if (id == null) {
                problems.add(file.getFileName() + ": not a valid id (use lower-case a-z, 0-9, _ - . /)");
                continue;
            }
            JsonElement json;
            try {
                json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                problems.add(id + ": not valid JSON (" + e.getClass().getSimpleName() + ")");
                continue;
            }
            DataResult<D> result = codec.parse(JsonOps.INSTANCE, json);
            if (result.error().isPresent()) {
                problems.add(id + ": dropped — " + result.error().get().message());
                continue;
            }
            out.put(id, build.apply(id, result.result().orElseThrow()));
        }
    }
}
