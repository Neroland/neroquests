package za.co.neroland.neroquests.quest;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.objective.UnknownObjective;
import za.co.neroland.neroquests.quest.reward.UnknownReward;

/**
 * The active set of quest and chapter definitions, loaded from datapacks. One JSON per quest
 * under {@code data/<namespace>/neroquests/quests/<path>.json} and one per chapter under
 * {@code data/<namespace>/neroquests/chapters/<path>.json}; in both cases the id is the file's
 * namespace + path without the extension, so a pack overrides a definition simply by shipping
 * the same id.
 *
 * <p>Lifecycle follows Neroland Core's {@code GateDefinitions}: definitions are read from the
 * running server's {@link ResourceManager} lazily on first use and cached, so a fully-completed
 * pack costs no I/O. NeroQuests goes one step further than Core and also survives {@code /reload}:
 * the cache is keyed on the <em>{@link ResourceManager} instance</em> as well as the server, and
 * {@code MinecraftServer.reloadResources} replaces that instance wholesale, so a reload is
 * detected by an identity comparison in pure common code — no per-loader reload-listener API to
 * register three different ways, and no divergence between loaders. {@link #reload(MinecraftServer)}
 * forces the same re-read explicitly.
 *
 * <p>Re-reading leaves stored progress untouched: progress for a quest that has disappeared is
 * simply retained but inert, and a quest whose objectives grew keeps the counters it had.
 *
 * <p>{@link #generation()} counts loads, so a consumer that caches something derived from the
 * definitions (the client-sync snapshot does) can tell when to rebuild it.
 *
 * <p><b>Nothing here ever crashes on bad content.</b> Every malformed file, unknown type,
 * dangling reference, cycle and duplicate is logged at warn level against its resource id and
 * the offending entry is dropped. Log lines carry resource ids only — never player data. The same
 * complaints are also collected as {@link ValidationIssue}s ({@link #validationIssues()}), so
 * {@code /neroquests reload-check} can show an operator what a pack got wrong without making them
 * read the server log.
 */
public final class QuestDefinitions {

    private static final String QUEST_DIRECTORY = "neroquests/quests";
    private static final String CHAPTER_DIRECTORY = "neroquests/chapters";
    private static final String EXTENSION = ".json";

    /** Stands in for "the whole load", which belongs to no single resource. */
    private static final Identifier LOAD_ISSUE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "load");

    /** The server whose datapacks produced the current maps, or null before the first load. */
    private static MinecraftServer loadedFor;

    /**
     * The resource-manager instance the current maps were read from. {@code /reload} replaces the
     * server's whole {@code ReloadableResources} (and with it this object), so an identity change
     * here means "the datapacks were reloaded" — the loader-free reload signal.
     */
    private static ResourceManager loadedFrom;

    /** Incremented on every (re)load, so derived caches can tell when they are stale. */
    private static int generation;

    private static Map<Identifier, Quest> quests = Map.of();
    private static Map<Identifier, Chapter> chapters = Map.of();

    /** What the last load complained about, in the order it complained. Replaced wholesale per load. */
    private static List<ValidationIssue> issues = List.of();

    private QuestDefinitions() {
    }

    /**
     * One thing the last load rejected, alongside the log line it produced. Collected purely so
     * {@code /neroquests reload-check} can hand an operator the same picture the server log holds
     * without making them read the log.
     *
     * <p>Resource ids and codec messages only — never player data.
     *
     * @param severity whether the whole definition was dropped or only part of it ignored
     * @param id       the quest, chapter or (for a whole-load failure) sentinel id it concerns
     * @param detail   a short, human-readable reason
     */
    public record ValidationIssue(Severity severity, Identifier id, String detail) {

        /** How badly a definition was affected. */
        public enum Severity {

            /** The definition is not loaded at all. */
            DROPPED,

            /** The definition is loaded, but part of it (an entry, a reference) was skipped. */
            IGNORED
        }
    }

    // --- lifecycle ---------------------------------------------------------

    /** The quest definitions for this server (loads + caches on first use). */
    public static synchronized Map<Identifier, Quest> questsForServer(MinecraftServer server) {
        ensureLoaded(server);
        return quests;
    }

    /** The chapter definitions for this server (loads + caches on first use). */
    public static synchronized Map<Identifier, Chapter> chaptersForServer(MinecraftServer server) {
        ensureLoaded(server);
        return chapters;
    }

    /** The validation problems this server's definitions produced (loads + caches on first use). */
    public static synchronized List<ValidationIssue> issuesForServer(MinecraftServer server) {
        ensureLoaded(server);
        return issues;
    }

    /**
     * Everything the last load dropped or ignored, empty when the pack is clean. The list is
     * immutable and replaced (never mutated) by each load, so a caller may hold on to a snapshot.
     */
    public static List<ValidationIssue> validationIssues() {
        return issues;
    }

    /**
     * Re-reads every definition from {@code server}'s current datapacks, replacing the cache.
     * Safe to call at any time.
     */
    public static synchronized void reload(MinecraftServer server) {
        loadFrom(server);
    }

    /**
     * Re-reads the definitions if — and only if — {@code server}'s datapacks have been reloaded
     * (or this is a different server) since the last load. Cheap enough to call every tick: the
     * common case is one reference comparison.
     *
     * @return {@code true} if the definitions were re-read, i.e. the caller should re-sync clients
     */
    public static synchronized boolean refreshIfReloaded(MinecraftServer server) {
        if (server == loadedFor && server.getResourceManager() == loadedFrom) {
            return false;
        }
        loadFrom(server);
        return true;
    }

    /** How many times the definitions have been loaded; changes whenever the content may have. */
    public static synchronized int generation() {
        return generation;
    }

    private static void ensureLoaded(MinecraftServer server) {
        if (server != loadedFor || server.getResourceManager() != loadedFrom) {
            loadFrom(server);
        }
    }

    private static void loadFrom(MinecraftServer server) {
        load(server);
        loadedFor = server;
        loadedFrom = server.getResourceManager();
        generation++;
    }

    // --- accessors ---------------------------------------------------------

    /** The currently loaded quests, keyed by id (empty until a server loads its datapacks). */
    public static Map<Identifier, Quest> quests() {
        return quests;
    }

    /** The currently loaded chapters, keyed by id (empty until a server loads its datapacks). */
    public static Map<Identifier, Chapter> chapters() {
        return chapters;
    }

    /** The quest with this id, if it is loaded and valid. */
    public static Optional<Quest> quest(Identifier id) {
        return Optional.ofNullable(quests.get(id));
    }

    /** The chapter with this id, if it is loaded. */
    public static Optional<Chapter> chapter(Identifier id) {
        return Optional.ofNullable(chapters.get(id));
    }

    /** Every loaded quest, in dependency order (prerequisites always precede their dependents). */
    public static Collection<Quest> allQuests() {
        return quests.values();
    }

    /** Every loaded chapter, ordered by chapter id. */
    public static Collection<Chapter> allChapters() {
        return chapters.values();
    }

    /**
     * The quests of a chapter in the order the chapter lists them (its layout order). Unknown
     * chapter ids yield an empty list; entries that failed validation are already gone.
     */
    public static List<Quest> questsOf(Identifier chapterId) {
        Chapter chapter = chapters.get(chapterId);
        if (chapter == null) {
            return List.of();
        }
        List<Quest> out = new ArrayList<>(chapter.quests().size());
        for (Chapter.Entry entry : chapter.quests()) {
            Quest quest = quests.get(entry.quest());
            if (quest != null) {
                out.add(quest);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The chapter a quest belongs to, if any chapter lists it (a quest belongs to at most one). */
    public static Optional<Identifier> chapterOf(Identifier questId) {
        for (Chapter chapter : chapters.values()) {
            if (chapter.entry(questId).isPresent()) {
                return Optional.of(chapter.id());
            }
        }
        return Optional.empty();
    }

    /**
     * Every loaded quest a holder may currently work on — visible (its {@code visible_gate} is
     * open) and unlocked (its prerequisites are complete). Pure: the caller supplies both sets,
     * so this needs no player or server plumbing.
     *
     * @param completedQuests the quest ids already complete for the holder
     * @param openGates       the Neroland Core gate ids currently open for the holder
     */
    public static List<Quest> availableQuests(Set<Identifier> completedQuests, Set<Identifier> openGates) {
        List<Quest> out = new ArrayList<>();
        for (Quest quest : quests.values()) {
            if (!completedQuests.contains(quest.id()) && quest.isAvailable(completedQuests, openGates)) {
                out.add(quest);
            }
        }
        return Collections.unmodifiableList(out);
    }

    // --- loading -----------------------------------------------------------

    private static void load(MinecraftServer server) {
        Map<Identifier, Quest> loadedQuests = Map.of();
        Map<Identifier, Chapter> loadedChapters = Map.of();
        List<ValidationIssue> collected = new ArrayList<>();
        try {
            ResourceManager resources = server.getResourceManager();
            loadedQuests = validateQuests(readQuests(resources, collected), collected);
            loadedChapters = validateChapters(readChapters(resources, collected), loadedQuests, collected);
        } catch (RuntimeException e) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Quest definition load failed; no quests are active.", e);
            loadedQuests = Map.of();
            loadedChapters = Map.of();
            collected.clear();
            // The exception's own message can carry a filesystem path, so only its type is kept for
            // the operator-facing report; the full trace stays in the log line above.
            collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, LOAD_ISSUE_ID,
                    "load failed (" + e.getClass().getSimpleName() + "); no quests are active"));
        }
        quests = loadedQuests;
        chapters = loadedChapters;
        issues = List.copyOf(collected);
        NeroQuestsCommon.LOGGER.info("[NeroQuests] Loaded {} quest(s) in {} chapter(s).",
                quests.size(), chapters.size());
    }

    private static Map<Identifier, Quest> readQuests(ResourceManager resources,
                                                     List<ValidationIssue> collected) {
        Map<Identifier, Quest> loaded = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> file : listJson(resources, QUEST_DIRECTORY).entrySet()) {
            Identifier questId = toDefinitionId(file.getKey(), QUEST_DIRECTORY);
            if (questId == null) {
                continue;
            }
            try (BufferedReader reader = file.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                List<String> errors = new ArrayList<>(1);
                Optional<Quest.Data> parsed = Quest.DATA_CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> {
                            NeroQuestsCommon.LOGGER.warn(
                                    "[NeroQuests] Bad quest definition {}: {}", questId, error);
                            errors.add(error);
                        });
                parsed.ifPresent(data -> loaded.put(questId, new Quest(questId, data)));
                recordParseErrors(collected, questId, "quest", parsed.isPresent(), errors);
            } catch (Exception e) {
                NeroQuestsCommon.LOGGER.warn("[NeroQuests] Could not read quest {}", questId, e);
                collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, questId,
                        "could not be read (" + e.getClass().getSimpleName() + ")"));
            }
        }
        return loaded;
    }

    private static Map<Identifier, Chapter> readChapters(ResourceManager resources,
                                                         List<ValidationIssue> collected) {
        Map<Identifier, Chapter> loaded = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> file : listJson(resources, CHAPTER_DIRECTORY).entrySet()) {
            Identifier chapterId = toDefinitionId(file.getKey(), CHAPTER_DIRECTORY);
            if (chapterId == null) {
                continue;
            }
            try (BufferedReader reader = file.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                List<String> errors = new ArrayList<>(1);
                Optional<Chapter.Data> parsed = Chapter.DATA_CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> {
                            NeroQuestsCommon.LOGGER.warn(
                                    "[NeroQuests] Bad chapter definition {}: {}", chapterId, error);
                            errors.add(error);
                        });
                parsed.ifPresent(data -> loaded.put(chapterId, new Chapter(chapterId, data)));
                recordParseErrors(collected, chapterId, "chapter", parsed.isPresent(), errors);
            } catch (Exception e) {
                NeroQuestsCommon.LOGGER.warn("[NeroQuests] Could not read chapter {}", chapterId, e);
                collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, chapterId,
                        "could not be read (" + e.getClass().getSimpleName() + ")"));
            }
        }
        return loaded;
    }

    /**
     * Turn a codec's complaints into report rows. A codec may complain and still produce a value
     * (a partial decode), so the severity follows whether anything survived rather than whether
     * anything was said.
     */
    private static void recordParseErrors(List<ValidationIssue> collected, Identifier id, String kind,
                                          boolean survived, List<String> errors) {
        if (errors.isEmpty()) {
            if (!survived) {
                collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, id,
                        "not a readable " + kind + " definition"));
            }
            return;
        }
        ValidationIssue.Severity severity = survived
                ? ValidationIssue.Severity.IGNORED
                : ValidationIssue.Severity.DROPPED;
        String prefix = survived ? "partly bad " + kind + " definition: " : "bad " + kind + " definition: ";
        for (String error : errors) {
            collected.add(new ValidationIssue(severity, id, prefix + error));
        }
    }

    private static Map<Identifier, Resource> listJson(ResourceManager resources, String directory) {
        return resources.listResources(directory, file -> file.getPath().endsWith(EXTENSION));
    }

    // --- validation --------------------------------------------------------

    /**
     * Drops every quest that cannot work, in three passes: unusable bodies (no objectives, an
     * unregistered objective/reward type, or an objective that could never advance at the quest's
     * scope), then dangling prerequisite references (pruned from the quest, which survives), then
     * prerequisite cycles (every quest in or behind a cycle is dropped). The surviving map is in
     * dependency order.
     */
    private static Map<Identifier, Quest> validateQuests(Map<Identifier, Quest> parsed,
                                                         List<ValidationIssue> collected) {
        Map<Identifier, Quest> usable = new LinkedHashMap<>();
        for (Quest quest : parsed.values()) {
            if (quest.objectives().isEmpty()) {
                NeroQuestsCommon.LOGGER.warn("[NeroQuests] Quest {} has no objectives; dropped.", quest.id());
                collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, quest.id(),
                        "no objectives"));
                continue;
            }
            Identifier unknownType = firstUnknownType(quest);
            if (unknownType != null) {
                NeroQuestsCommon.LOGGER.warn(
                        "[NeroQuests] Quest {} uses unregistered objective/reward type '{}'; dropped.",
                        quest.id(), unknownType);
                collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, quest.id(),
                        "unregistered objective/reward type " + unknownType));
                continue;
            }
            String scopeMismatch = firstScopeMismatch(quest);
            if (scopeMismatch != null) {
                NeroQuestsCommon.LOGGER.warn(
                        "[NeroQuests] Quest {} has an objective that can never advance at scope '{}' "
                                + "({}); dropped.",
                        quest.id(), quest.scope().name().toLowerCase(Locale.ROOT), scopeMismatch);
                collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, quest.id(),
                        "objective can never advance at this quest's scope: " + scopeMismatch));
                continue;
            }
            usable.put(quest.id(), quest);
        }

        Map<Identifier, Quest> pruned = new LinkedHashMap<>();
        for (Quest quest : usable.values()) {
            List<Identifier> kept = new ArrayList<>(quest.prerequisites().size());
            for (Identifier prerequisite : quest.prerequisites()) {
                if (usable.containsKey(prerequisite)) {
                    kept.add(prerequisite);
                } else {
                    NeroQuestsCommon.LOGGER.warn(
                            "[NeroQuests] Quest {} requires unknown quest {}; that prerequisite is ignored.",
                            quest.id(), prerequisite);
                    collected.add(new ValidationIssue(ValidationIssue.Severity.IGNORED, quest.id(),
                            "unknown prerequisite " + prerequisite));
                }
            }
            pruned.put(quest.id(), kept.size() == quest.prerequisites().size()
                    ? quest
                    : quest.withPrerequisites(kept));
        }

        // Peel quests whose prerequisites are all resolved (Kahn). Whatever cannot be peeled is
        // either in a prerequisite cycle or depends on one, and is unreachable either way.
        Map<Identifier, Quest> accepted = new LinkedHashMap<>();
        Set<Identifier> resolved = new HashSet<>();
        Map<Identifier, Quest> remaining = new LinkedHashMap<>(pruned);
        boolean progressed = true;
        while (progressed && !remaining.isEmpty()) {
            progressed = false;
            Iterator<Map.Entry<Identifier, Quest>> it = remaining.entrySet().iterator();
            while (it.hasNext()) {
                Quest quest = it.next().getValue();
                if (resolved.containsAll(quest.prerequisites())) {
                    accepted.put(quest.id(), quest);
                    resolved.add(quest.id());
                    it.remove();
                    progressed = true;
                }
            }
        }
        for (Quest quest : remaining.values()) {
            NeroQuestsCommon.LOGGER.warn(
                    "[NeroQuests] Quest {} is in (or behind) a prerequisite cycle and can never unlock; dropped.",
                    quest.id());
            collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, quest.id(),
                    "in (or behind) a prerequisite cycle"));
        }
        return Collections.unmodifiableMap(accepted);
    }

    /**
     * Drops chapter entries that point at a quest which did not survive validation, and entries
     * claiming a quest another chapter already claimed (chapters are visited in id order, so the
     * first chapter alphabetically wins and the result is deterministic). The chapter itself is
     * always kept.
     */
    private static Map<Identifier, Chapter> validateChapters(Map<Identifier, Chapter> parsed,
                                                            Map<Identifier, Quest> validQuests,
                                                            List<ValidationIssue> collected) {
        List<Identifier> ordered = new ArrayList<>(parsed.keySet());
        ordered.sort(Comparator.comparing(Identifier::toString));

        Map<Identifier, Chapter> accepted = new LinkedHashMap<>();
        Set<Identifier> claimed = new HashSet<>();
        for (Identifier chapterId : ordered) {
            Chapter chapter = parsed.get(chapterId);
            List<Chapter.Entry> kept = new ArrayList<>(chapter.quests().size());
            for (Chapter.Entry entry : chapter.quests()) {
                if (!validQuests.containsKey(entry.quest())) {
                    NeroQuestsCommon.LOGGER.warn(
                            "[NeroQuests] Chapter {} lists unknown quest {}; that entry is ignored.",
                            chapterId, entry.quest());
                    collected.add(new ValidationIssue(ValidationIssue.Severity.IGNORED, chapterId,
                            "lists unknown quest " + entry.quest()));
                } else if (!claimed.add(entry.quest())) {
                    NeroQuestsCommon.LOGGER.warn(
                            "[NeroQuests] Quest {} is listed more than once (chapter {} keeps the first "
                                    + "listing only); the duplicate entry is ignored.",
                            entry.quest(), chapterId);
                    collected.add(new ValidationIssue(ValidationIssue.Severity.IGNORED, chapterId,
                            "duplicate listing of quest " + entry.quest() + " (an earlier chapter "
                                    + "already claims it)"));
                } else {
                    kept.add(entry);
                }
            }
            accepted.put(chapterId, kept.size() == chapter.quests().size()
                    ? chapter
                    : chapter.withQuests(kept));
        }
        return Collections.unmodifiableMap(accepted);
    }

    /**
     * The reason the first objective that is incompatible with this quest's {@code scope} could
     * never advance, or null when every objective can. This catches the one authoring mistake the
     * codec cannot: a {@code custom_event} objective left on the default {@code audience: world},
     * which writes shared progress, inside a {@code scope: player} quest that has none. Dropping the
     * quest names the mistake in {@code /neroquests reload-check} instead of leaving a player
     * staring at a counter that never ticks.
     */
    private static String firstScopeMismatch(Quest quest) {
        for (ObjectiveSpec objective : quest.objectives()) {
            Optional<String> reason = objective.unusableInScope(quest.scope());
            if (reason.isPresent()) {
                return reason.get();
            }
        }
        return null;
    }

    /** The first unregistered objective/reward type id in this quest, or null if all are known. */
    private static Identifier firstUnknownType(Quest quest) {
        for (ObjectiveSpec objective : quest.objectives()) {
            if (objective instanceof UnknownObjective unknown) {
                return unknown.typeId();
            }
        }
        for (RewardSpec reward : quest.rewards()) {
            if (reward instanceof UnknownReward unknown) {
                return unknown.typeId();
            }
        }
        return null;
    }

    /** {@code <ns>:neroquests/quests/foo/bar.json} -> {@code <ns>:foo/bar}. */
    private static Identifier toDefinitionId(Identifier file, String directory) {
        String path = file.getPath();
        if (!path.startsWith(directory + "/") || !path.endsWith(EXTENSION)) {
            return null;
        }
        String trimmed = path.substring(directory.length() + 1, path.length() - EXTENSION.length());
        return Identifier.fromNamespaceAndPath(file.getNamespace(), trimmed);
    }
}
