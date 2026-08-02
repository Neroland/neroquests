package za.co.neroland.neroquests.client;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.data.QuestProgress;
import za.co.neroland.neroquests.network.QuestProgressPayload;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;

/**
 * The client's mirror of the local player's quest progress, populated by
 * {@link QuestProgressPayload}.
 *
 * <p>Mirrors Neroland Core's {@code ClientGates}: pure data, no client-only imports, replaced
 * wholesale and published through a {@code volatile} field so a reader always sees one complete
 * snapshot. <b>The client never decides completion</b> — every method here reports what the server
 * last said, and nothing writes back.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> holds the local player's own rows plus the identifier-free
 * server-scoped section. No other player's progress is ever sent to, or stored on, a client.
 */
public final class ClientQuestProgress {

    /** Own + shared rows, published as one immutable unit. */
    private record Snapshot(Map<Identifier, QuestProgress> own, Map<Identifier, QuestProgress> server) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());
    }

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private ClientQuestProgress() {
    }

    // --- payload handling ---------------------------------------------------

    /** Replace the mirror with the server's latest snapshot for this player. */
    public static void accept(QuestProgressPayload payload) {
        snapshot = new Snapshot(toMap(payload.own()), toMap(payload.server()));
    }

    /** Drop everything. Called when the client leaves a world or server. */
    public static void clear() {
        snapshot = Snapshot.EMPTY;
    }

    private static Map<Identifier, QuestProgress> toMap(java.util.List<QuestProgressPayload.Row> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Identifier, QuestProgress> out = new LinkedHashMap<>(rows.size());
        for (QuestProgressPayload.Row row : rows) {
            out.put(row.quest(), row.toProgress());
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    // --- accessors ----------------------------------------------------------

    /**
     * The progress stored for {@code quest}, read from whichever section owns it — the player's
     * own rows for a {@code scope: player} quest, the shared section for {@code scope: server}.
     * Never null: an untouched quest reads as {@link QuestProgress#EMPTY}.
     */
    public static QuestProgress progress(Quest quest) {
        Map<Identifier, QuestProgress> section =
                quest.scope() == QuestScope.SERVER ? snapshot.server() : snapshot.own();
        return section.getOrDefault(quest.id(), QuestProgress.EMPTY);
    }

    /** The local player's own progress on a quest id (ignores the shared section). */
    public static QuestProgress ownProgress(Identifier quest) {
        return snapshot.own().getOrDefault(quest, QuestProgress.EMPTY);
    }

    /** The world's shared progress on a {@code scope: server} quest id. */
    public static QuestProgress serverProgress(Identifier quest) {
        return snapshot.server().getOrDefault(quest, QuestProgress.EMPTY);
    }

    /** Whether {@code quest} is complete for this client, in the scope that owns it. */
    public static boolean isComplete(Quest quest) {
        return progress(quest).isComplete();
    }

    /** One objective's counter for {@code quest}, in the scope that owns it. */
    public static int counter(Quest quest, int objectiveIndex) {
        return progress(quest).counter(objectiveIndex);
    }

    /**
     * The quest ids that count as complete for this client — the player's own completions plus the
     * world's completed {@code scope: server} quests. This is the set the book feeds to
     * {@link Quest#prerequisitesMet(Set)}.
     */
    public static Set<Identifier> completedQuests() {
        Snapshot current = snapshot;
        Set<Identifier> complete = new LinkedHashSet<>();
        current.own().forEach((quest, progress) -> {
            if (progress.isComplete()) {
                complete.add(quest);
            }
        });
        current.server().forEach((quest, progress) -> {
            if (progress.isComplete()) {
                complete.add(quest);
            }
        });
        return java.util.Collections.unmodifiableSet(complete);
    }
}
