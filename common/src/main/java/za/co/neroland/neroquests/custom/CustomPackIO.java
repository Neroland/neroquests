package za.co.neroland.neroquests.custom;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.PackRules;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestSource;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.objective.ManualObjective;
import za.co.neroland.neroquests.quest.objective.QuestCompleteObjective;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.LootTableReward;

/**
 * Moving custom quests in and out of the world's {@code datapacks/} folder (F2) — the upgrade path from
 * "custom" to "pack", and back.
 *
 * <h2>Export</h2>
 *
 * <p>{@link #export} writes {@code <world>/datapacks/neroquests_custom_export/}: a {@code pack.mcmeta}, the
 * chapters and quests as ordinary quest-pack JSON in namespace {@value #EXPORT_NAMESPACE}
 * ({@code data/custom_export/neroquests/{chapters,quests}/...}), and — because pack titles must be translation
 * keys — a resource-pack-style {@code assets/custom_export/lang/en_us.json} holding the author text under the
 * recommended keys ({@code quest.custom_export.<chapter>.<quest>.title|desc},
 * {@code chapter.custom_export.<chapter>.title}, {@code ...manual<n>} for manual labels). <b>No
 * {@code created_by}</b> and nothing else about any player goes into the export. Repeatable flags and escrow do
 * not exist in packs and are left out. Promote it by moving {@code data/} into a real datapack (or a mod jar)
 * and {@code assets/} into a resource pack (or the same jar), renaming the namespace if wanted, deleting the
 * custom originals, then {@code /reload}.
 *
 * <h2>Import</h2>
 *
 * <p>{@link #importFolder} reads quest-pack JSON from {@code <world>/datapacks/<folder>/}, validates it with
 * {@link QuestDefinitions#validate} exactly as a pack load would, then copies the chapters and the quests they
 * list into the custom store with fresh random ids (references inside the import are remapped; references to
 * spine or pack quests are kept), resolving titles through the folder's {@code assets/<ns>/lang/en_us.json}
 * when present, sanitising every text and applying every custom rule and cap. Nothing is written unless the
 * whole import is clean; {@code dry-run} only reports.
 */
public final class CustomPackIO {

    /** The namespace an export uses. */
    public static final String EXPORT_NAMESPACE = "custom_export";

    /** The datapack folder an export writes. */
    public static final String EXPORT_FOLDER = "neroquests_custom_export";

    private static final Pattern FOLDER = Pattern.compile("[a-z0-9_.\\-]{1,64}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_FILES = 4096;
    private static final long MAX_FILE_BYTES = 262_144L;

    private CustomPackIO() {
    }

    // --- export ----------------------------------------------------------------------

    /**
     * What an export wrote.
     *
     * @param chapters           chapters written
     * @param quests             quests written
     * @param externalReferences prerequisites / quest_complete targets left pointing outside the export (custom
     *                           quests of other chapters, or another pack's quests, which a pack may not name)
     * @param repeatable         repeatable quests exported as one-off quests
     */
    public record ExportResult(int chapters, int quests, int externalReferences, int repeatable) {
    }

    /** Export every custom chapter, or only {@code chapterSlug}'s. */
    public static ExportResult export(MinecraftServer server, Optional<String> chapterSlug) throws IOException {
        CustomQuestState state = CustomQuestState.get(server);
        Path root = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(EXPORT_FOLDER);
        Path data = root.resolve("data").resolve(EXPORT_NAMESPACE).resolve("neroquests");
        Path lang = root.resolve("assets").resolve(EXPORT_NAMESPACE).resolve("lang");
        deleteTree(root.resolve("data").resolve(EXPORT_NAMESPACE));
        deleteTree(root.resolve("assets").resolve(EXPORT_NAMESPACE));
        Files.createDirectories(data);
        Files.createDirectories(lang);
        writeJson(root.resolve("pack.mcmeta"), packMeta());

        Map<String, String> translations = new LinkedHashMap<>();
        int chapters = 0;
        int quests = 0;
        int external = 0;
        int repeatable = 0;
        for (CustomChapter chapter : state.chapters().values()) {
            if (chapterSlug.isPresent() && !chapterSlug.get().equals(chapter.slug())) {
                continue;
            }
            Identifier chapterId = Identifier.fromNamespaceAndPath(EXPORT_NAMESPACE, chapter.slug());
            String chapterKey = PackRules.recommendedKey("chapter", chapterId, "title");
            translations.put(chapterKey, chapter.title());
            List<Chapter.Entry> entries = new ArrayList<>();
            for (CustomQuest row : state.questsOf(chapter.slug())) {
                Optional<Quest> decoded = row.toQuest();
                if (decoded.isEmpty()) {
                    continue;
                }
                Quest quest = decoded.get();
                Identifier id = remap(quest.id());
                entries.add(new Chapter.Entry(id, row.x(), row.y()));
                String titleKey = PackRules.recommendedKey("quest", id, "title");
                translations.put(titleKey, quest.title());
                String descriptionKey = "";
                if (!quest.description().isEmpty()) {
                    descriptionKey = PackRules.recommendedKey("quest", id, "desc");
                    translations.put(descriptionKey, quest.description());
                }
                List<Identifier> prerequisites = new ArrayList<>();
                for (Identifier prerequisite : quest.prerequisites()) {
                    if (outside(prerequisite, chapter.slug())) {
                        external++;
                    }
                    prerequisites.add(remap(prerequisite));
                }
                List<ObjectiveSpec> objectives = new ArrayList<>();
                int manual = 0;
                for (ObjectiveSpec objective : quest.objectives()) {
                    if (objective instanceof ManualObjective label) {
                        String key = PackRules.recommendedKey("quest", id, "manual" + manual++);
                        translations.put(key, label.label());
                        objectives.add(new ManualObjective(key));
                    } else if (objective instanceof QuestCompleteObjective complete) {
                        if (outside(complete.quest(), chapter.slug())) {
                            external++;
                        }
                        objectives.add(new QuestCompleteObjective(remap(complete.quest())));
                    } else {
                        objectives.add(objective);
                    }
                }
                if (row.repeatable()) {
                    repeatable++;
                }
                Quest.Data body = new Quest.Data(titleKey, descriptionKey, quest.icon(), prerequisites, objectives,
                        quest.rewards(), QuestScope.PLAYER, Optional.empty());
                JsonElement json = Quest.DATA_CODEC.encodeStart(JsonOps.INSTANCE, body).result()
                        .orElseThrow(() -> new IOException("a quest could not be encoded"));
                writeJson(data.resolve("quests").resolve(chapter.slug()).resolve(row.id().getPath()
                        .substring(chapter.slug().length() + 1) + ".json"), json);
                quests++;
            }
            Chapter.Data body = new Chapter.Data(chapterKey, chapter.icon(), entries);
            JsonElement json = Chapter.DATA_CODEC.encodeStart(JsonOps.INSTANCE, body).result()
                    .orElseThrow(() -> new IOException("a chapter could not be encoded"));
            writeJson(data.resolve("chapters").resolve(chapter.slug() + ".json"), json);
            chapters++;
        }
        JsonObject langJson = new JsonObject();
        translations.forEach(langJson::addProperty);
        writeJson(lang.resolve("en_us.json"), langJson);
        return new ExportResult(chapters, quests, external, repeatable);
    }

    /** Whether a reference leaves the exported chapter (another chapter's custom quest, or another pack's quest). */
    private static boolean outside(Identifier target, String chapterSlug) {
        QuestSource source = QuestSource.of(target);
        if (source == QuestSource.CUSTOM) {
            return !target.getPath().startsWith(chapterSlug + "/");
        }
        return source == QuestSource.PACK;
    }

    private static Identifier remap(Identifier id) {
        return QuestSource.of(id) == QuestSource.CUSTOM
                ? Identifier.fromNamespaceAndPath(EXPORT_NAMESPACE, id.getPath())
                : id;
    }

    private static JsonObject packMeta() {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "NeroQuests custom quest export");
        pack.addProperty("pack_format", 84);
        pack.addProperty("min_format", 84);
        pack.addProperty("max_format", 999);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    // --- import ----------------------------------------------------------------------

    /**
     * What an import found (and did, unless dry-run or refused).
     *
     * @param chapters chapters that would be (or were) imported
     * @param quests   quests that would be (or were) imported
     * @param problems everything that stops the import (empty: clean) — pack content ids and reasons only
     * @param notes    things skipped without stopping it (a quest no chapter lists)
     * @param applied  whether the store was changed
     */
    public record ImportResult(int chapters, int quests, List<String> problems, List<String> notes, boolean applied) {
    }

    /** Whether {@code folder} is an acceptable datapack folder name (no separators, no dot-dot). */
    public static boolean validFolderName(String folder) {
        return folder != null && FOLDER.matcher(folder).matches() && !folder.contains("..");
    }

    /** Import {@code <world>/datapacks/<folder>/} into the custom store (or only report, with {@code dryRun}). */
    public static ImportResult importFolder(MinecraftServer server, String folder, boolean dryRun,
                                            CustomQuests.Actor actor) throws IOException {
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (!validFolderName(folder)) {
            problems.add("folder name must be lower-case letters, digits, '_', '-' or '.'");
            return new ImportResult(0, 0, problems, notes, false);
        }
        Path datapacks = server.getWorldPath(LevelResource.DATAPACK_DIR).normalize();
        Path root = datapacks.resolve(folder).normalize();
        if (!root.startsWith(datapacks) || !Files.isDirectory(root.resolve("data"))) {
            problems.add("no datapack folder " + folder + " with a data/ directory");
            return new ImportResult(0, 0, problems, notes, false);
        }

        Map<Identifier, Quest> decodedQuests = new LinkedHashMap<>();
        Map<Identifier, Chapter> decodedChapters = new LinkedHashMap<>();
        Map<String, Map<String, String>> langs = new LinkedHashMap<>();
        try (Stream<Path> namespaces = Files.list(root.resolve("data"))) {
            for (Path namespaceDir : namespaces.filter(Files::isDirectory).toList()) {
                String namespace = namespaceDir.getFileName().toString();
                if (!Identifier.isValidNamespace(namespace)) {
                    continue;
                }
                if (QuestSource.CUSTOM_NAMESPACE.equals(namespace)) {
                    problems.add("namespace 'custom' is reserved; export with /neroquests custom export instead");
                    continue;
                }
                readDefinitions(namespaceDir.resolve("neroquests").resolve("quests"), namespace, problems,
                        (id, json) -> QuestDefinitions.decodeStrict(Quest.DATA_CODEC.parse(JsonOps.INSTANCE, json),
                                error -> problems.add(id + ": " + error)).ifPresent(body -> decodedQuests.put(id,
                                new Quest(id, body))));
                readDefinitions(namespaceDir.resolve("neroquests").resolve("chapters"), namespace, problems,
                        (id, json) -> QuestDefinitions.decodeStrict(Chapter.DATA_CODEC.parse(JsonOps.INSTANCE, json),
                                error -> problems.add(id + ": " + error)).ifPresent(body -> decodedChapters.put(id,
                                new Chapter(id, body))));
                langs.put(namespace, readLang(root.resolve("assets").resolve(namespace).resolve("lang")
                        .resolve("en_us.json")));
            }
        }
        if (decodedChapters.isEmpty()) {
            problems.add("no chapters found under data/<namespace>/neroquests/chapters/");
        }
        QuestDefinitions.Validated validated =
                QuestDefinitions.validate(decodedQuests, decodedChapters, QuestDefinitions.Presence.RUNTIME);
        for (QuestDefinitions.ValidationIssue issue : validated.issues()) {
            if (issue.severity() == QuestDefinitions.ValidationIssue.Severity.DROPPED) {
                problems.add(issue.id() + ": " + issue.detail());
            } else {
                notes.add(issue.id() + ": " + issue.detail());
            }
        }

        CustomLimits limits = CustomLimits.current();
        CustomQuestState state = CustomQuestState.get(server);
        CustomQuestState preview = state.copy();
        Map<Identifier, Identifier> newIds = new LinkedHashMap<>();
        Map<Identifier, String> chapterOf = new LinkedHashMap<>();
        int importedChapters = 0;
        for (Chapter chapter : validated.chapters().values()) {
            if (!decodedChapters.containsKey(chapter.id())) {
                continue;
            }
            Map<String, String> lang = langs.getOrDefault(chapter.id().getNamespace(), Map.of());
            Optional<String> title = text(lang, chapter.title(), TextSanitizer.TITLE_MAX);
            if (title.isEmpty() || title.get().isEmpty()) {
                problems.add(chapter.id() + ": title is empty or longer than " + TextSanitizer.TITLE_MAX
                        + " characters");
                continue;
            }
            String slug = SlugGenerator.unique(candidate -> preview.chapters().containsKey(candidate));
            int order = preview.chapters().values().stream().mapToInt(CustomChapter::order).max().orElse(-1) + 1;
            preview.putChapter(new CustomChapter(slug, title.get(), chapter.icon(),
                    Math.min(order, CustomQuests.MAX_ORDER), System.currentTimeMillis()));
            importedChapters++;
            for (Chapter.Entry entry : chapter.quests()) {
                String questSlug = SlugGenerator.unique(candidate -> preview.questSlugTaken(slug, candidate)
                        || newIds.containsValue(Identifier.fromNamespaceAndPath(QuestSource.CUSTOM_NAMESPACE,
                                slug + "/" + candidate)));
                newIds.put(entry.quest(), Identifier.fromNamespaceAndPath(QuestSource.CUSTOM_NAMESPACE,
                        slug + "/" + questSlug));
                chapterOf.put(entry.quest(), slug);
            }
        }
        for (Identifier id : validated.quests().keySet()) {
            if (decodedQuests.containsKey(id) && !newIds.containsKey(id)) {
                notes.add(id + ": not listed by an imported chapter; skipped");
            }
        }
        if (preview.chapters().size() > limits.maxChapters()) {
            problems.add("the import would exceed customChapterMax (" + limits.maxChapters() + ")");
        }
        if (state.quests().size() + newIds.size() > limits.maxQuests()) {
            problems.add("the import would exceed customQuestMaxTotal (" + limits.maxQuests() + ")");
        }

        Optional<UUID> author = actor.id();
        Map<Identifier, Chapter.Entry> positions = new LinkedHashMap<>();
        validated.chapters().values().forEach(chapter -> chapter.quests().forEach(e -> positions.put(e.quest(), e)));
        for (Map.Entry<Identifier, Identifier> mapping : newIds.entrySet()) {
            Quest quest = validated.quests().get(mapping.getKey());
            if (quest == null) {
                continue;
            }
            Identifier newId = mapping.getValue();
            Map<String, String> lang = langs.getOrDefault(quest.id().getNamespace(), Map.of());
            Optional<String> title = text(lang, quest.title(), TextSanitizer.TITLE_MAX);
            Optional<String> description = text(lang, quest.description(), TextSanitizer.DESCRIPTION_MAX);
            if (title.isEmpty() || title.get().isEmpty() || description.isEmpty()) {
                problems.add(quest.id() + ": title or description is empty or too long");
                continue;
            }
            if (quest.scope() != QuestScope.PLAYER || quest.visibleGate().isPresent()) {
                problems.add(quest.id() + ": custom quests are player-scope only and cannot use visible_gate");
                continue;
            }
            List<ObjectiveSpec> objectives = new ArrayList<>();
            for (ObjectiveSpec objective : quest.objectives()) {
                if (objective instanceof ManualObjective manual) {
                    objectives.add(new ManualObjective(text(lang, manual.label(), TextSanitizer.LABEL_MAX).orElse("")));
                } else if (objective instanceof QuestCompleteObjective complete) {
                    objectives.add(new QuestCompleteObjective(newIds.getOrDefault(complete.quest(), complete.quest())));
                } else {
                    objectives.add(objective);
                }
            }
            List<ObjectiveSpec> cleaned = new ArrayList<>();
            Optional<EditResult> objectiveError = checkImportedObjectives(server, objectives, newIds, cleaned);
            if (objectiveError.isPresent()) {
                problems.add(quest.id() + ": objective rejected (" + objectiveError.get().key() + " "
                        + objectiveError.get().args() + ")");
                continue;
            }
            List<RewardSpec> rewards = quest.rewards();
            Optional<EditResult> rewardError = CustomQuests.checkRewards(server, rewards, limits);
            if (rewardError.isPresent()) {
                problems.add(quest.id() + ": reward rejected (" + rewardError.get().key() + " "
                        + rewardError.get().args() + ")");
                continue;
            }
            if (limits.escrow() && rewards.stream().anyMatch(reward -> reward instanceof ItemReward
                    || reward instanceof LootTableReward)) {
                problems.add(quest.id() + ": escrow mode is on; item and loot-table rewards cannot be imported "
                        + "(set them in the editor with a deposit)");
                continue;
            }
            List<Identifier> prerequisites = new ArrayList<>();
            for (Identifier prerequisite : quest.prerequisites()) {
                prerequisites.add(newIds.getOrDefault(prerequisite, prerequisite));
            }
            Chapter.Entry position = positions.get(quest.id());
            int x = position == null ? 0 : Math.max(CustomLimits.MIN_COORD, Math.min(CustomLimits.MAX_COORD, position.x()));
            int y = position == null ? 0 : Math.max(CustomLimits.MIN_COORD, Math.min(CustomLimits.MAX_COORD, position.y()));
            long now = System.currentTimeMillis();
            CustomQuest row = CustomQuest.create(newId, chapterOf.get(quest.id()), title.get(), quest.icon(), x, y,
                    cleaned, author, now)
                    .withText(title.get(), description.get(), quest.icon(), now)
                    .withPrerequisites(prerequisites, now)
                    .withRewards(rewards, List.of(), now);
            preview.putQuest(row);
        }

        if (problems.isEmpty()) {
            QuestDefinitions.Validated after = QuestDefinitions.preview(server, CustomContent.build(preview));
            for (Identifier newId : newIds.values()) {
                if (preview.quest(newId).isPresent() && !after.quests().containsKey(newId)) {
                    String reason = after.issues().stream().filter(issue -> issue.id().equals(newId))
                            .map(QuestDefinitions.ValidationIssue::detail).findFirst().orElse("dropped");
                    problems.add(newId + ": " + reason);
                }
            }
            CustomQuests.previewRejects(server, preview, null)
                    .ifPresent(error -> problems.add(error.args().toString()));
        }
        int importedQuests = (int) newIds.values().stream().filter(id -> preview.quest(id).isPresent()).count();
        if (dryRun || !problems.isEmpty()) {
            return new ImportResult(importedChapters, importedQuests, problems, notes, false);
        }
        state.adopt(preview);
        CustomQuests.changed(server);
        return new ImportResult(importedChapters, importedQuests, problems, notes, true);
    }

    /** Objective checks for an import: quest_complete targets inside the import resolve to their new ids. */
    private static Optional<EditResult> checkImportedObjectives(MinecraftServer server, List<ObjectiveSpec> objectives,
                                                                Map<Identifier, Identifier> newIds,
                                                                List<ObjectiveSpec> out) {
        List<ObjectiveSpec> external = new ArrayList<>();
        for (ObjectiveSpec objective : objectives) {
            if (!(objective instanceof QuestCompleteObjective complete && newIds.containsValue(complete.quest()))) {
                external.add(objective);
            }
        }
        Optional<EditResult> error = CustomQuests.checkObjectives(server, external, new ArrayList<>());
        if (error.isPresent() && !(external.isEmpty() && error.get().key().equals(EditResult.OBJECTIVE_NONE))) {
            return error;
        }
        if (objectives.isEmpty() || objectives.size() > CustomLimits.MAX_OBJECTIVES) {
            return Optional.of(EditResult.error(EditResult.CAP_OBJECTIVES, Integer.toString(CustomLimits.MAX_OBJECTIVES)));
        }
        for (ObjectiveSpec objective : objectives) {
            if (objective instanceof ManualObjective manual) {
                out.add(new ManualObjective(TextSanitizer.clean(manual.label())));
            } else {
                out.add(objective);
            }
        }
        return Optional.empty();
    }

    /** {@code value} resolved through {@code lang} when it is a key there, sanitised and within {@code max}. */
    private static Optional<String> text(Map<String, String> lang, String value, int max) {
        String resolved = lang.getOrDefault(value, value);
        return TextSanitizer.cleanWithin(resolved, max);
    }

    @FunctionalInterface
    private interface DefinitionReader {
        void read(Identifier id, JsonElement json);
    }

    private static void readDefinitions(Path directory, String namespace, List<String> problems,
                                        DefinitionReader reader) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(directory)) {
            files = walk.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".json"))
                    .limit(MAX_FILES).toList();
        }
        for (Path file : files) {
            String relative = directory.relativize(file).toString().replace('\\', '/');
            String path = relative.substring(0, relative.length() - ".json".length());
            Identifier id = Identifier.tryBuild(namespace, path);
            if (id == null) {
                problems.add(namespace + ":" + path + ": not a valid id");
                continue;
            }
            if (Files.size(file) > MAX_FILE_BYTES) {
                problems.add(id + ": file too large");
                continue;
            }
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                reader.read(id, JsonParser.parseReader(in));
            } catch (RuntimeException e) {
                problems.add(id + ": not readable JSON");
            }
        }
    }

    private static Map<String, String> readLang(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement json = JsonParser.parseReader(in);
            if (json.isJsonObject()) {
                json.getAsJsonObject().entrySet().forEach(entry -> {
                    if (entry.getValue().isJsonPrimitive()) {
                        out.put(entry.getKey(), entry.getValue().getAsString());
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable lang file just means keys stay keys
        }
        return out;
    }

    private static void writeJson(Path file, JsonElement json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
    }

    /** Delete a directory tree this exporter owns (inside its own export folder only). */
    private static void deleteTree(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
