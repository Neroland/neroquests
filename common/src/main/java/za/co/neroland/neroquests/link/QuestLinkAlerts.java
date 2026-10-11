package za.co.neroland.neroquests.link;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.link.LinkAlert;
import za.co.neroland.nerolandcore.link.LinkAlerts;
import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * NeroQuests' two NeroLink alerts (G1), raised through Core's {@link LinkAlerts} store so the app can
 * show them while it is closed.
 *
 * <ul>
 *   <li>{@value #REWARD_TEXT} — a new owed entry was kept for the player;</li>
 *   <li>{@value #CHAPTER_TEXT} — a pack or custom chapter became visible to the player in game.</li>
 * </ul>
 *
 * <p><b>Fixed text only (POPIA/GDPR).</b> The alert text is one of the two English constants above —
 * never a quest or chapter title (custom titles are player-written), never an id, amount or name. Each
 * alert has one stable id per player ({@value #REWARD_ALERT_ID}, {@value #CHAPTER_ALERT_ID}), so Core's
 * store holds at most two NeroQuests alerts per player — well inside Core 1.15.0's per-module and
 * per-player caps, which Core enforces on {@code raise} anyway. An alert with the same id raised less than
 * an hour ago is not raised again (dedupe per player per hour, read from Core's stored {@code createdAt},
 * so it survives restarts without NeroQuests storing anything).
 *
 * <p>The "seen chapters" set used to notice a new chapter is in memory only, per online player, and is
 * dropped on logout and server stop; the first view after joining only records, it never alerts.
 *
 * <p>Server thread only.
 */
public final class QuestLinkAlerts {

    /** Stable alert id for "a reward is waiting". */
    public static final String REWARD_ALERT_ID = "neroquests:reward_waiting";

    /** Stable alert id for "a new chapter appeared". */
    public static final String CHAPTER_ALERT_ID = "neroquests:new_chapter";

    /** Fixed alert text (English constant; no player data). */
    public static final String REWARD_TEXT = "A reward is waiting for you";

    /** Fixed alert text (English constant; no player data). */
    public static final String CHAPTER_TEXT = "A new chapter appeared";

    /** The same alert id is raised at most once per this window. */
    public static final long DEDUPE_MILLIS = 60L * 60L * 1000L;

    private static final Map<UUID, Set<Identifier>> SEEN_CHAPTERS = new HashMap<>();

    private QuestLinkAlerts() {
    }

    /** A new owed entry exists for {@code player}. */
    static void rewardWaiting(UUID player) {
        raise(player, REWARD_ALERT_ID, REWARD_TEXT);
    }

    /**
     * Called with the pack/custom chapters {@code player} can see right now (whenever their definitions are
     * (re)sent). Raises the chapter alert when one appears that was not in their previous view.
     */
    public static void chaptersVisible(UUID player, Set<Identifier> packOrCustomChapters) {
        Set<Identifier> previous;
        synchronized (SEEN_CHAPTERS) {
            previous = SEEN_CHAPTERS.put(player, new LinkedHashSet<>(packOrCustomChapters));
        }
        if (hasNewChapter(previous, packOrCustomChapters)) {
            raise(player, CHAPTER_ALERT_ID, CHAPTER_TEXT);
        }
    }

    /** Whether {@code current} holds a chapter {@code previous} did not; never on the first view (null). */
    static boolean hasNewChapter(Set<Identifier> previous, Set<Identifier> current) {
        if (previous == null) {
            return false;
        }
        for (Identifier id : current) {
            if (!previous.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** Whether an alert {@code id} may be raised at {@code now}, given the player's stored alerts. */
    static boolean mayRaise(List<LinkAlert> existing, String id, long now) {
        for (LinkAlert alert : existing) {
            if (alert.id().equals(id) && now - alert.createdAt() < DEDUPE_MILLIS) {
                return false;
            }
        }
        return true;
    }

    /** Forget players no longer online (bounded memory). */
    public static void retainOnline(Set<UUID> online) {
        synchronized (SEEN_CHAPTERS) {
            SEEN_CHAPTERS.keySet().retainAll(online);
        }
    }

    /** Drop all in-memory state (server stopped). */
    public static void serverStopped() {
        synchronized (SEEN_CHAPTERS) {
            SEEN_CHAPTERS.clear();
        }
    }

    private static void raise(UUID player, String id, String text) {
        MinecraftServer server = QuestLinkAccess.server();
        if (server == null || player == null) {
            return;
        }
        try {
            LinkAlerts alerts = LinkAlerts.get(server);
            if (!mayRaise(alerts.list(player), id, System.currentTimeMillis())) {
                return;
            }
            alerts.raise(server, player,
                    LinkAlert.raise(id, QuestLinkModule.MODULE_ID, LinkAlert.Severity.INFO, text));
        } catch (RuntimeException | LinkageError e) {
            // Alert id only — never who (POPIA/GDPR). Alerts are a convenience; quests are unaffected.
            NeroQuestsCommon.LOGGER.debug("[NeroQuests] Could not raise the NeroLink alert '{}'.", id, e);
        }
    }
}
