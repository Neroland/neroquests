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
 * <p>Lifecycle mirrors Neroland Core's {@code GateDefinitions}: definitions are read from the
 * running server's {@link ResourceManager} lazily on first use, cached per server instance, and
 * re-read whenever the server object changes (a new world/singleplayer session). Because the
 * cached value is keyed on the server, a datapack {@code /reload} does not by itself invalidate
 * it — call {@link #reload(MinecraftServer)} from a reload hook once a reload-listener seam
 * lands, which re-reads in place and leaves progress untouched (progress for a quest that has
 * disappeared is simply retained but inert).
 *
 * <p><b>Nothing here ever crashes on bad content.</b> Every malformed file, unknown type,
 * dangling reference, cycle and duplicate is logged at warn level against its resource id and
 * the offending entry is dropped. Log lines carry resource ids only — never player data.
 */
public final class QuestDefinitions {

    private static final String QUEST_DIRECTORY = "neroquests/quests";
    private static final String CHAPTER_DIRECTORY = "neroquests/chapters";
    private static final String EXTENSION = ".json";

    /** The server whose datapacks produced the current maps, or null before the first load. */
    private static MinecraftServer loadedFor;
    private static Map<Identifier, Quest> quests = Map.of();
    private static Map<Identifier, Chapter> chapters = Map.of();

    private QuestDefinitions() {
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

    /**
     * Re-reads every definition from {@code server}'s current datapacks, replacing the cache.
     * Safe to call at any time; intended for a {@code /reload} hook.
     */
    public static synchronized void reload(MinecraftServer server) {
        load(server);
        loadedFor = server;
    }

    private static void ensureLoaded(MinecraftServer server) {
        if (server != loadedFor) {
            load(server);
            loadedFor = server;
        }
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
        try {
            ResourceManager resources = server.getResourceManager();
            loadedQuests = validateQuests(readQuests(resources));
            loadedChapters = validateChapters(readChapters(resources), loadedQuests);
        } catch (RuntimeException e) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Quest definition load failed; no quests are active.", e);
            loadedQuests = Map.of();
            loadedChapters = Map.of();
        }
        quests = loadedQuests;
        chapters = loadedChapters;
        NeroQuestsCommon.LOGGER.info("[NeroQuests] Loaded {} quest(s) in {} chapter(s).",
                quests.size(), chapters.size());
    }

    private static Map<Identifier, Quest> readQuests(ResourceManager resources) {
        Map<Identifier, Quest> loaded = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> file : listJson(resources, QUEST_DIRECTORY).entrySet()) {
            Identifier questId = toDefinitionId(file.getKey(), QUEST_DIRECTORY);
            if (questId == null) {
                continue;
            }
            try (BufferedReader reader = file.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                Quest.DATA_CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> NeroQuestsCommon.LOGGER.warn(
                                "[NeroQuests] Bad quest definition {}: {}", questId, error))
                        .ifPresent(data -> loaded.put(questId, new Quest(questId, data)));
            } catch (Exception e) {
                NeroQuestsCommon.LOGGER.warn("[NeroQuests] Could not read quest {}", questId, e);
            }
        }
        return loaded;
    }

    private static Map<Identifier, Chapter> readChapters(ResourceManager resources) {
        Map<Identifier, Chapter> loaded = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> file : listJson(resources, CHAPTER_DIRECTORY).entrySet()) {
            Identifier chapterId = toDefinitionId(file.getKey(), CHAPTER_DIRECTORY);
            if (chapterId == null) {
                continue;
            }
            try (BufferedReader reader = file.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                Chapter.DATA_CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> NeroQuestsCommon.LOGGER.warn(
                                "[NeroQuests] Bad chapter definition {}: {}", chapterId, error))
                        .ifPresent(data -> loaded.put(chapterId, new Chapter(chapterId, data)));
            } catch (Exception e) {
                NeroQuestsCommon.LOGGER.warn("[NeroQuests] Could not read chapter {}", chapterId, e);
            }
        }
        return loaded;
    }

    private static Map<Identifier, Resource> listJson(ResourceManager resources, String directory) {
        return resources.listResources(directory, file -> file.getPath().endsWith(EXTENSION));
    }

    // --- validation --------------------------------------------------------

    /**
     * Drops every quest that cannot work, in three passes: unusable bodies (no objectives, or
     * an unregistered objective/reward type), then dangling prerequisite references (pruned
     * from the quest, which survives), then prerequisite cycles (every quest in or behind a
     * cycle is dropped). The surviving map is in dependency order.
     */
    private static Map<Identifier, Quest> validateQuests(Map<Identifier, Quest> parsed) {
        Map<Identifier, Quest> usable = new LinkedHashMap<>();
        for (Quest quest : parsed.values()) {
            if (quest.objectives().isEmpty()) {
                NeroQuestsCommon.LOGGER.warn("[NeroQuests] Quest {} has no objectives; dropped.", quest.id());
                continue;
            }
            Identifier unknownType = firstUnknownType(quest);
            if (unknownType != null) {
                NeroQuestsCommon.LOGGER.warn(
                        "[NeroQuests] Quest {} uses unregistered objective/reward type '{}'; dropped.",
                        quest.id(), unknownType);
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
                                                            Map<Identifier, Quest> validQuests) {
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
                } else if (!claimed.add(entry.quest())) {
                    NeroQuestsCommon.LOGGER.warn(
                            "[NeroQuests] Quest {} is listed more than once (chapter {} keeps the first "
                                    + "listing only); the duplicate entry is ignored.",
                            entry.quest(), chapterId);
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
