package za.co.neroland.neroquests.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.custom.CustomQuests;
import za.co.neroland.neroquests.custom.EditorSync;
import za.co.neroland.neroquests.link.QuestLinkAlerts;
import za.co.neroland.neroquests.platform.Services;
import za.co.neroland.neroquests.quest.Chapter;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestSource;
import za.co.neroland.neroquests.quest.engine.QuestEngine;

/**
 * The one server-side entry point for pushing quest state to clients, so every call site — the
 * loaders' join hooks, the reload hook, the engine — stays free of loader types and of payload
 * construction.
 *
 * <p>Four moments produce a sync:
 *
 * <ul>
 *   <li><b>Join</b> — {@link #syncTo(ServerPlayer)} sends the joining player the definitions they
 *       may see and their own progress. Each loader entry point wires its own join event to this.</li>
 *   <li><b>Reload</b> — {@link #syncAll(MinecraftServer)} after a datapack reload has re-read and
 *       re-validated the definitions, because both halves may have changed meaning.</li>
 *   <li><b>Gate change</b> (AUD-19) — {@link #markDefinitionsDirty(UUID)} /
 *       {@link #markDefinitionsDirtyAll()}; at the end of the tick the player's visible set is
 *       recomputed and their definitions re-sent only if it changed.</li>
 *   <li><b>Progress change</b> (AUD-20) — the engine marks the evaluated player (or, when shared
 *       {@code scope: server} progress moved, everyone) dirty during its passes;
 *       {@link #flush(MinecraftServer)} at the end of the server tick sends each dirty player one
 *       snapshot, however many passes touched them in that tick.</li>
 * </ul>
 *
 * <p><b>Hidden quests (AUD-19).</b> A quest whose {@code visible_gate} is closed for a player is not
 * sent to that player at all, and chapter entries naming it are left out of their copy of the chapter
 * (a chapter left with nothing visible is not sent). The book then has nothing to leak: not a title,
 * not an id. A visible quest that depends on a hidden one simply draws no line to it, and the shared
 * state rule treats a prerequisite it has no definition for by the server-sent completion sets.
 *
 * <p><b>Paging (AUD-21).</b> Definitions go out in size-bounded pages ({@link QuestDefinitionsPayload});
 * the encoded entries are cached per definitions generation and only filtered per player.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> a progress payload is only ever addressed to the player whose
 * rows it contains. Nothing here broadcasts one player's progress to another — a server-scope
 * change fans out one <em>individually built</em> snapshot per player, each carrying that player's
 * own rows plus the identifier-free shared section. The per-player bookkeeping here (dirty marks, the
 * last definitions view sent) lives in memory only, is never logged, and is dropped on server stop.
 *
 * <p>Server thread only.
 */
public final class QuestSync {

    /** Encoded quest entries of one definitions generation (chapters are filtered per player). */
    private record Cache(MinecraftServer server, int generation, Map<Identifier, QuestDefinitionsPayload.Entry> quests) {
    }

    private static Cache cache;
    private static int nextSyncId;

    /** Ids already reported as too large to sync, so the warning is logged once per id per server. */
    private static final Set<Identifier> OVERSIZE_WARNED = new HashSet<>();

    /** Per online player: the definitions view last sent (generation + visible quest ids). */
    private static final Map<UUID, Integer> SENT_VIEW = new HashMap<>();

    private static final Set<UUID> DEFINITIONS_DIRTY = new LinkedHashSet<>();
    private static boolean definitionsDirtyAll;

    private static final Set<UUID> PROGRESS_DIRTY = new LinkedHashSet<>();
    private static boolean progressDirtyAll;

    private QuestSync() {
    }

    // --- join ---------------------------------------------------------------

    /** Everything a joining player needs: the definitions they may see plus their own progress. */
    public static void syncTo(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        sendDefinitions(server, player, true);
        sendProgress(server, player);
    }

    // --- reload -------------------------------------------------------------

    /** Re-send definitions and progress to every online player (after a datapack reload). */
    public static void syncAll(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            sendDefinitions(server, player, true);
            sendProgress(server, player);
        }
        DEFINITIONS_DIRTY.clear();
        definitionsDirtyAll = false;
        PROGRESS_DIRTY.clear();
        progressDirtyAll = false;
    }

    // --- immediate progress (admin commands) ---------------------------------

    /** Send one player a fresh snapshot of their own progress now. */
    public static void syncProgressTo(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            sendProgress(server, player);
        }
    }

    /** Send every online player a fresh snapshot of their own progress now. */
    public static void syncProgressAll(MinecraftServer server) {
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            sendProgress(server, player);
        }
    }

    // --- coalesced (AUD-19, AUD-20) ------------------------------------------

    /** This player's own progress changed; sent once at the end of the tick. */
    public static void markProgressDirty(UUID player) {
        PROGRESS_DIRTY.add(player);
    }

    /** Shared {@code scope: server} progress changed — everyone online re-syncs once at the end of the tick. */
    public static void markAllProgressDirty() {
        progressDirtyAll = true;
    }

    /** A gate changed for this player; their visible definitions are re-checked at the end of the tick. */
    public static void markDefinitionsDirty(UUID player) {
        DEFINITIONS_DIRTY.add(player);
    }

    /** A server or team gate changed; every online player's visible definitions are re-checked. */
    public static void markDefinitionsDirtyAll() {
        definitionsDirtyAll = true;
    }

    /**
     * Send what this tick marked dirty: definitions (only to players whose visible set actually changed),
     * then progress — at most one of each per player per tick. Called at the end of every server tick.
     */
    public static void flush(MinecraftServer server) {
        if (definitionsDirtyAll || !DEFINITIONS_DIRTY.isEmpty()) {
            boolean all = definitionsDirtyAll;
            Set<UUID> marked = Set.copyOf(DEFINITIONS_DIRTY);
            definitionsDirtyAll = false;
            DEFINITIONS_DIRTY.clear();
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                if (all || marked.contains(player.getUUID())) {
                    sendDefinitions(server, player, false);
                }
            }
        }
        if (progressDirtyAll || !PROGRESS_DIRTY.isEmpty()) {
            boolean all = progressDirtyAll;
            Set<UUID> marked = Set.copyOf(PROGRESS_DIRTY);
            progressDirtyAll = false;
            PROGRESS_DIRTY.clear();
            for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                if (all || marked.contains(player.getUUID())) {
                    sendProgress(server, player);
                }
            }
        }
        if (SENT_VIEW.size() > server.getPlayerList().getPlayerCount() * 2 + 16) {
            Set<UUID> online = new HashSet<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                online.add(player.getUUID());
            }
            SENT_VIEW.keySet().retainAll(online);
            QuestLinkAlerts.retainOnline(online);
        }
    }

    /** Drop every static reference to the stopped server and all per-player bookkeeping (AUD-31). */
    public static void serverStopped() {
        cache = null;
        OVERSIZE_WARNED.clear();
        SENT_VIEW.clear();
        DEFINITIONS_DIRTY.clear();
        definitionsDirtyAll = false;
        PROGRESS_DIRTY.clear();
        progressDirtyAll = false;
    }

    // --- internals ----------------------------------------------------------

    private static void sendProgress(MinecraftServer server, ServerPlayer player) {
        Services.NETWORK.sendToPlayer(player, QuestProgressPayload.of(server, player));
    }

    /**
     * Send {@code player} the definitions they may see, paged. Unless {@code force}, nothing is sent
     * when their view (definitions generation + visible quest ids) is the one they already have.
     */
    private static void sendDefinitions(MinecraftServer server, ServerPlayer player, boolean force) {
        Cache current = cache(server);
        Map<Identifier, Quest> definitions = QuestDefinitions.questsForServer(server);
        Set<Identifier> openGates = QuestEngine.openGatesFor(player);
        Set<Identifier> visible = new LinkedHashSet<>();
        for (Quest quest : definitions.values()) {
            if (quest.isVisible(openGates)) {
                visible.add(quest.id());
            }
        }
        // D2/E1: the receiving player's own editor block (can_author, caps, the quests they wrote).
        JsonObject editorInfo = EditorSync.info(server, player);
        boolean canAuthor = editorInfo.get(EditorSync.CAN_AUTHOR).getAsBoolean();
        String editor = editorInfo.toString();
        int view = 31 * (31 * current.generation() + visible.hashCode()) + editor.hashCode();
        UUID id = player.getUUID();
        Integer last = SENT_VIEW.get(id);
        if (!force && last != null && last.intValue() == view) {
            return;
        }
        List<QuestDefinitionsPayload.Entry> entries = new ArrayList<>(visible.size() + 8);
        for (Identifier quest : visible) {
            QuestDefinitionsPayload.Entry entry = current.quests().get(quest);
            if (entry != null) {
                entries.add(entry);
            }
        }
        Set<Identifier> packOrCustomShown = new LinkedHashSet<>();
        for (Chapter chapter : QuestDefinitions.chaptersForServer(server).values()) {
            List<Chapter.Entry> shown = new ArrayList<>(chapter.quests().size());
            for (Chapter.Entry entry : chapter.quests()) {
                if (visible.contains(entry.quest())) {
                    shown.add(entry);
                }
            }
            boolean custom = chapter.source() == QuestSource.CUSTOM;
            if (!shown.isEmpty() && chapter.source() != QuestSource.SPINE) {
                packOrCustomShown.add(chapter.id());
            }
            // An empty custom chapter is still sent to the players who may author (they need it to add quests).
            if (!shown.isEmpty() || (custom && canAuthor)) {
                QuestDefinitionsPayload.chapter(chapter, shown,
                        custom ? CustomQuests.chapterSyncExtra(server, chapter.id()) : null).ifPresent(entries::add);
            }
        }
        for (QuestDefinitionsPayload page : QuestDefinitionsPayload.paginate(++nextSyncId, editor, entries,
                QuestSync::warnOversize)) {
            Services.NETWORK.sendToPlayer(player, page);
        }
        SENT_VIEW.put(id, Integer.valueOf(view));
        // G1: "A new chapter appeared" (fixed text, deduped) when a pack/custom chapter joins this view.
        QuestLinkAlerts.chaptersVisible(id, packOrCustomShown);
    }

    /** The encoded quest entries for this server, rebuilt only when the definitions were re-read. */
    private static Cache cache(MinecraftServer server) {
        int generation = QuestDefinitions.generation();
        Cache current = cache;
        if (current != null && current.server() == server && current.generation() == generation) {
            return current;
        }
        Map<Identifier, QuestDefinitionsPayload.Entry> quests = new LinkedHashMap<>();
        for (Quest quest : QuestDefinitions.questsForServer(server).values()) {
            JsonObject extra = quest.source() == QuestSource.CUSTOM ? CustomQuests.questSyncExtra(server, quest.id()) : null;
            QuestDefinitionsPayload.quest(quest, extra).ifPresent(entry -> quests.put(quest.id(), entry));
        }
        current = new Cache(server, QuestDefinitions.generation(), quests);
        cache = current;
        return current;
    }

    /** An entry too large for one page: logged once by kind and id (never its text), then skipped. */
    private static void warnOversize(QuestDefinitionsPayload.Entry entry) {
        if (OVERSIZE_WARNED.add(entry.id())) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] {} {} is too large to sync to clients ({} bytes encoded); "
                            + "the quest book will not show it.",
                    entry.kind() == QuestDefinitionsPayload.Kind.QUEST ? "Quest" : "Chapter", entry.id(),
                    Integer.valueOf(entry.encodedSize()));
        }
    }
}
