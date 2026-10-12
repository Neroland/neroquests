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

    /** Bumped on every replace/clear, so the quest book can cache derived state until the next sync. */
    private static volatile int generation;

    private ClientQuestProgress() {
    }

    // --- payload handling ---------------------------------------------------

    /** Replace the mirror with the server's latest snapshot for this player. */
    public static void accept(QuestProgressPayload payload) {
        snapshot = new Snapshot(toMap(payload.own()), toMap(payload.server()));
        generation++;
    }

    /** Drop everything. Called when the client leaves a world or server. */
    public static void clear() {
        snapshot = Snapshot.EMPTY;
        generation++;
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

    /** A counter bumped by every progress sync (and clear); the book compares it to notice new progress. */
    public static int generation() {
        return generation;
    }

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
     * The quest ids the local player has completed in their own section. With
     * {@link #serverCompletedQuests()} this is what the book hands to
     * {@link za.co.neroland.neroquests.quest.QuestStates} (AUD-18), which resolves each prerequisite
     * against the section of its own scope.
     */
    public static Set<Identifier> ownCompletedQuests() {
        return completedIn(snapshot.own());
    }

    /** The world's completed {@code scope: server} quest ids. */
    public static Set<Identifier> serverCompletedQuests() {
        return completedIn(snapshot.server());
    }

    private static Set<Identifier> completedIn(Map<Identifier, QuestProgress> section) {
        Set<Identifier> complete = new LinkedHashSet<>();
        section.forEach((quest, progress) -> {
            if (progress.isComplete()) {
                complete.add(quest);
            }
        });
        return java.util.Collections.unmodifiableSet(complete);
    }
}
