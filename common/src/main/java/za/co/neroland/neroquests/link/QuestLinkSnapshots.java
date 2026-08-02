package za.co.neroland.neroquests.link;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.link.LinkSnapshotProvider;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.data.QuestProgress;
import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.RewardSpec;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;
import za.co.neroland.neroquests.quest.reward.GateReward;
import za.co.neroland.neroquests.quest.reward.ItemReward;
import za.co.neroland.neroquests.quest.reward.ReputationReward;
import za.co.neroland.neroquests.quest.reward.XpReward;

/**
 * The read half of the link module: what one player may see of the quest pack, and how far they
 * have got with it.
 *
 * <h2>Sections</h2>
 *
 * <ul>
 *   <li>{@code quests} — every quest <em>visible</em> to this player, each with its translation
 *       keys, scope, state, per-objective {@code current}/{@code target} pair, completion stamp and
 *       a reward summary;</li>
 *   <li>{@code chapters} — the quest-book chapters, each listing the ids of the quests in it that
 *       this player may see (a chapter whose every quest is hidden is left out entirely).</li>
 * </ul>
 *
 * <p>Any other section name yields an empty object, as Core's contract prescribes.
 *
 * <h2>Privacy (POPIA/GDPR)</h2>
 *
 * <p>Everything returned is scoped to {@code playerId} before it leaves this class — their own
 * counters and completion stamps, plus the shared {@code scope: server} rows, which belong to the
 * world and carry no identifiers. No names, no other players, no coordinates, no UUIDs in the
 * payload (the snapshot is implicitly "yours"). Quests gated behind an unopened {@code visible_gate}
 * are filtered out here, server-side, so a hidden quest's very existence never leaks.
 *
 * <p><b>Read-only and cheap.</b> Nothing here mutates anything; it walks the loaded definition map
 * and the player's own progress rows. The bridge governs how often it is called and caches the
 * result.
 *
 * <p>Server thread only.
 */
public final class QuestLinkSnapshots implements LinkSnapshotProvider {

    private static final List<String> SECTIONS =
            List.of(QuestLinkModule.SECTION_QUESTS, QuestLinkModule.SECTION_CHAPTERS);

    /** Quest states, as the app renders them. */
    private static final String STATE_COMPLETED = "completed";
    private static final String STATE_IN_PROGRESS = "in_progress";
    private static final String STATE_AVAILABLE = "available";
    private static final String STATE_LOCKED = "locked";

    @Override
    public String moduleId() {
        return QuestLinkModule.MODULE_ID;
    }

    @Override
    public int schemaVersion() {
        return QuestLinkModule.SCHEMA_VERSION;
    }

    @Override
    public List<String> sections() {
        return SECTIONS;
    }

    @Override
    public JsonObject snapshot(UUID playerId, String section, Map<String, String> params) {
        if (playerId == null || section == null) {
            return new JsonObject();
        }
        MinecraftServer server = QuestLinkAccess.server();
        if (server == null) {
            return new JsonObject();
        }
        try {
            if (QuestLinkModule.SECTION_QUESTS.equals(section)) {
                return quests(server, playerId);
            }
            if (QuestLinkModule.SECTION_CHAPTERS.equals(section)) {
                return chapters(server, playerId);
            }
            return new JsonObject(); // unknown section: nothing to say
        } catch (RuntimeException e) {
            // Section name only — never who asked (POPIA/GDPR). A failed snapshot must not
            // propagate into the bridge.
            NeroQuestsCommon.LOGGER.warn(
                    "[NeroQuests] NeroLink snapshot section '{}' failed; returning nothing for it.",
                    section, e);
            return new JsonObject();
        }
    }

    // --- section: quests ------------------------------------------------------

    /**
     * {@code {"schema_version":1,"player_online":true,"counts":{…},"quests":[…]}} — one entry per
     * visible quest, in the definitions' dependency order (prerequisites first), which is a stable
     * order for the app to render.
     */
    private static JsonObject quests(MinecraftServer server, UUID playerId) {
        Map<Identifier, Quest> definitions = QuestDefinitions.questsForServer(server);
        QuestProgressState state = QuestProgressState.get(server);
        Set<Identifier> openGates = QuestLinkAccess.openGates(server, playerId);
        Set<Identifier> playerCompleted = state.completedQuests(playerId);
        Set<Identifier> serverCompleted = state.completedServerQuests();
        Map<Identifier, Identifier> chapterOf = chapterIndex(server);

        JsonArray entries = new JsonArray();
        int completed = 0;
        int inProgress = 0;
        int available = 0;
        int locked = 0;

        for (Quest quest : definitions.values()) {
            if (!quest.isVisible(openGates)) {
                continue; // hidden in game, hidden here
            }
            boolean serverScope = quest.scope() == QuestScope.SERVER;
            Set<Identifier> completedSet = serverScope ? serverCompleted : playerCompleted;
            QuestProgress progress = (serverScope
                    ? state.serverProgress(quest.id())
                    : state.progress(playerId, quest.id())).orElse(QuestProgress.EMPTY);

            JsonObject entry = new JsonObject();
            entry.addProperty("id", quest.id().toString());
            entry.addProperty("title", quest.title());
            if (!quest.description().isEmpty()) {
                entry.addProperty("description", quest.description());
            }
            entry.addProperty("icon", quest.icon().toString());
            entry.addProperty("scope", scopeName(quest.scope()));
            Identifier chapter = chapterOf.get(quest.id());
            if (chapter != null) {
                entry.addProperty("chapter", chapter.toString());
            }

            JsonArray objectives = objectives(quest, progress);
            entry.add("objectives", objectives);
            entry.add("prerequisites", ids(quest.prerequisites()));
            entry.add("rewards", rewards(quest));

            String questState = state(quest, progress, completedSet, objectives);
            entry.addProperty("state", questState);
            if (progress.isComplete()) {
                entry.addProperty("completed_at", progress.completedAt());
            }
            switch (questState) {
                case STATE_COMPLETED -> completed++;
                case STATE_IN_PROGRESS -> inProgress++;
                case STATE_LOCKED -> locked++;
                default -> available++;
            }
            entries.add(entry);
        }

        JsonObject counts = new JsonObject();
        counts.addProperty("visible", entries.size());
        counts.addProperty(STATE_COMPLETED, completed);
        counts.addProperty(STATE_IN_PROGRESS, inProgress);
        counts.addProperty(STATE_AVAILABLE, available);
        counts.addProperty(STATE_LOCKED, locked);

        JsonObject root = new JsonObject();
        root.addProperty("schema_version", QuestLinkModule.SCHEMA_VERSION);
        // Player-scope gates only fully resolve for an online player, so the app can tell the app
        // user why a gated quest may be missing while they are logged off.
        root.addProperty("player_online", QuestLinkAccess.isOnline(server, playerId));
        root.add("counts", counts);
        root.add("quests", entries);
        return root;
    }

    /** {@code [{"index":0,"type":"…","current":3,"target":10,"content":"minecraft:iron_ingot"}]}. */
    private static JsonArray objectives(Quest quest, QuestProgress progress) {
        List<ObjectiveSpec> specs = quest.objectives();
        JsonArray out = new JsonArray();
        for (int index = 0; index < specs.size(); index++) {
            ObjectiveSpec spec = specs.get(index);
            int target = Math.max(1, spec.target());
            JsonObject objective = new JsonObject();
            objective.addProperty("index", index);
            objective.addProperty("type", spec.typeId().toString());
            objective.addProperty("current", Math.min(Math.max(progress.counter(index), 0), target));
            objective.addProperty("target", target);
            // A resource id / tag label only (the same string the missing-content warning logs).
            objective.addProperty("content", spec.contentLabel());
            out.add(objective);
        }
        return out;
    }

    /**
     * A reward summary — the reward's registered type id plus its primitive fields, read off the
     * definition. No item stacks are serialised: an item reward reports its item id and count and
     * nothing more, which is all a companion client needs to show "you will get 8 iron ingots".
     */
    private static JsonArray rewards(Quest quest) {
        JsonArray out = new JsonArray();
        for (RewardSpec reward : quest.rewards()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", reward.typeId().toString());
            // A reward type this build does not know about is reported by its id alone.
            if (reward instanceof ItemReward item) {
                entry.addProperty("item", item.item().toString());
                entry.addProperty("count", item.count());
            } else if (reward instanceof XpReward xp) {
                entry.addProperty("amount", xp.amount());
            } else if (reward instanceof GateReward gate) {
                entry.addProperty("gate", gate.gate().toString());
            } else if (reward instanceof CurrencyReward currency) {
                entry.addProperty("currency", currency.currency().toString());
                entry.addProperty("amount", currency.amount());
            } else if (reward instanceof ReputationReward reputation) {
                entry.addProperty("faction", reputation.faction().toString());
                entry.addProperty("amount", reputation.amount());
            }
            out.add(entry);
        }
        return out;
    }

    /**
     * Which of the four states this quest is in for this holder: complete, blocked by an unmet
     * prerequisite, started, or simply not started yet.
     */
    private static String state(Quest quest, QuestProgress progress, Set<Identifier> completedSet,
                                JsonArray objectives) {
        if (progress.isComplete()) {
            return STATE_COMPLETED;
        }
        if (!quest.prerequisitesMet(completedSet)) {
            return STATE_LOCKED;
        }
        for (int index = 0; index < objectives.size(); index++) {
            if (objectives.get(index).getAsJsonObject().get("current").getAsInt() > 0) {
                return STATE_IN_PROGRESS;
            }
        }
        return STATE_AVAILABLE;
    }

    // --- section: chapters ----------------------------------------------------

    /**
     * {@code {"schema_version":1,"chapters":[{"id":"…","title":"…","icon":"…","quests":["…"]}]}} —
     * the chapters in id order, each listing only the quests this player may see. A chapter left
     * with no visible quests is omitted, so hidden content stays invisible.
     */
    private static JsonObject chapters(MinecraftServer server, UUID playerId) {
        Map<Identifier, Quest> definitions = QuestDefinitions.questsForServer(server);
        Set<Identifier> openGates = QuestLinkAccess.openGates(server, playerId);

        JsonArray out = new JsonArray();
        for (Chapter chapter : QuestDefinitions.chaptersForServer(server).values()) {
            JsonArray questIds = new JsonArray();
            for (Chapter.Entry entry : chapter.quests()) {
                Quest quest = definitions.get(entry.quest());
                if (quest != null && quest.isVisible(openGates)) {
                    questIds.add(quest.id().toString());
                }
            }
            if (questIds.isEmpty()) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", chapter.id().toString());
            row.addProperty("title", chapter.title());
            row.addProperty("icon", chapter.icon().toString());
            row.add("quests", questIds);
            out.add(row);
        }

        JsonObject root = new JsonObject();
        root.addProperty("schema_version", QuestLinkModule.SCHEMA_VERSION);
        root.add("chapters", out);
        return root;
    }

    // --- helpers --------------------------------------------------------------

    /** quest id &rarr; the chapter that claims it, built once per snapshot (a quest has at most one). */
    private static Map<Identifier, Identifier> chapterIndex(MinecraftServer server) {
        Map<Identifier, Identifier> index = new HashMap<>();
        for (Chapter chapter : QuestDefinitions.chaptersForServer(server).values()) {
            for (Chapter.Entry entry : chapter.quests()) {
                index.putIfAbsent(entry.quest(), chapter.id());
            }
        }
        return index;
    }

    private static JsonArray ids(List<Identifier> source) {
        JsonArray out = new JsonArray();
        source.forEach(id -> out.add(id.toString()));
        return out;
    }

    /** Lower-case scope name, matching the datapack JSON spelling. */
    static String scopeName(QuestScope scope) {
        return scope == QuestScope.SERVER ? "server" : "player";
    }
}
