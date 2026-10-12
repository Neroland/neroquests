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
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.custom.CustomLimits;
import za.co.neroland.neroquests.custom.CustomRules;
import za.co.neroland.neroquests.quest.objective.ContentIds;
import za.co.neroland.neroquests.quest.objective.UnknownObjective;
import za.co.neroland.neroquests.quest.reward.GateReward;
import za.co.neroland.neroquests.quest.reward.ModHookReward;
import za.co.neroland.neroquests.quest.reward.ReputationReward;
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
 *
 * <h2>Sources and packs (B1–B3)</h2>
 *
 * <p>Every definition has a {@link QuestSource} from its namespace. Before validation, files in the
 * reserved {@code custom} namespace are dropped (only the editor store may use it), and each pack
 * namespace's presence is decided ({@link Presence}): a pack whose mod is not installed loads nothing,
 * unless one of its chapters sets {@code show_when_absent} — then that chapter and the quests it lists
 * load with {@code modAbsent} set (badge, never completable). Server datapacks (a namespace that is not
 * a mod id) always load. Pack quests then follow {@link PackRules}.
 *
 * <h2>Custom content (D1)</h2>
 *
 * <p>Editor-made chapters and quests (namespace {@code custom}) come from the world's custom-quest store
 * through {@link #setCustomSource}, are merged in <em>after</em> the datapack filters (so the reserved
 * namespace stays closed to datapacks) and are validated by the same pipeline plus {@link CustomRules}.
 * A spine or pack quest may never depend on a custom one. An editor mutation re-runs the merge and the
 * validation without re-reading any pack file ({@link #customChanged}); the generation advances, so the
 * reconciler and the client sync treat it exactly like a {@code /reload}.
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

    /** Every decoded quest/chapter of the last load, before validation — the input of the typo pass. */
    private static Map<Identifier, Quest> parsedQuests = Map.of();
    private static Map<Identifier, Chapter> parsedChapters = Map.of();

    /** The issues the last load's file reading produced (before validation added its own). */
    private static List<ValidationIssue> parseIssues = List.of();

    /**
     * Whether the last load still owes its typo pass (AUD-02), which needs registries, tags and levels
     * and therefore runs on the next server tick ({@link #runContentChecks}). The engine evaluates
     * nothing while this is set.
     */
    private static boolean contentChecksPending;

    /** Quest id → objective fingerprint of every loaded quest (AUD-17). Replaced with {@link #quests}. */
    private static Map<Identifier, Integer> fingerprints = Map.of();

    /** Pack namespace → quests not loaded because the pack's mod is absent (B3). Replaced per load. */
    private static Map<String, Integer> absentPacks = Map.of();

    /** The datapack candidates of the last load (after the namespace and presence filters), without custom content. */
    private static Map<Identifier, Quest> packQuests = Map.of();
    private static Map<Identifier, Chapter> packChapters = Map.of();

    /** The issues reading the datapacks produced (before validation), the base every merge starts from. */
    private static List<ValidationIssue> packIssues = List.of();

    /** Where custom (editor) content comes from: the world's store once the custom module is initialised. */
    private static volatile Function<MinecraftServer, Extra> customSource = server -> Extra.EMPTY;

    /**
     * Definitions merged in after the datapack filters: the editor's custom chapters and quests (D1).
     *
     * @param quests   custom quests by id
     * @param chapters custom chapters by id
     */
    public record Extra(Map<Identifier, Quest> quests, Map<Identifier, Chapter> chapters) {

        /** No custom content. */
        public static final Extra EMPTY = new Extra(Map.of(), Map.of());
    }

    /** Install the source of custom content (called once by the custom module's init). */
    public static void setCustomSource(Function<MinecraftServer, Extra> source) {
        customSource = source == null ? server -> Extra.EMPTY : source;
    }

    private QuestDefinitions() {
    }

    /**
     * How a load decides whether a pack namespace's mod is here (B3). Pure, so tests and
     * {@code QuestPackValidator} can supply their own.
     */
    public interface Presence {

        /** Whether a mod with this id is loaded. */
        boolean modLoaded(String namespace);

        /** Whether this namespace is a mod id at all (loaded, or a known Neroland mod) — else a server datapack. */
        boolean isModId(String namespace);

        /** Whether a pack in {@code namespace} is present: its mod is loaded, or it is not a mod id. */
        default boolean packPresent(String namespace) {
            return modLoaded(namespace) || !isModId(namespace);
        }

        /** The running game's answer (Core's platform seam, through {@link ContentIds}). */
        Presence RUNTIME = new Presence() {
            @Override
            public boolean modLoaded(String namespace) {
                return ContentIds.modLoaded(namespace);
            }

            @Override
            public boolean isModId(String namespace) {
                return ContentIds.isModId(namespace);
            }
        };
    }

    /**
     * The outcome of {@link #validate}: what loads, what was rejected and which pack namespaces were
     * skipped because their mod is absent.
     *
     * @param quests      the surviving quests, in dependency order
     * @param chapters    the surviving chapters
     * @param issues      every rejection, in order
     * @param absentPacks pack namespace → number of its quests not loaded because its mod is absent
     */
    public record Validated(Map<Identifier, Quest> quests, Map<Identifier, Chapter> chapters,
                            List<ValidationIssue> issues, Map<String, Integer> absentPacks) {
    }

    /**
     * The whole validation pipeline on already-decoded definitions, without a server (pure): the reserved
     * namespace, pack presence, then every quest and chapter rule. The typo pass (which needs registries)
     * is not part of it.
     */
    public static Validated validate(Map<Identifier, Quest> decodedQuests, Map<Identifier, Chapter> decodedChapters,
                                     Presence presence) {
        return validate(decodedQuests, decodedChapters, presence, Extra.EMPTY);
    }

    /**
     * {@link #validate(Map, Map, Presence)} with custom (editor) content merged in after the datapack filters,
     * exactly as a live load does (pure).
     */
    public static Validated validate(Map<Identifier, Quest> decodedQuests, Map<Identifier, Chapter> decodedChapters,
                                     Presence presence, Extra custom) {
        List<ValidationIssue> collected = new ArrayList<>();
        Candidates candidates = candidates(decodedQuests, decodedChapters, presence, collected);
        Map<Identifier, Quest> quests = merged(candidates.quests(), custom.quests());
        Map<Identifier, Chapter> chapters = merged(candidates.chapters(), custom.chapters());
        Map<Identifier, Quest> valid = validateQuests(quests, chapters, collected);
        Map<Identifier, Chapter> validChapters = validateChapters(chapters, valid, collected);
        return new Validated(valid, validChapters, List.copyOf(collected), candidates.absentPacks());
    }

    /**
     * The datapack definitions plus the editor's (J1): an editor entry never replaces a datapack id and is
     * taken only when it is a {@code custom:} id, so nothing the editor store holds can add, shadow or alter a
     * spine or pack quest or chapter. Pure.
     */
    static <T> Map<Identifier, T> merged(Map<Identifier, T> base, Map<Identifier, T> extra) {
        if (extra.isEmpty()) {
            return base;
        }
        Map<Identifier, T> out = new LinkedHashMap<>(base);
        extra.forEach((id, value) -> {
            if (QuestSource.of(id) == QuestSource.CUSTOM) {
                out.putIfAbsent(id, value);
            }
        });
        return out;
    }

    /**
     * What the definitions would be with {@code custom} as the editor content, without changing anything
     * (the editor's preview before a mutation is applied, D2). Uses the datapack content of the current load.
     */
    public static synchronized Validated preview(MinecraftServer server, Extra custom) {
        ensureLoaded(server);
        List<ValidationIssue> collected = new ArrayList<>(packIssues);
        Map<Identifier, Quest> quests = merged(packQuests, custom.quests());
        Map<Identifier, Chapter> chapters = merged(packChapters, custom.chapters());
        quiet = true;
        try {
            Map<Identifier, Quest> valid = validateQuests(quests, chapters, collected);
            Map<Identifier, Chapter> validChapters = validateChapters(chapters, valid, collected);
            return new Validated(valid, validChapters, List.copyOf(collected), absentPacks);
        } finally {
            quiet = false;
        }
    }

    /**
     * The custom store changed (D2): merge its current content with the datapack content of the last load,
     * re-validate, run the typo pass straight away (registries are live) and advance the generation —
     * without re-reading a single pack file. The caller re-syncs clients.
     */
    public static synchronized void customChanged(MinecraftServer server) {
        if (server != loadedFor || server.getResourceManager() != loadedFrom) {
            loadFrom(server); // a full load already merges the store
            runContentChecks(server);
            return;
        }
        // The datapack drops were logged by the real load; an editor change does not repeat them.
        quiet = true;
        try {
            merge(server);
        } finally {
            quiet = false;
        }
        generation++;
        runContentChecks(server);
    }

    /** Merge the datapack candidates of the last load with the current custom content and validate the lot. */
    private static void merge(MinecraftServer server) {
        Extra custom;
        try {
            custom = customSource.apply(server);
        } catch (RuntimeException e) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Custom quests could not be read; loading without them.", e);
            custom = Extra.EMPTY;
        }
        List<ValidationIssue> collected = new ArrayList<>(packIssues);
        Map<Identifier, Quest> readQuests = merged(packQuests, custom.quests());
        Map<Identifier, Chapter> readChapters = merged(packChapters, custom.chapters());
        Map<Identifier, Quest> loadedQuests = validateQuests(readQuests, readChapters, collected);
        quests = loadedQuests;
        chapters = validateChapters(readChapters, loadedQuests, collected);
        issues = List.copyOf(collected);
        fingerprints = fingerprintsOf(loadedQuests);
        parsedQuests = readQuests;
        parsedChapters = readChapters;
        parseIssues = packIssues;
        contentChecksPending = !loadedQuests.isEmpty();
    }

    /** Pack namespaces skipped this load because their mod is absent, with how many quests each held (B3). */
    public static synchronized Map<String, Integer> absentPacksForServer(MinecraftServer server) {
        ensureLoaded(server);
        return absentPacks;
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
        // An explicit reload runs on a live server, so the typo pass can run right away.
        runContentChecks(server);
    }

    /** The objective fingerprint of a loaded quest (AUD-17), or {@code 0} if it is not loaded. */
    public static synchronized int fingerprint(Identifier quest) {
        Integer value = fingerprints.get(quest);
        return value == null ? 0 : value.intValue();
    }

    /** Every loaded quest's objective fingerprint for this server (loads + caches on first use). */
    public static synchronized Map<Identifier, Integer> fingerprintsForServer(MinecraftServer server) {
        ensureLoaded(server);
        return fingerprints;
    }

    private static Map<Identifier, Integer> fingerprintsOf(Map<Identifier, Quest> loaded) {
        Map<Identifier, Integer> out = new LinkedHashMap<>();
        loaded.forEach((id, quest) -> out.put(id, Integer.valueOf(Quest.objectivesFingerprint(quest.objectives()))));
        return Collections.unmodifiableMap(out);
    }

    /** Whether freshly loaded definitions still await their typo pass (the engine waits for it). */
    public static synchronized boolean contentChecksPending() {
        return contentChecksPending;
    }

    /**
     * The typo pass (AUD-02), run once per load from the server tick (or straight after an explicit
     * {@link #reload}), when registries, tags and levels are all bound. A quest with an objective whose
     * target does not resolve although its owning mod is loaded is DROPPED with the reason in
     * {@link #validationIssues()}, and validation re-runs so its dependents and chapter entries are
     * handled exactly as for any other dropped quest.
     *
     * @return {@code true} if quests were dropped (the definitions changed; re-sync clients)
     */
    public static synchronized boolean runContentChecks(MinecraftServer server) {
        if (!contentChecksPending || server != loadedFor) {
            return false;
        }
        contentChecksPending = false;
        Set<Identifier> defined = parsedQuests.keySet();
        Map<Identifier, String> typos = new LinkedHashMap<>();
        for (Quest quest : parsedQuests.values()) {
            List<ObjectiveSpec> objectives = quest.objectives();
            for (int index = 0; index < objectives.size(); index++) {
                Optional<String> reason;
                try {
                    reason = objectives.get(index).unknownTarget(server, defined);
                } catch (RuntimeException e) {
                    NeroQuestsCommon.LOGGER.warn("[NeroQuests] Could not check quest {} objective #{} for typos.",
                            quest.id(), Integer.valueOf(index), e);
                    continue;
                }
                if (reason.isPresent()) {
                    typos.put(quest.id(), "objective #" + index + ": " + reason.get());
                    break;
                }
            }
        }
        if (typos.isEmpty()) {
            return false;
        }
        List<ValidationIssue> collected = new ArrayList<>(parseIssues);
        Map<Identifier, Quest> kept = new LinkedHashMap<>(parsedQuests);
        typos.forEach((id, reason) -> {
            kept.remove(id);
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Quest {} names content that does not exist although "
                    + "its mod is loaded (a typo?): {}; dropped.", id, reason);
            collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, id, "typo: " + reason));
        });
        quests = validateQuests(kept, parsedChapters, collected);
        chapters = validateChapters(parsedChapters, quests, collected);
        issues = List.copyOf(collected);
        fingerprints = fingerprintsOf(quests);
        generation++;
        NeroQuestsCommon.LOGGER.info("[NeroQuests] {} quest(s) remain after the typo check.", quests.size());
        return true;
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

    /**
     * The server stopped (AUD-31): drop the reference to it, its resource manager and everything read
     * from them, so a stopped integrated server is not kept reachable until the next world loads. The
     * generation still advances, so every derived cache sees the change.
     */
    public static synchronized void serverStopped() {
        loadedFor = null;
        loadedFrom = null;
        quests = Map.of();
        chapters = Map.of();
        issues = List.of();
        parsedQuests = Map.of();
        parsedChapters = Map.of();
        parseIssues = List.of();
        contentChecksPending = false;
        fingerprints = Map.of();
        absentPacks = Map.of();
        packQuests = Map.of();
        packChapters = Map.of();
        packIssues = List.of();
        generation++;
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

    // --- loading -----------------------------------------------------------

    private static void load(MinecraftServer server) {
        List<ValidationIssue> collected = new ArrayList<>();
        Map<Identifier, Quest> readQuests = Map.of();
        Map<Identifier, Chapter> readChapters = Map.of();
        Map<String, Integer> skippedPacks = Map.of();
        try {
            ResourceManager resources = server.getResourceManager();
            Candidates candidates = candidates(readQuests(resources, collected), readChapters(resources, collected),
                    Presence.RUNTIME, collected);
            readQuests = candidates.quests();
            readChapters = candidates.chapters();
            skippedPacks = candidates.absentPacks();
        } catch (RuntimeException e) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Quest definition load failed; no quests are active.", e);
            readQuests = Map.of();
            readChapters = Map.of();
            skippedPacks = Map.of();
            collected.clear();
            // The exception's own message can carry a filesystem path, so only its type is kept for
            // the operator-facing report; the full trace stays in the log line above.
            collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, LOAD_ISSUE_ID,
                    "load failed (" + e.getClass().getSimpleName() + "); no quests are active"));
        }
        packQuests = readQuests;
        packChapters = readChapters;
        packIssues = List.copyOf(collected);
        absentPacks = skippedPacks;
        // D1: the editor's custom content joins here, after the datapack filters.
        merge(server);
        NeroQuestsCommon.LOGGER.info("[NeroQuests] Loaded {} quest(s) in {} chapter(s).",
                quests.size(), chapters.size());
    }

    private static Map<Identifier, Quest> readQuests(ResourceManager resources,
                                                     List<ValidationIssue> collected) {
        Map<Identifier, Quest> loaded = new LinkedHashMap<>();
        for (Map.Entry<Identifier, List<Resource>> file : listJson(resources, QUEST_DIRECTORY).entrySet()) {
            Identifier questId = toDefinitionId(file.getKey(), QUEST_DIRECTORY);
            if (questId == null) {
                continue;
            }
            Resource resource = chooseResource(questId, "quest", file.getValue(), collected);
            if (resource == null) {
                continue;
            }
            boolean datapack = fromDatapack(resource);
            try (BufferedReader reader = resource.openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                List<String> errors = new ArrayList<>(1);
                Optional<Quest.Data> parsed = decodeStrict(Quest.DATA_CODEC.parse(JsonOps.INSTANCE, json),
                        error -> {
                            NeroQuestsCommon.LOGGER.warn(
                                    "[NeroQuests] Bad quest definition {}: {}", questId, error);
                            errors.add(error);
                        });
                parsed.ifPresent(data -> loaded.put(questId,
                        datapack ? withoutModHooks(new Quest(questId, data), collected) : new Quest(questId, data)));
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
        for (Map.Entry<Identifier, List<Resource>> file : listJson(resources, CHAPTER_DIRECTORY).entrySet()) {
            Identifier chapterId = toDefinitionId(file.getKey(), CHAPTER_DIRECTORY);
            if (chapterId == null) {
                continue;
            }
            Resource resource = chooseResource(chapterId, "chapter", file.getValue(), collected);
            if (resource == null) {
                continue;
            }
            try (BufferedReader reader = resource.openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                List<String> errors = new ArrayList<>(1);
                Optional<Chapter.Data> parsed = decodeStrict(Chapter.DATA_CODEC.parse(JsonOps.INSTANCE, json),
                        error -> {
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
     * Decode all-or-nothing (AUD-04): any codec error drops the whole definition. A partial result —
     * a list with its bad elements silently removed — is never used, because for a quest that means
     * an easier quest, vanished rewards or shifted objective indices (and so the wrong stored
     * counters).
     *
     * @param result  the codec's result
     * @param onError receives the error message when there is one
     * @return the decoded value, or empty on any error
     */
    public static <T> Optional<T> decodeStrict(DataResult<T> result, Consumer<String> onError) {
        Optional<DataResult.Error<T>> error = result.error();
        if (error.isPresent()) {
            onError.accept(error.get().message());
            return Optional.empty();
        }
        return result.result();
    }

    /**
     * Turn a codec's complaints into report rows. Since {@link #decodeStrict} never keeps a partial
     * decode, a complaint always means the definition was dropped.
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

    private static Map<Identifier, List<Resource>> listJson(ResourceManager resources, String directory) {
        return resources.listResourceStacks(directory, file -> file.getPath().endsWith(EXTENSION));
    }

    // --- J2 (RT-07): where a definition file came from ------------------------------------------------

    /**
     * Whether a definition file came from a <b>datapack</b> (a world or server datapack folder) rather than from a
     * mod's own resources. Read from the vanilla pack metadata of the file's source pack, so it works the same on
     * every loader: world datapacks carry {@code PackSource.WORLD} and an id starting with {@code file/}.
     */
    static boolean fromDatapack(Resource resource) {
        boolean worldOrServer = false;
        String packId = "";
        try {
            packId = resource.sourcePackId();
            PackSource source = resource.source().location().source();
            worldOrServer = source == PackSource.WORLD || source == PackSource.SERVER;
        } catch (RuntimeException e) {
            // A pack without location metadata is treated by its id alone.
        }
        return datapackOrigin(worldOrServer, packId);
    }

    /** Pure form of {@link #fromDatapack}: the pack's source is WORLD/SERVER, or its id names a datapack folder. */
    static boolean datapackOrigin(boolean worldOrServerSource, String packId) {
        return worldOrServerSource || (packId != null && packId.startsWith("file/"));
    }

    /**
     * Which file of a stack (lowest to highest pack priority) to read, given which of them come from a datapack.
     * Any namespace but {@code neroquests}: the highest-priority file, as before. NeroQuests' own namespace: the
     * highest-priority file from <em>mod</em> resources — a datapack can neither replace a NeroQuests quest nor add
     * one in that namespace (which would let it write progression gates and reputation). -1 when none qualifies.
     * Pure.
     */
    static int pickResource(List<Boolean> datapack, boolean spineNamespace) {
        if (datapack.isEmpty()) {
            return -1;
        }
        if (!spineNamespace) {
            return datapack.size() - 1;
        }
        for (int index = datapack.size() - 1; index >= 0; index--) {
            if (!datapack.get(index).booleanValue()) {
                return index;
            }
        }
        return -1;
    }

    /** {@link #pickResource} for one file, reporting a datapack file that was passed over (or the only one). */
    private static Resource chooseResource(Identifier id, String kind, List<Resource> stack,
                                           List<ValidationIssue> collected) {
        List<Boolean> origin = new ArrayList<>(stack.size());
        for (Resource resource : stack) {
            origin.add(Boolean.valueOf(fromDatapack(resource)));
        }
        boolean spine = QuestSource.of(id) == QuestSource.SPINE;
        int chosen = pickResource(origin, spine);
        if (spine && chosen < 0) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] A datapack adds {} {} in the '{}' namespace; dropped (datapacks "
                    + "ship quests in their own namespace).", kind, id, NeroQuestsCommon.MOD_ID);
            collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, id,
                    "a datapack may not add " + kind + "s in the '" + NeroQuestsCommon.MOD_ID
                            + "' namespace; use your own namespace"));
        } else if (spine && chosen < stack.size() - 1) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] A datapack tries to replace NeroQuests {} {}; the datapack copy "
                    + "is ignored.", kind, id);
            collected.add(new ValidationIssue(ValidationIssue.Severity.IGNORED, id,
                    "a datapack copy of this NeroQuests " + kind + " was ignored (NeroQuests' own definitions cannot "
                            + "be replaced by a datapack)"));
        }
        return chosen < 0 ? null : stack.get(chosen);
    }

    /**
     * A datapack quest's {@code mod_hook} rewards are dropped (J2, RT-07): a hook gate fires a sibling mod's own
     * effect, so only that mod's own resources may name it. Reported; the rest of the quest loads.
     */
    private static Quest withoutModHooks(Quest quest, List<ValidationIssue> collected) {
        List<RewardSpec> kept = new ArrayList<>(quest.rewards().size());
        for (RewardSpec reward : quest.rewards()) {
            if (reward instanceof ModHookReward hook) {
                NeroQuestsCommon.LOGGER.warn("[NeroQuests] Quest {} comes from a datapack; its mod_hook reward {} is "
                        + "dropped.", quest.id(), hook.gate());
                collected.add(new ValidationIssue(ValidationIssue.Severity.IGNORED, quest.id(),
                        "mod_hook reward " + hook.gate() + " dropped: only the mod that owns a hook gate may name it, "
                                + "not a datapack"));
            } else {
                kept.add(reward);
            }
        }
        if (kept.size() == quest.rewards().size()) {
            return quest;
        }
        return new Quest(quest.id(), quest.title(), quest.description(), quest.icon(), quest.prerequisites(),
                quest.objectives(), List.copyOf(kept), quest.scope(), quest.visibleGate(), quest.modAbsent());
    }

    // --- validation --------------------------------------------------------

    /** The definitions that go on to validation, after the namespace and presence filters. */
    private record Candidates(Map<Identifier, Quest> quests, Map<Identifier, Chapter> chapters,
                              Map<String, Integer> absentPacks) {
    }

    /**
     * B1 + B3, before any per-quest rule: drop datapack files in the reserved {@code custom} namespace, and
     * decide each pack namespace's presence. An absent pack keeps only its {@code show_when_absent}
     * chapters and the own-namespace quests they list, all marked {@code modAbsent}; everything else of
     * it is skipped (counted, not reported as an error — a smaller install is normal).
     */
    private static Candidates candidates(Map<Identifier, Quest> decodedQuests, Map<Identifier, Chapter> decodedChapters,
                                         Presence presence, List<ValidationIssue> collected) {
        Map<Identifier, Chapter> chapterCandidates = new LinkedHashMap<>();
        for (Chapter chapter : decodedChapters.values()) {
            if (PackRules.reservedNamespace(chapter.id())) {
                reserved(chapter.id(), "chapter", collected);
            } else {
                chapterCandidates.put(chapter.id(), chapter);
            }
        }
        Map<Identifier, Quest> questCandidates = new LinkedHashMap<>();
        for (Quest quest : decodedQuests.values()) {
            if (PackRules.reservedNamespace(quest.id())) {
                reserved(quest.id(), "quest", collected);
            } else {
                questCandidates.put(quest.id(), quest);
            }
        }

        Map<String, Boolean> present = new LinkedHashMap<>();
        Consumer<Identifier> decide = id -> {
            if (QuestSource.of(id) == QuestSource.PACK) {
                present.computeIfAbsent(id.getNamespace(), presence::packPresent);
            }
        };
        chapterCandidates.keySet().forEach(decide);
        questCandidates.keySet().forEach(decide);
        if (!present.containsValue(Boolean.FALSE)) {
            return new Candidates(questCandidates, chapterCandidates, Map.of());
        }

        Map<Identifier, Chapter> chapters = new LinkedHashMap<>();
        Set<Identifier> shownQuests = new HashSet<>();
        for (Chapter chapter : chapterCandidates.values()) {
            String namespace = chapter.id().getNamespace();
            if (!Boolean.FALSE.equals(present.get(namespace))) {
                chapters.put(chapter.id(), chapter);
            } else if (chapter.showWhenAbsent()) {
                chapters.put(chapter.id(), chapter.withModAbsent(true));
                for (Chapter.Entry entry : chapter.quests()) {
                    if (entry.quest().getNamespace().equals(namespace)) {
                        shownQuests.add(entry.quest());
                    }
                }
            }
        }
        Map<Identifier, Quest> quests = new LinkedHashMap<>();
        Map<String, Integer> skipped = new LinkedHashMap<>();
        for (Quest quest : questCandidates.values()) {
            String namespace = quest.id().getNamespace();
            if (!Boolean.FALSE.equals(present.get(namespace))) {
                quests.put(quest.id(), quest);
            } else if (shownQuests.contains(quest.id())) {
                quests.put(quest.id(), quest.withModAbsent(true));
            } else {
                skipped.merge(namespace, Integer.valueOf(1), Integer::sum);
            }
        }
        present.forEach((namespace, here) -> {
            if (!here.booleanValue()) {
                skipped.putIfAbsent(namespace, Integer.valueOf(0));
                NeroQuestsCommon.LOGGER.info("[NeroQuests] Quest pack '{}' is not loaded: that mod is not installed "
                        + "({} quest(s) skipped).", namespace, skipped.get(namespace));
            }
        });
        return new Candidates(quests, chapters, Collections.unmodifiableMap(skipped));
    }

    private static void reserved(Identifier id, String kind, List<ValidationIssue> collected) {
        NeroQuestsCommon.LOGGER.warn("[NeroQuests] {} {} uses the reserved '{}' namespace; dropped.", kind, id,
                QuestSource.CUSTOM_NAMESPACE);
        collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, id,
                "the '" + QuestSource.CUSTOM_NAMESPACE + "' namespace is reserved for quests made in the in-game "
                        + "editor; a datapack may not use it"));
    }

    /** Set while a preview validates (D2): its drops are reported to the editor, not logged again. */
    private static boolean quiet;

    /** A validation warning — resource ids and plain words only — unless a preview is running. */
    private static void warn(String format, Object... args) {
        if (!quiet) {
            NeroQuestsCommon.LOGGER.warn(format, args);
        }
    }

    /**
     * Drops every quest that cannot work, in three passes: unusable bodies (no objectives, an
     * unregistered objective/reward type, or an objective that could never advance at the quest's
     * scope), then dangling prerequisite references (pruned from the quest, which survives), then
     * prerequisite cycles (every quest in or behind a cycle is dropped). The surviving map is in
     * dependency order.
     */
    private static Map<Identifier, Quest> validateQuests(Map<Identifier, Quest> parsed,
                                                         Map<Identifier, Chapter> chapters,
                                                         List<ValidationIssue> collected) {
        Map<Identifier, Quest> usable = new LinkedHashMap<>();
        for (Quest quest : parsed.values()) {
            if (quest.objectives().isEmpty()) {
                warn("[NeroQuests] Quest {} has no objectives; dropped.", quest.id());
                collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, quest.id(),
                        "no objectives"));
                continue;
            }
            Identifier unknownType = firstUnknownType(quest);
            if (unknownType != null) {
                warn(
                        "[NeroQuests] Quest {} uses unregistered objective/reward type '{}'; dropped.",
                        quest.id(), unknownType);
                collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, quest.id(),
                        "unregistered objective/reward type " + unknownType));
                continue;
            }
            Identifier restricted = restrictedRewardOutsideNamespace(quest);
            if (restricted != null) {
                warn(
                        "[NeroQuests] Quest {} uses reward type '{}', which only quests in the '{}' namespace may "
                                + "use; dropped.", quest.id(), restricted, NeroQuestsCommon.MOD_ID);
                collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, quest.id(),
                        "reward type " + restricted + " is only allowed in quests of the '" + NeroQuestsCommon.MOD_ID
                                + "' namespace (it writes ecosystem progression or faction standing)"));
                continue;
            }
            Optional<String> customRule = quest.source() == QuestSource.CUSTOM
                    ? CustomRules.violation(quest, CustomLimits.current())
                    : CustomRules.referencesCustom(quest);
            if (customRule.isPresent()) {
                // Ids and plain words only: a custom quest's title or description never reaches the log.
                warn("[NeroQuests] Quest {} breaks a {} rule ({}); dropped.", quest.id(),
                        quest.source().wireName(), customRule.get());
                collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, quest.id(), customRule.get()));
                continue;
            }
            Optional<String> packRule = PackRules.violation(quest, chapters);
            if (packRule.isPresent()) {
                warn("[NeroQuests] Quest {} breaks a {} rule ({}); dropped.", quest.id(),
                        quest.source().wireName(), packRule.get());
                collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, quest.id(), packRule.get()));
                continue;
            }
            String scopeMismatch = firstScopeMismatch(quest);
            if (scopeMismatch != null) {
                warn(
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
                    warn(
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
            warn(
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
            Optional<String> chapterRule = PackRules.chapterViolation(chapter);
            if (chapterRule.isPresent()) {
                warn("[NeroQuests] Chapter {} breaks a pack rule ({}); dropped.", chapterId,
                        chapterRule.get());
                collected.add(new ValidationIssue(ValidationIssue.Severity.DROPPED, chapterId, chapterRule.get()));
                continue;
            }
            List<Chapter.Entry> kept = new ArrayList<>(chapter.quests().size());
            for (Chapter.Entry entry : chapter.quests()) {
                if (!PackRules.mayList(chapter, entry.quest())) {
                    warn(
                            "[NeroQuests] Pack chapter {} lists quest {} of another namespace; that entry is ignored.",
                            chapterId, entry.quest());
                    collected.add(new ValidationIssue(ValidationIssue.Severity.IGNORED, chapterId,
                            chapter.source() == QuestSource.PACK
                                    ? "a pack chapter may list only its own namespace's quests, not " + entry.quest()
                                    : "custom (editor) quests are listed only by custom chapters, not " + entry.quest()));
                } else if (!validQuests.containsKey(entry.quest())) {
                    warn(
                            "[NeroQuests] Chapter {} lists unknown quest {}; that entry is ignored.",
                            chapterId, entry.quest());
                    collected.add(new ValidationIssue(ValidationIssue.Severity.IGNORED, chapterId,
                            "lists unknown quest " + entry.quest()));
                } else if (!claimed.add(entry.quest())) {
                    warn(
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

    /**
     * AUD-23: {@code neroquests:gate} and {@code neroquests:reputation} rewards write ecosystem-wide state
     * (progression gates, faction standing), so only quests of the {@code neroquests} namespace may carry
     * them. Returns the first such reward type in a quest of any other namespace, or null when the quest
     * is fine.
     */
    public static Identifier restrictedRewardOutsideNamespace(Quest quest) {
        if (NeroQuestsCommon.MOD_ID.equals(quest.id().getNamespace())) {
            return null;
        }
        for (RewardSpec reward : quest.rewards()) {
            Identifier type = reward.typeId();
            if (GateReward.TYPE_ID.equals(type) || ReputationReward.TYPE_ID.equals(type)) {
                return type;
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
