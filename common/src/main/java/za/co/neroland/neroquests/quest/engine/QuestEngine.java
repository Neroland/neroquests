package za.co.neroland.neroquests.quest.engine;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.data.QuestProgress;
import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.link.QuestLinkEvents;
import za.co.neroland.neroquests.network.QuestSync;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestDefinitions;
import za.co.neroland.neroquests.quest.QuestScope;

/**
 * The server-side brain: given a player and (optionally) one thing that just happened, bring every
 * quest they can currently work on up to date and complete the ones that are finished.
 *
 * <p>One pass over a player looks like this:
 *
 * <ol>
 *   <li>Take the player's completed set, the world's completed set, and the gate ids Neroland Core
 *       currently resolves as open for them.</li>
 *   <li>For every loaded quest that is <em>available</em> (visible gate open, prerequisites met)
 *       and not already complete, walk its objectives — crediting the triggering event once, then
 *       re-measuring whatever can be measured.</li>
 *   <li>A quest whose every objective has reached its target is marked complete and published to
 *       {@link QuestCompletionListeners} (which is where Stage 5 hangs reward execution).</li>
 * </ol>
 *
 * <p><b>Cascades.</b> Completing a quest can tick a {@code quest_complete} objective in another
 * one, so the whole pass repeats while anything completed. This terminates because completion is
 * monotonic — a quest never un-completes, so each extra pass has strictly fewer candidates. A
 * hard pass cap guards against a future non-monotonic mistake rather than against the current
 * logic. The triggering event is credited on the first pass only, never re-credited by a cascade.
 *
 * <p><b>Server scope.</b> A {@code scope: server} quest reads and writes the shared section of the
 * progress store instead of the player's own, so any player's trigger advances it and it completes
 * once for the whole world. Measured objectives there may only ever <em>raise</em> shared progress:
 * one player's inventory snapshot must not undo what the world has collectively achieved.
 *
 * <p><b>Cost.</b> Nothing here scans anything for a quest that is complete, hidden or locked, so a
 * player who has finished the pack costs one walk of the (small) quest map and no world reads at
 * all. Measured objectives — the only ones that touch the world — are reached solely for quests
 * actively in progress, which is what keeps the periodic sweep affordable.
 *
 * <p>Server thread only.
 */
public final class QuestEngine {

    /**
     * Safety cap on the completion cascade. Real chains are one or two deep; this only exists so a
     * future objective that is accidentally non-monotonic cannot spin the server thread.
     */
    private static final int MAX_CASCADE_PASSES = 8;

    private QuestEngine() {
    }

    /**
     * How much one discrete event is worth to a given objective. Trigger entry points build one of
     * these and hand it to {@link #evaluate(ServerPlayer, Credit)}; the engine applies it exactly
     * once per objective per event.
     */
    @FunctionalInterface
    public interface Credit {

        /** Units this event adds to {@code objective}, or {@code 0} if it does not apply. */
        int of(ObjectiveSpec objective, ObjectiveContext context);
    }

    /**
     * What one evaluation pass changed, so the pass can push exactly one progress payload per
     * affected player at the end instead of one per write. Instance-per-call, so a reward that
     * (some day) re-enters the engine cannot corrupt the outer pass's record.
     */
    private static final class Mutations {

        /** The evaluated player's own rows changed. */
        private boolean player;

        /** The shared {@code scope: server} section changed — everyone online can see it. */
        private boolean server;

        /**
         * Which counters moved in the player's own rows — quest id &rarr; (objective index &rarr;
         * the counter's value afterwards). Allocated lazily, so a pass that changes nothing (the
         * common case on the 1 Hz sweep) allocates nothing. Feeds the NeroLink {@code progress}
         * event; the client sync payload is a whole snapshot and needs none of this.
         */
        private Map<Identifier, Map<Integer, Integer>> playerChanges;

        /** The same for the shared {@code scope: server} section. */
        private Map<Identifier, Map<Integer, Integer>> serverChanges;

        /** Record one counter's new value against the section that owns it. */
        void note(Identifier quest, int index, int value, boolean serverScope) {
            if (serverScope) {
                if (serverChanges == null) {
                    serverChanges = new LinkedHashMap<>();
                }
                put(serverChanges, quest, index, value);
            } else {
                if (playerChanges == null) {
                    playerChanges = new LinkedHashMap<>();
                }
                put(playerChanges, quest, index, value);
            }
        }

        private static void put(Map<Identifier, Map<Integer, Integer>> target, Identifier quest,
                                int index, int value) {
            target.computeIfAbsent(quest, id -> new LinkedHashMap<>())
                    .put(Integer.valueOf(index), Integer.valueOf(value));
        }
    }

    /** Re-measure everything measurable for this player and settle any completions. */
    public static void evaluate(ServerPlayer player) {
        evaluate(player, null);
    }

    /**
     * Credit one discrete event to this player's objectives, re-measure alongside it, and settle
     * any completions.
     *
     * <p>Whatever the pass changed is pushed to the client(s) once, at the end — see
     * {@link #publish}.
     *
     * @param credit what the event is worth per objective, or {@code null} for a plain re-measure
     */
    public static void evaluate(ServerPlayer player, Credit credit) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        Map<Identifier, Quest> quests = QuestDefinitions.questsForServer(server);
        if (quests.isEmpty()) {
            return;
        }
        QuestProgressState state = QuestProgressState.get(server);
        Set<Identifier> openGates = openGatesFor(player);
        Mutations mutations = new Mutations();
        try {
            cascade(player, server, quests, state, openGates, credit, mutations);
        } finally {
            // Even a pass that hit the cascade cap has already written real progress; the client
            // must see it.
            publish(server, player, mutations);
        }
    }

    /**
     * Complete a quest for a player because an operator said so ({@code /neroquests grant}), taking
     * exactly the same path a genuinely finished quest takes: the store is marked in whichever
     * section the quest's scope owns, {@link QuestCompletionListeners} fires (so rewards pay out),
     * the cascade re-settles anything that was waiting on this quest — a {@code quest_complete}
     * objective, or a quest whose prerequisites this just satisfied — and whatever changed is
     * pushed to the client(s) once at the end.
     *
     * <p>Objective counters are deliberately <em>not</em> filled in: completion is what the rest of
     * the engine reads, and a completed quest is never re-settled.
     *
     * @return {@code true} if this newly completed the quest, {@code false} if it already was
     */
    public static boolean adminComplete(ServerPlayer player, Quest quest) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        QuestProgressState state = QuestProgressState.get(server);
        boolean serverScope = quest.scope() == QuestScope.SERVER;
        UUID uuid = player.getUUID();
        Mutations mutations = new Mutations();
        boolean newlyComplete = serverScope
                ? state.markServerComplete(quest.id(), System.currentTimeMillis())
                : state.markComplete(uuid, quest.id(), System.currentTimeMillis());
        if (!newlyComplete) {
            return false;
        }
        try {
            record(mutations, serverScope);
            QuestCompletionListeners.fire(new QuestCompletion(server, uuid, quest));
            cascade(player, server, QuestDefinitions.questsForServer(server), state,
                    openGatesFor(player), null, mutations);
        } finally {
            publish(server, player, mutations);
        }
        return true;
    }

    /** The completion cascade: credit once, then re-settle while anything is still completing. */
    private static void cascade(ServerPlayer player, MinecraftServer server,
                                Map<Identifier, Quest> quests, QuestProgressState state,
                                Set<Identifier> openGates, Credit credit, Mutations mutations) {
        Credit pending = credit;
        for (int pass = 0; pass < MAX_CASCADE_PASSES; pass++) {
            Set<Identifier> playerCompleted = state.completedQuests(player.getUUID());
            Set<Identifier> serverCompleted = state.completedServerQuests();
            boolean completedSomething = false;

            for (Quest quest : quests.values()) {
                boolean serverScope = quest.scope() == QuestScope.SERVER;
                Set<Identifier> completed = serverScope ? serverCompleted : playerCompleted;
                if (completed.contains(quest.id()) || !quest.isAvailable(completed, openGates)) {
                    continue;
                }
                ObjectiveContext context =
                        new ObjectiveContext(server, player, quest.scope(), completed);
                // No short-circuit: settle() must run for every candidate quest, not just up to
                // the first one that completes.
                completedSomething |= settle(state, quest, context, pending, mutations);
            }

            pending = null; // a discrete event counts once, never again on a cascade pass
            if (!completedSomething) {
                return;
            }
        }
        NeroQuestsCommon.LOGGER.warn(
                "[NeroQuests] Quest completion cascade did not settle within {} passes; stopping this "
                        + "evaluation. This suggests an objective whose progress is not monotonic.",
                MAX_CASCADE_PASSES);
    }

    // --- one quest ----------------------------------------------------------

    /**
     * Bring one quest's objectives up to date and complete it if they are all satisfied.
     *
     * @return {@code true} if this call newly completed the quest
     */
    private static boolean settle(QuestProgressState state, Quest quest, ObjectiveContext context,
                                  Credit credit, Mutations mutations) {
        List<ObjectiveSpec> objectives = quest.objectives();
        boolean serverScope = context.scope() == QuestScope.SERVER;
        UUID player = context.player().getUUID();
        Identifier questId = quest.id();
        boolean allSatisfied = true;

        for (int index = 0; index < objectives.size(); index++) {
            ObjectiveSpec objective = objectives.get(index);
            int target = Math.max(1, objective.target());

            // Cross-mod degradation: content this installation does not have never blocks a quest.
            if (!objective.contentPresent(context)) {
                MissingContent.Policy policy = MissingContent.policy();
                MissingContent.warnOnce(questId, index, objective, policy);
                if (policy == MissingContent.Policy.AUTOCOMPLETE) {
                    int stored = counter(state, player, questId, index, serverScope);
                    if (stored < target) {
                        write(state, player, questId, index, target - stored, serverScope, mutations);
                    }
                }
                continue; // satisfied under 'autocomplete', ignored under 'skip' — either way, no block
            }

            int current = counter(state, player, questId, index, serverScope);

            if (credit != null && current < target) {
                int amount = credit.of(objective, context);
                if (amount > 0) {
                    current = write(state, player, questId, index, amount, serverScope, mutations);
                }
            }

            OptionalInt measured = objective.measure(context);
            if (measured.isPresent()) {
                int value = Math.min(Math.max(measured.getAsInt(), 0), target);
                // Shared world progress may only be raised by any one player's snapshot; personal
                // progress may also fall back when the objective is a live recount (collect_item).
                boolean mayLower = objective.measureRegresses() && !serverScope;
                if (value > current || (mayLower && value < current)) {
                    current = write(state, player, questId, index, value - current, serverScope, mutations);
                }
            }

            if (current < target) {
                allSatisfied = false;
            }
        }

        if (!allSatisfied) {
            return false;
        }
        boolean newlyComplete = serverScope
                ? state.markServerComplete(questId, System.currentTimeMillis())
                : state.markComplete(player, questId, System.currentTimeMillis());
        if (newlyComplete) {
            record(mutations, serverScope);
            QuestCompletionListeners.fire(new QuestCompletion(context.server(), player, quest));
        }
        return newlyComplete;
    }

    // --- progress-store helpers ---------------------------------------------

    /** One objective's stored counter, from whichever section of the store owns this quest. */
    private static int counter(QuestProgressState state, UUID player, Identifier quest, int index,
                               boolean serverScope) {
        Optional<QuestProgress> progress = serverScope
                ? state.serverProgress(quest)
                : state.progress(player, quest);
        return progress.isPresent() ? progress.get().counter(index) : 0;
    }

    /** Add {@code delta} (which may be negative) to one counter; returns the value afterwards. */
    private static int write(QuestProgressState state, UUID player, Identifier quest, int index,
                             int delta, boolean serverScope, Mutations mutations) {
        record(mutations, serverScope);
        int updated = serverScope
                ? state.incrementServerObjective(quest, index, delta)
                : state.incrementObjective(player, quest, index, delta);
        mutations.note(quest, index, updated, serverScope);
        return updated;
    }

    /** Note which section of the progress store this pass has touched. */
    private static void record(Mutations mutations, boolean serverScope) {
        if (serverScope) {
            mutations.server = true;
        } else {
            mutations.player = true;
        }
    }

    // --- client sync ---------------------------------------------------------

    /**
     * Push what this pass changed, once. A change to the shared {@code scope: server} section is
     * visible to everyone, so it fans out to every online player (each getting their own snapshot);
     * otherwise only the evaluated player hears about it. A pass that changed nothing sends nothing,
     * which is what keeps the 1 Hz sweep silent on a settled world.
     *
     * <p>The NeroLink {@code progress} event rides along here for exactly the same reason: at most
     * one event per player per pass, and none at all when nothing moved.
     */
    private static void publish(MinecraftServer server, ServerPlayer player, Mutations mutations) {
        if (mutations.server) {
            QuestSync.syncProgressAll(server);
        } else if (mutations.player) {
            QuestSync.syncProgressTo(player);
        }
        QuestLinkEvents.publishProgress(player.getUUID(), mutations.playerChanges,
                mutations.serverChanges);
    }

    // --- gates --------------------------------------------------------------

    /**
     * The Core gate ids currently open for this player, as {@link Identifier}s, for
     * {@link Quest#isVisible(Set)}. Core hands them over as strings (its sync format); anything
     * unparseable is dropped rather than thrown. Public so {@code /neroquests list &lt;player&gt;}
     * can report the same locked/available verdict the engine itself would reach.
     */
    public static Set<Identifier> openGatesFor(ServerPlayer player) {
        List<String> resolved = ProgressionGates.resolvedOpenGates(player);
        if (resolved.isEmpty()) {
            return Set.of();
        }
        Set<Identifier> gates = new LinkedHashSet<>(resolved.size());
        for (String id : resolved) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed != null) {
                gates.add(parsed);
            }
        }
        return gates;
    }
}
