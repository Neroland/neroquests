package za.co.neroland.neroquests.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.QuestSource;

/**
 * The one server-wide store of quest progress, in two sections mirroring {@link QuestScope}:
 *
 * <ul>
 *   <li><b>player</b> — {@code UUID → (quest id → {@link QuestProgress})} for {@code scope: player}
 *       quests, plus one last-updated epoch-millis stamp per player that drives retention;</li>
 *   <li><b>server</b> — {@code quest id → QuestProgress} for {@code scope: server} quests, shared by
 *       everyone and holding no UUIDs at all.</li>
 * </ul>
 *
 * <p>Persisted as vanilla {@link SavedData} on the overworld (so it is always loaded) through the
 * same {@link SavedDataType} + Codec pattern Neroland Core uses for its own state. Every accessor
 * goes through {@link SavedDataRecovery} (Core's recovering accessor, AUD-16): a corrupt file is copied
 * aside and the store rolls back to Core's last-known-good backup, or starts empty without one,
 * instead of crashing the server.
 *
 * <p><b>Threading:</b> server thread only. The Stage-4 objective engine, the quest book's server
 * handlers and the admin commands all run there; nothing here is synchronised.
 *
 * <p><b>Privacy (POPIA/GDPR).</b> Player rows are keyed by the player's existing Minecraft game
 * UUID and hold only quest ids, integer objective counters and epoch-millis timestamps — no names,
 * IPs, chat, coordinates or any other personal information. Three controls apply:
 * <ul>
 *   <li><b>Erasure</b> — {@link #forgetPlayer(UUID)} purges a UUID completely; it is wired into
 *       Core's shared {@code PlayerDataErasure} hook from {@link QuestData#init()}, so one request
 *       clears a player across every Nero mod. Erasure never logs player identity.</li>
 *   <li><b>Retention</b> — when {@code questDataRetentionDays} is above zero, rows whose
 *       last-updated stamp is older than that are pruned on first access per server session
 *       ({@link #pruneStale(int)}); only the number pruned is logged.</li>
 *   <li><b>Access</b> — {@link #exportPlayer(MinecraftServer, UUID)} returns exactly one player's
 *       own rows as JSON for a data-access request, and nobody else's.</li>
 * </ul>
 *
 * <p><b>Reward integrity (AUD-01).</b> Two more per-player records sit beside the progress rows and
 * share every one of the controls above (stored under the game UUID, exported, erased):
 * <ul>
 *   <li>the <b>paid ledger</b> — the quest ids whose rewards have already been paid. A completion
 *       pays only when its id is not yet in the ledger, so revoking, resetting or re-completing a
 *       quest can never pay it twice. {@code scope: server} quests keep a world-level paid set that
 *       holds no identifiers;</li>
 *   <li><b>revoked-until-changed</b> markers — the objective counters snapshotted when an operator
 *       revoked a quest. The engine may not re-complete a revoked quest until one of its counters
 *       actually differs from that snapshot, so a revoke is not undone by the next 1 Hz sweep.</li>
 * </ul>
 * After an erasure the erased UUID is additionally held in an in-memory set until that player
 * rejoins, so erasing an online player does not re-complete (and re-pay) everything within a second.
 */
public final class QuestProgressState extends SavedData {

    /** Stable, non-identifying label used for the storage file and recovery logs. */
    public static final String NAME = NeroQuestsCommon.MOD_ID + ":quest_progress";

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "quest_progress");

    public static final SavedDataType<QuestProgressState> TYPE =
            new SavedDataType<>(ID, QuestProgressState::new, codec(), null);

    /**
     * Upper bound on objective indices accepted by the increment methods. A quest never has this
     * many objectives; the cap simply stops a bad caller (or a malformed pack) from growing a
     * counter list without limit.
     */
    private static final int MAX_OBJECTIVES = 256;

    private static final long MILLIS_PER_DAY = 86_400_000L;

    /**
     * The server whose start-up retention sweep has already run, so the lazy check in {@link #get}
     * fires at most once per server instance (the periodic sweep is driven separately by
     * {@code QuestTriggers}). Written on the server thread; {@code volatile} only so an
     * integrated-server restart in the same JVM is seen promptly.
     */
    private static volatile MinecraftServer prunedFor;

    /** The server stopped (AUD-31): forget it, so the next server's start-up sweep runs and the old one is not kept reachable. */
    public static void serverStopped() {
        prunedFor = null;
    }

    private final Map<UUID, Map<Identifier, QuestProgress>> byPlayer = new LinkedHashMap<>();
    private final Map<UUID, Long> lastUpdated = new LinkedHashMap<>();
    private final Map<Identifier, QuestProgress> serverScope = new LinkedHashMap<>();

    /** Currency rewards owed to a player because a payout was refused; retried later. */
    private final Map<UUID, List<OwedReward>> owed = new LinkedHashMap<>();

    /** Per player: the UTC day of their daily-contract record and the contract ids done that day. */
    private final Map<UUID, Long> contractDay = new LinkedHashMap<>();
    private final Map<UUID, Set<Identifier>> contractsDone = new LinkedHashMap<>();

    /**
     * Once-per-world currency rewards already claimed, as {@code <quest id>#<reward index>}. World
     * data: no player identifiers.
     */
    private final Set<String> claimedOnce = new LinkedHashSet<>();

    /** The last request-key sequence handed out (world-wide, no identity). */
    private long rewardSequence;

    /** AUD-01 paid ledger: per player, the quest ids whose rewards have been paid. */
    private final Map<UUID, Set<Identifier>> paid = new LinkedHashMap<>();

    /** AUD-01 paid ledger for {@code scope: server} quests. World data: no player identifiers. */
    private final Set<Identifier> serverPaid = new LinkedHashSet<>();

    /** AUD-01 revoked-until-changed: per player, quest id → counter snapshot taken at revocation. */
    private final Map<UUID, Map<Identifier, List<Integer>>> revoked = new LinkedHashMap<>();

    /** The same for the shared {@code scope: server} section. */
    private final Map<Identifier, List<Integer>> serverRevoked = new LinkedHashMap<>();

    /**
     * Repeatable custom quests (D2): per player, quest id → epoch millis of the last paid completion. For a
     * repeatable quest this timestamp, not the paid set, decides whether a completion pays: once per
     * cooldown. Same controls as the ledger (saved under the game UUID, exported, erased).
     */
    private final Map<UUID, Map<Identifier, Long>> repeatPaid = new LinkedHashMap<>();

    /**
     * In memory only, never saved: players whose data was erased while this store was live. The
     * engine evaluates nobody in here until they rejoin, so an erasure is not immediately followed by
     * a wave of re-completions. Cleared per player by {@link #playerJoined(UUID)}.
     */
    private final Set<UUID> suppressed = new HashSet<>();

    /**
     * Told when an owed entry is created and when one is settled (paid or handed over, wholly or in part)
     * — the link module's {@code reward_owed} / {@code reward_claimed} events (G1). Never told about
     * erasure, pruning or the 64-cap drop. Implementations must be cheap and must not touch this store.
     */
    public interface OwedListener {
        /** {@code entry} was just added to {@code player}'s owed list. */
        void owed(UUID player, OwedReward entry);

        /** {@code delivered} of {@code entry}'s amount was just settled; {@code remaining} is left on it. */
        void settled(UUID player, OwedReward entry, long delivered, long remaining);
    }

    private static volatile OwedListener owedListener;

    /** Install the (single) owed listener; {@code null} removes it. */
    public static void setOwedListener(OwedListener listener) {
        owedListener = listener;
    }

    private static void notifyOwed(UUID player, OwedReward entry) {
        OwedListener listener = owedListener;
        if (listener == null) {
            return;
        }
        try {
            listener.owed(player, entry);
        } catch (RuntimeException e) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] An owed-reward listener failed.", e);
        }
    }

    private static void notifySettled(UUID player, OwedReward entry, long delivered, long remaining) {
        OwedListener listener = owedListener;
        if (listener == null || delivered <= 0L) {
            return;
        }
        try {
            listener.settled(player, entry, delivered, remaining);
        } catch (RuntimeException e) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] An owed-reward listener failed.", e);
        }
    }

    /** At most this many owed rewards are kept per player; the oldest is dropped beyond it. */
    private static final int MAX_OWED_PER_PLAYER = 64;

    /** Upper bound on distinct ids in each world completion counter (C3); beyond it new ids are not stored. */
    public static final int MAX_COMPLETION_COUNTERS = 4096;

    /**
     * C3: how many times each quest has been completed in this world (every player's completion counts
     * once; a server-scope quest's single completion counts once). World data: quest ids and counts only,
     * no player identifiers. Bounded by {@link #MAX_COMPLETION_COUNTERS}.
     */
    private final Map<Identifier, Long> questCompletions = new LinkedHashMap<>();

    /** C3: the same for chapters (a player completing the last quest of a chapter). */
    private final Map<Identifier, Long> chapterCompletions = new LinkedHashMap<>();

    public QuestProgressState() {
    }

    /**
     * The one store, on the overworld so it is always loaded. Runs the retention sweep on the
     * first call for a given server instance.
     */
    public static QuestProgressState get(MinecraftServer server) {
        QuestProgressState state = SavedDataRecovery.get(
                server.overworld(), TYPE, QuestProgressState::new, NAME);
        if (prunedFor != server) {
            prunedFor = server; // set first: pruning must never re-enter this sweep
            if (state.pruneStale(NeroQuestsConfig.RETENTION_DAYS.get()) > 0) {
                state.backupNow(server);
            }
        }
        return state;
    }

    /**
     * Refresh Core's last-known-good backup of this store now. Called straight after an erasure or a
     * retention purge: the backup is a second copy of the same player rows, and a POPIA/GDPR erasure has
     * to reach it in the same request (Core's {@code SavedDataRecovery} contract).
     */
    public void backupNow(MinecraftServer server) {
        SavedDataRecovery.backupNow(server.overworld(), TYPE, this, NAME, server.registryAccess());
    }

    // --- player scope -------------------------------------------------------

    /** A player's progress on one quest, or empty if they have never touched it. */
    public Optional<QuestProgress> progress(UUID player, Identifier quest) {
        Map<Identifier, QuestProgress> quests = byPlayer.get(player);
        return Optional.ofNullable(quests == null ? null : quests.get(quest));
    }

    /** The quest ids a player has completed (a copy; never null). */
    public Set<Identifier> completedQuests(UUID player) {
        Map<Identifier, QuestProgress> quests = byPlayer.get(player);
        if (quests == null || quests.isEmpty()) {
            return Set.of();
        }
        Set<Identifier> complete = new LinkedHashSet<>();
        quests.forEach((quest, progress) -> {
            if (progress.isComplete()) {
                complete.add(quest);
            }
        });
        return complete;
    }

    /** Whether a player has completed a quest. */
    public boolean isComplete(UUID player, Identifier quest) {
        return progress(player, quest).map(QuestProgress::isComplete).orElse(Boolean.FALSE);
    }

    /**
     * Every quest row stored for one player, as an immutable copy — the snapshot the client sync
     * sends to that player, and nobody else's data (POPIA/GDPR).
     */
    public Map<Identifier, QuestProgress> playerProgress(UUID player) {
        Map<Identifier, QuestProgress> quests = byPlayer.get(player);
        return quests == null || quests.isEmpty() ? Map.of() : Map.copyOf(quests);
    }

    /**
     * Adds {@code amount} to one objective's counter for a player, saturating at
     * {@code [0, Integer.MAX_VALUE]}.
     *
     * @param objectiveIndex the objective's position in the quest definition; out-of-range indices
     *                       are ignored
     * @return the counter's value after the change (0 for an ignored index)
     */
    public int incrementObjective(UUID player, Identifier quest, int objectiveIndex, int amount) {
        return incrementObjective(player, quest, objectiveIndex, amount, 0);
    }

    /**
     * {@link #incrementObjective(UUID, Identifier, int, int)}, stamping a row that has no objective
     * fingerprint yet with {@code fingerprint} (AUD-17; {@code 0} stamps nothing).
     */
    public int incrementObjective(UUID player, Identifier quest, int objectiveIndex, int amount, int fingerprint) {
        if (objectiveIndex < 0 || objectiveIndex >= MAX_OBJECTIVES) {
            return 0;
        }
        Map<Identifier, QuestProgress> quests = byPlayer.computeIfAbsent(player, i -> new LinkedHashMap<>());
        QuestProgress current = quests.getOrDefault(quest, QuestProgress.EMPTY);
        int updatedValue = clampedSum(current.counter(objectiveIndex), amount);
        QuestProgress updated = stamp(current.withCounter(objectiveIndex, updatedValue), fingerprint);
        if (updated != current) {
            quests.put(quest, updated);
            touch(player);
        } else if (quests.isEmpty()) {
            byPlayer.remove(player); // nothing was stored and nothing changed
        }
        return updatedValue;
    }

    /**
     * Marks a quest complete for a player.
     *
     * @param epochMillis the completion time; anything below 1 records "now"
     * @return {@code true} if this newly completed the quest
     */
    public boolean markComplete(UUID player, Identifier quest, long epochMillis) {
        Map<Identifier, QuestProgress> quests = byPlayer.computeIfAbsent(player, i -> new LinkedHashMap<>());
        QuestProgress current = quests.getOrDefault(quest, QuestProgress.EMPTY);
        if (current.isComplete()) {
            return false;
        }
        quests.put(quest, current.withCompletedAt(epochMillis > 0L ? epochMillis : System.currentTimeMillis()));
        touch(player);
        return true;
    }

    // --- server scope -------------------------------------------------------

    /** The shared progress on a {@code scope: server} quest, or empty if untouched. */
    public Optional<QuestProgress> serverProgress(Identifier quest) {
        return Optional.ofNullable(serverScope.get(quest));
    }

    /** The {@code scope: server} quest ids completed for the whole world (a copy). */
    public Set<Identifier> completedServerQuests() {
        Set<Identifier> complete = new LinkedHashSet<>();
        serverScope.forEach((quest, progress) -> {
            if (progress.isComplete()) {
                complete.add(quest);
            }
        });
        return complete;
    }

    /**
     * Every shared {@code scope: server} quest row, as an immutable copy. Identifier-free: this
     * section belongs to the world, so it is safe to send to every client.
     */
    public Map<Identifier, QuestProgress> allServerProgress() {
        return serverScope.isEmpty() ? Map.of() : Map.copyOf(serverScope);
    }

    /** Whether a {@code scope: server} quest is complete for the whole world. */
    public boolean isServerComplete(Identifier quest) {
        QuestProgress progress = serverScope.get(quest);
        return progress != null && progress.isComplete();
    }

    /** {@link #incrementObjective} for the shared server-scoped section. */
    public int incrementServerObjective(Identifier quest, int objectiveIndex, int amount) {
        return incrementServerObjective(quest, objectiveIndex, amount, 0);
    }

    /** {@link #incrementServerObjective(Identifier, int, int)} with a fingerprint stamp (AUD-17). */
    public int incrementServerObjective(Identifier quest, int objectiveIndex, int amount, int fingerprint) {
        if (objectiveIndex < 0 || objectiveIndex >= MAX_OBJECTIVES) {
            return 0;
        }
        QuestProgress current = serverScope.getOrDefault(quest, QuestProgress.EMPTY);
        int updatedValue = clampedSum(current.counter(objectiveIndex), amount);
        QuestProgress updated = stamp(current.withCounter(objectiveIndex, updatedValue), fingerprint);
        if (updated != current) {
            serverScope.put(quest, updated);
            setDirty();
        }
        return updatedValue;
    }

    /** {@link #markComplete} for the shared server-scoped section. */
    public boolean markServerComplete(Identifier quest, long epochMillis) {
        QuestProgress current = serverScope.getOrDefault(quest, QuestProgress.EMPTY);
        if (current.isComplete()) {
            return false;
        }
        serverScope.put(quest, current.withCompletedAt(epochMillis > 0L ? epochMillis : System.currentTimeMillis()));
        setDirty();
        return true;
    }

    // --- admin edits ---------------------------------------------------------

    /**
     * Drops one player's whole row for one quest — its completion stamp <em>and</em> every objective
     * counter — so the quest reads as never started again. Backs {@code /neroquests revoke} and
     * {@code /neroquests reset &lt;player&gt; &lt;quest&gt;}, which are one behaviour under two names.
     *
     * <p>This only forgets progress. Rewards already paid out are not clawed back: they live in
     * inventories, XP, Core gates, currency and reputation, none of which this store owns.
     *
     * @return {@code true} if a row was actually removed
     */
    public boolean resetQuest(UUID player, Identifier quest) {
        Map<Identifier, QuestProgress> quests = byPlayer.get(player);
        if (quests == null || quests.remove(quest) == null) {
            return false;
        }
        if (quests.isEmpty()) {
            byPlayer.remove(player);
        }
        touch(player);
        return true;
    }

    /** {@link #resetQuest} for the shared {@code scope: server} section (no player is involved). */
    public boolean resetServerQuest(Identifier quest) {
        if (serverScope.remove(quest) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    // --- AUD-17: objective fingerprints and orphan rows -------------------------

    /** What one {@link #reconcile} changed: counts only. */
    public record ReconcileResult(int reset, int stamped, int orphaned, int pruned) {

        /** Whether anything changed at all. */
        public boolean changed() {
            return reset + stamped + orphaned + pruned > 0;
        }
    }

    /**
     * Bring every stored row in line with the current definitions (AUD-17), once per definitions load:
     *
     * <ul>
     *   <li>a row without a fingerprint (written before fingerprints existed) is stamped with the
     *       current one — migration, nothing is reset;</li>
     *   <li>an <b>incomplete</b> row whose fingerprint differs has its counters cleared (they were
     *       counted against different objectives) and any revoked marker on it dropped;</li>
     *   <li>a <b>complete</b> row whose fingerprint differs stays complete and is re-stamped; the
     *       paid ledger already stops any second payout;</li>
     *   <li>a row whose quest is no longer defined is an <b>orphan</b>: stamped with the moment it
     *       became one, kept inert, and deleted once it has been an orphan for longer than
     *       {@code retentionDays}. With retention {@code 0} orphans are kept inert indefinitely, so a
     *       quest that comes back finds its row (Core's own retention still erases whole players).
     *       Paid-ledger entries are never pruned here, so a returning quest cannot pay twice.</li>
     * </ul>
     *
     * Never touches a player's activity stamp: this is maintenance, not activity.
     *
     * @param fingerprints  quest id → current objective fingerprint, for every defined quest
     * @param retentionDays {@code questDataRetentionDays}
     * @param now           epoch millis
     */
    public ReconcileResult reconcile(Map<Identifier, Integer> fingerprints, int retentionDays, long now) {
        int[] counts = new int[4];
        long threshold = retentionDays > 0 ? now - retentionDays * MILLIS_PER_DAY : Long.MIN_VALUE;
        for (Map.Entry<UUID, Map<Identifier, QuestProgress>> entry : byPlayer.entrySet()) {
            UUID player = entry.getKey();
            reconcileSection(entry.getValue(), fingerprints, threshold, now, counts,
                    quest -> clearRevocation(player, quest));
        }
        byPlayer.values().removeIf(Map::isEmpty);
        reconcileSection(serverScope, fingerprints, threshold, now, counts, quest -> clearRevocation(null, quest));
        ReconcileResult result = new ReconcileResult(counts[0], counts[1], counts[2], counts[3]);
        if (result.changed()) {
            setDirty();
        }
        return result;
    }

    private static void reconcileSection(Map<Identifier, QuestProgress> rows, Map<Identifier, Integer> fingerprints,
                                         long orphanThreshold, long now, int[] counts,
                                         Consumer<Identifier> dropRevocation) {
        Iterator<Map.Entry<Identifier, QuestProgress>> it = rows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Identifier, QuestProgress> row = it.next();
            QuestProgress progress = row.getValue();
            Integer current = fingerprints.get(row.getKey());
            if (current == null) {
                if (progress.orphanSince() <= 0L) {
                    row.setValue(progress.withOrphanSince(now));
                    counts[2]++;
                } else if (progress.orphanSince() < orphanThreshold) {
                    it.remove();
                    dropRevocation.accept(row.getKey());
                    counts[3]++;
                }
                continue;
            }
            QuestProgress updated = progress.withOrphanSince(0L);
            int fingerprint = current.intValue();
            if (updated.fingerprint() == 0) {
                updated = updated.withFingerprint(fingerprint);
                counts[1]++;
            } else if (updated.fingerprint() != fingerprint) {
                if (!updated.isComplete()) {
                    updated = updated.withoutCounters();
                    dropRevocation.accept(row.getKey());
                    counts[0]++;
                } else {
                    counts[1]++;
                }
                updated = updated.withFingerprint(fingerprint);
            }
            if (updated != progress) {
                row.setValue(updated);
            }
        }
    }

    private static QuestProgress stamp(QuestProgress progress, int fingerprint) {
        return fingerprint != 0 && progress.fingerprint() == 0 ? progress.withFingerprint(fingerprint) : progress;
    }

    // --- D2: repeatable custom quests and deleted quests -------------------------

    /** Epoch millis of the player's last paid completion of a repeatable quest, or {@code 0}. */
    public long lastRepeatPaid(UUID player, Identifier quest) {
        Map<Identifier, Long> quests = repeatPaid.get(player);
        Long at = quests == null ? null : quests.get(quest);
        return at == null ? 0L : at.longValue();
    }

    /** Record a paid completion of a repeatable quest at {@code epochMillis}. */
    public void markRepeatPaid(UUID player, Identifier quest, long epochMillis) {
        repeatPaid.computeIfAbsent(player, i -> new LinkedHashMap<>()).put(quest, Long.valueOf(epochMillis));
        touch(player);
    }

    /**
     * Re-arm a completed repeatable quest for a player once its cooldown has passed: the row is dropped (so it
     * can be completed again) and any revoked marker cleared. The repeat timestamp stays — it is what stops
     * a second payout inside one cooldown.
     *
     * @return whether a row was re-armed
     */
    public boolean rearmRepeatable(UUID player, Identifier quest) {
        boolean changed = resetQuest(player, quest);
        clearRevocation(player, quest);
        return changed;
    }

    /**
     * A quest was deleted (a custom quest, D3): drop every stored trace of it — every player's progress row,
     * paid-ledger entry, revoked marker and repeat timestamp, the shared row and its world completion count.
     * Owed rewards already earned from it are kept (they belong to the players).
     *
     * @return how many player records held something for it
     */
    public int pruneQuest(Identifier quest) {
        int players = 0;
        Set<UUID> everyone = new LinkedHashSet<>(byPlayer.keySet());
        everyone.addAll(paid.keySet());
        everyone.addAll(revoked.keySet());
        everyone.addAll(repeatPaid.keySet());
        for (UUID player : everyone) {
            boolean touched = removeFrom(byPlayer, player, quest);
            touched |= removeFromSet(paid, player, quest);
            touched |= removeFrom(revoked, player, quest);
            touched |= removeFrom(repeatPaid, player, quest);
            if (touched) {
                players++;
            }
        }
        boolean changed = players > 0;
        changed |= serverScope.remove(quest) != null;
        changed |= serverPaid.remove(quest);
        changed |= serverRevoked.remove(quest) != null;
        changed |= questCompletions.remove(quest) != null;
        if (changed) {
            setDirty();
        }
        return players;
    }

    private static <V> boolean removeFrom(Map<UUID, Map<Identifier, V>> map, UUID player, Identifier quest) {
        Map<Identifier, V> rows = map.get(player);
        if (rows == null || rows.remove(quest) == null) {
            return false;
        }
        if (rows.isEmpty()) {
            map.remove(player);
        }
        return true;
    }

    private static boolean removeFromSet(Map<UUID, Set<Identifier>> map, UUID player, Identifier quest) {
        Set<Identifier> rows = map.get(player);
        if (rows == null || !rows.remove(quest)) {
            return false;
        }
        if (rows.isEmpty()) {
            map.remove(player);
        }
        return true;
    }

    // --- AUD-01: paid ledger and revoked-until-changed ------------------------

    /** Whether a player has already been paid this quest's rewards. */
    public boolean isPaid(UUID player, Identifier quest) {
        Set<Identifier> quests = paid.get(player);
        return quests != null && quests.contains(quest);
    }

    /**
     * Record that a player is being paid this quest's rewards.
     *
     * @return {@code true} if the quest was not yet in the ledger, i.e. the rewards may be paid now
     */
    public boolean markPaid(UUID player, Identifier quest) {
        if (!paid.computeIfAbsent(player, i -> new LinkedHashSet<>()).add(quest)) {
            return false;
        }
        setDirty();
        return true;
    }

    /** The quest ids in a player's paid ledger (a copy; never null). */
    public Set<Identifier> paidQuests(UUID player) {
        Set<Identifier> quests = paid.get(player);
        return quests == null ? Set.of() : Set.copyOf(quests);
    }

    /** Whether a {@code scope: server} quest's rewards have already been paid. */
    public boolean isServerPaid(Identifier quest) {
        return serverPaid.contains(quest);
    }

    /** {@link #markPaid} for a {@code scope: server} quest. */
    public boolean markServerPaid(Identifier quest) {
        if (!serverPaid.add(quest)) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * The revoked-until-changed snapshot for a quest: per objective, the counter clamped to its
     * target — or the target itself when the quest was complete, because a completed quest counts
     * every objective as satisfied (an admin {@code grant} never filled the counters in).
     *
     * @param progress the row being revoked
     * @param targets  each objective's target, in definition order
     */
    public static List<Integer> revocationSnapshot(QuestProgress progress, List<Integer> targets) {
        List<Integer> snapshot = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) {
            int target = Math.max(1, targets.get(index).intValue());
            snapshot.add(Integer.valueOf(progress.isComplete()
                    ? target
                    : Math.min(Math.max(progress.counter(index), 0), target)));
        }
        return snapshot;
    }

    /**
     * {@code /neroquests revoke} for a player-scope quest: drops the progress row, marks the quest
     * revoked-until-changed with {@code snapshot}, and — only with {@code repay} — removes it from the
     * paid ledger so the next genuine completion pays again.
     *
     * @return {@code true} if anything changed
     */
    public boolean revokeQuest(UUID player, Identifier quest, List<Integer> snapshot, boolean repay) {
        boolean changed = resetQuest(player, quest);
        if (changed) {
            revoked.computeIfAbsent(player, i -> new LinkedHashMap<>()).put(quest, List.copyOf(snapshot));
        }
        if (repay) {
            Set<Identifier> quests = paid.get(player);
            if (quests != null && quests.remove(quest)) {
                if (quests.isEmpty()) {
                    paid.remove(player);
                }
                changed = true;
            }
            // D2: a repeatable quest's next completion pays again too, whatever its cooldown.
            changed |= removeFrom(repeatPaid, player, quest);
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /** {@link #revokeQuest} for the shared {@code scope: server} section. */
    public boolean revokeServerQuest(Identifier quest, List<Integer> snapshot, boolean repay) {
        boolean changed = resetServerQuest(quest);
        if (changed) {
            serverRevoked.put(quest, List.copyOf(snapshot));
        }
        if (repay && serverPaid.remove(quest)) {
            changed = true;
        }
        if (repay) {
            // AUD-06: each recipient of a server quest is entered in their own ledger under the quest id;
            // a repay lets every one of them be paid again.
            for (Iterator<Map.Entry<UUID, Set<Identifier>>> it = paid.entrySet().iterator(); it.hasNext();) {
                Set<Identifier> quests = it.next().getValue();
                if (quests.remove(quest)) {
                    changed = true;
                    if (quests.isEmpty()) {
                        it.remove();
                    }
                }
            }
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /**
     * Whether a revoked quest is still held back. {@code counters} are the quest's current
     * per-objective counters clamped to target, with {@code -1} for an objective that must not be
     * compared (content missing here, so it cannot change). The hold lifts — and the marker is
     * removed — the first time any compared counter differs from the snapshot.
     *
     * @return {@code true} if the quest must not complete yet
     */
    public boolean revocationHolds(UUID player, Identifier quest, int[] counters) {
        Map<Identifier, List<Integer>> quests = revoked.get(player);
        if (quests == null) {
            return false;
        }
        List<Integer> snapshot = quests.get(quest);
        if (snapshot == null) {
            return false;
        }
        if (sameAsSnapshot(snapshot, counters)) {
            return true;
        }
        quests.remove(quest);
        if (quests.isEmpty()) {
            revoked.remove(player);
        }
        setDirty();
        return false;
    }

    /** {@link #revocationHolds} for the shared {@code scope: server} section. */
    public boolean serverRevocationHolds(Identifier quest, int[] counters) {
        List<Integer> snapshot = serverRevoked.get(quest);
        if (snapshot == null) {
            return false;
        }
        if (sameAsSnapshot(snapshot, counters)) {
            return true;
        }
        serverRevoked.remove(quest);
        setDirty();
        return false;
    }

    /** Whether a quest is currently revoked-until-changed for a player (or the world, when {@code player} is null). */
    public boolean isRevoked(UUID player, Identifier quest) {
        if (player == null) {
            return serverRevoked.containsKey(quest);
        }
        Map<Identifier, List<Integer>> quests = revoked.get(player);
        return quests != null && quests.containsKey(quest);
    }

    /** Drop a revoked-until-changed marker (an operator {@code grant} overrides it). */
    public void clearRevocation(UUID player, Identifier quest) {
        if (player == null) {
            if (serverRevoked.remove(quest) != null) {
                setDirty();
            }
            return;
        }
        Map<Identifier, List<Integer>> quests = revoked.get(player);
        if (quests != null && quests.remove(quest) != null) {
            if (quests.isEmpty()) {
                revoked.remove(player);
            }
            setDirty();
        }
    }

    private static boolean sameAsSnapshot(List<Integer> snapshot, int[] counters) {
        int length = Math.max(snapshot.size(), counters.length);
        for (int index = 0; index < length; index++) {
            int now = index < counters.length ? counters[index] : 0;
            if (now < 0) {
                continue; // not comparable this pass (content missing here)
            }
            int then = index < snapshot.size() ? snapshot.get(index).intValue() : 0;
            if (now != then) {
                return false;
            }
        }
        return true;
    }

    // --- erasure suppression and activity ---------------------------------------

    /** Whether the engine must leave this player alone until they rejoin (they were just erased). */
    public boolean isSuppressed(UUID player) {
        return suppressed.contains(player);
    }

    /**
     * A player joined: lift any post-erasure suppression and stamp them active, so retention never
     * purges someone who plays daily but has nothing left to progress (AUD-06 retention half).
     */
    public void playerJoined(UUID player) {
        suppressed.remove(player);
        touch(player);
    }

    // --- payouts: request keys, owed rewards, once-per-world claims ---------

    /**
     * A fresh request key for one payout attempt: {@code <prefix><sequence>:<source>}, at most 64
     * characters, never holding a player id or name. The sequence is world-wide and strictly
     * increasing, seeded from the clock so it stays unique even if this store is ever reset.
     */
    public String nextRequestKey(String prefix, Identifier source) {
        rewardSequence = Math.max(rewardSequence + 1L, System.currentTimeMillis());
        setDirty();
        String base = prefix + rewardSequence;
        String full = base + ":" + source;
        if (full.length() > 64) {
            full = full.substring(0, 64);
        }
        // The economy refuses anything that could pass for a player id; fall back to the bare
        // sequence, which is unique on its own.
        if (!full.matches("[A-Za-z0-9_.:/#-]{1,64}") || full.matches(".*[0-9a-fA-F]{32}.*")) {
            return base;
        }
        return full;
    }

    /** Reason code of an owed escrow refund to a custom quest's author (J2, RT-10/RT-11). */
    public static final String REASON_ESCROW_REFUND = "escrow_refund";

    /**
     * Record a reward as owed to a player (kept until paid, erased or pruned with their data). Beyond
     * {@value #MAX_OWED_PER_PLAYER} entries one is dropped to make room; the caller tells the player.
     *
     * <p>J2 (RT-10): which one is dropped depends on who is asking. Custom-quest rewards (author-made, see
     * {@link #customReward}) go first, oldest first. A custom-quest reward may only push out another custom-quest
     * reward: when the queue holds nothing else, the <em>new</em> custom entry is the one dropped, so an editor can
     * never evict a player's spine, pack or contract rewards or an author's escrow refund. Any other entry pushes
     * out the oldest custom-quest reward, else the oldest entry (as before).
     *
     * @return how many entries were dropped (an old one, or the new custom entry itself)
     */
    public int addOwed(UUID player, OwedReward reward) {
        List<OwedReward> list = owed.computeIfAbsent(player, i -> new ArrayList<>());
        int dropped = 0;
        while (list.size() >= MAX_OWED_PER_PLAYER) {
            int victim = evictionIndex(list, customReward(reward));
            if (victim < 0) {
                return dropped + 1; // the queue holds only protected entries: the custom reward is not kept
            }
            list.remove(victim);
            dropped++;
        }
        list.add(reward);
        touch(player);
        notifyOwed(player, reward);
        return dropped;
    }

    /** Whether an owed entry is a custom-quest reward (author-made content, not an escrow refund). */
    public static boolean customReward(OwedReward entry) {
        return QuestSource.of(entry.source()) == QuestSource.CUSTOM && !REASON_ESCROW_REFUND.equals(entry.reason());
    }

    /** The entry to drop for room: the oldest custom-quest reward, else (only for a non-custom entry) the oldest. */
    static int evictionIndex(List<OwedReward> list, boolean incomingCustom) {
        for (int index = 0; index < list.size(); index++) {
            if (customReward(list.get(index))) {
                return index;
            }
        }
        return incomingCustom || list.isEmpty() ? -1 : 0;
    }

    /** J2 (RT-10): whether a player's owed queue is at its cap (custom manual completions are then refused). */
    public boolean isOwedFull(UUID player) {
        List<OwedReward> list = owed.get(player);
        return list != null && list.size() >= MAX_OWED_PER_PLAYER;
    }

    /**
     * J2 (RT-17): take the entry keyed {@code key} out of the queue <em>before</em> anything is handed over (the
     * listener is told once the hand-over is settled, by {@link #settleTaken}).
     *
     * @return its index, or -1 when no such entry exists
     */
    public int takeOwedByKey(UUID player, String key) {
        List<OwedReward> list = owed.get(player);
        if (list == null || key == null || key.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < list.size(); index++) {
            if (key.equals(list.get(index).requestKey())) {
                list.remove(index);
                if (list.isEmpty()) {
                    owed.remove(player);
                }
                setDirty();
                return index;
            }
        }
        return -1;
    }

    /**
     * J2 (RT-17): finish a {@link #takeOwedByKey}: put back what was not handed over ({@code rest}, null or
     * empty when everything went) at its old place, and tell the listener what was delivered. Counts as activity.
     */
    public void settleTaken(UUID player, int index, OwedReward taken, OwedReward rest) {
        long remaining = rest == null ? 0L : Math.max(0L, rest.amount());
        if (remaining > 0L) {
            List<OwedReward> list = owed.computeIfAbsent(player, i -> new ArrayList<>());
            list.add(Math.max(0, Math.min(index, list.size())), rest);
        }
        touch(player);
        notifySettled(player, taken, taken.amount() - remaining, remaining);
    }

    /** The rewards owed to a player (a copy; never null). */
    public List<OwedReward> owed(UUID player) {
        List<OwedReward> list = owed.get(player);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Whether anything is owed to a player. */
    public boolean hasOwed(UUID player) {
        List<OwedReward> list = owed.get(player);
        return list != null && !list.isEmpty();
    }

    /**
     * Replace the owed entry whose request key is {@code key} with {@code next}, or remove it when
     * {@code next} is null (AUD-05: delivery is idempotent by entry key). Counts as player activity.
     *
     * @return whether an entry with that key existed
     */
    public boolean replaceOwedByKey(UUID player, String key, OwedReward next) {
        List<OwedReward> list = owed.get(player);
        if (list == null || key == null || key.isEmpty()) {
            return false;
        }
        for (int index = 0; index < list.size(); index++) {
            if (key.equals(list.get(index).requestKey())) {
                OwedReward previous = list.get(index);
                if (next == null) {
                    list.remove(index);
                    if (list.isEmpty()) {
                        owed.remove(player);
                    }
                } else {
                    list.set(index, next);
                }
                touch(player);
                long remaining = next == null ? 0L : next.amount();
                notifySettled(player, previous, previous.amount() - remaining, remaining);
                return true;
            }
        }
        return false;
    }

    /** Replace one owed entry (matched by identity of its request key and source) after a retry. */
    public void updateOwed(UUID player, OwedReward previous, OwedReward next) {
        updateOwed(player, previous, next, true);
    }

    /**
     * {@link #updateOwed(UUID, OwedReward, OwedReward)}; with {@code activity} false the player's
     * retention stamp is left alone — a refused automatic retry is not the player being active.
     */
    public void updateOwed(UUID player, OwedReward previous, OwedReward next, boolean activity) {
        List<OwedReward> list = owed.get(player);
        if (list == null) {
            return;
        }
        int index = list.indexOf(previous);
        if (index < 0) {
            return;
        }
        if (next == null) {
            list.remove(index);
            if (list.isEmpty()) {
                owed.remove(player);
            }
        } else {
            list.set(index, next);
        }
        if (activity) {
            touch(player);
        } else {
            setDirty();
        }
        if (next == null) {
            // Only a paid retry/claim removes an entry this way (a refusal replaces it).
            notifySettled(player, previous, previous.amount(), 0L);
        }
    }

    /**
     * Claim a once-per-world reward slot.
     *
     * @return {@code true} if this call claimed it, {@code false} if it was already claimed
     */
    public boolean claimOnce(String slot) {
        if (!claimedOnce.add(slot)) {
            return false;
        }
        setDirty();
        return true;
    }

    /** Whether a once-per-world slot has been claimed. */
    public boolean isClaimedOnce(String slot) {
        return claimedOnce.contains(slot);
    }

    /** Give a once-per-world slot back (its payout was refused before anything moved). */
    public void releaseOnce(String slot) {
        if (claimedOnce.remove(slot)) {
            setDirty();
        }
    }

    // --- daily contracts ------------------------------------------------------

    /** Whether a player has already done contract {@code contract} on UTC day {@code day}. */
    public boolean contractDone(UUID player, long day, Identifier contract) {
        Long recorded = contractDay.get(player);
        if (recorded == null || recorded.longValue() != day) {
            return false;
        }
        Set<Identifier> done = contractsDone.get(player);
        return done != null && done.contains(contract);
    }

    /** Record contract {@code contract} as done by a player on UTC day {@code day}; older days are forgotten. */
    public void markContractDone(UUID player, long day, Identifier contract) {
        Long recorded = contractDay.get(player);
        if (recorded == null || recorded.longValue() != day) {
            contractDay.put(player, Long.valueOf(day));
            contractsDone.put(player, new LinkedHashSet<>());
        }
        contractsDone.computeIfAbsent(player, i -> new LinkedHashSet<>()).add(contract);
        touch(player);
    }

    // --- privacy: erasure, retention, export --------------------------------

    /**
     * POPIA/GDPR erasure: drop everything stored for a player — every quest row and the
     * last-updated stamp. Shared server-scoped progress is deliberately untouched: it belongs to
     * the world, not to any player, and holds no identifiers. Never logs player identity.
     */
    public void forgetPlayer(UUID player) {
        boolean changed = byPlayer.remove(player) != null;
        changed |= lastUpdated.remove(player) != null;
        changed |= owed.remove(player) != null;
        changed |= contractDay.remove(player) != null;
        changed |= contractsDone.remove(player) != null;
        changed |= paid.remove(player) != null;
        changed |= revoked.remove(player) != null;
        changed |= repeatPaid.remove(player) != null;
        // Not saved, and cleared when they next join: without it an online player's measured quests
        // would re-complete (and, the ledger being erased too, re-pay) on the next sweep.
        suppressed.add(player);
        if (changed) {
            setDirty();
        }
    }

    /**
     * POPIA/GDPR retention: purge every player whose progress has not changed in {@code days}
     * days. A value of {@code 0} or less disables the sweep, leaving retention entirely to Core's
     * own purge-inactive flow (which reaches this store through the registered eraser).
     *
     * @return how many player records were purged
     */
    public int pruneStale(int days) {
        return pruneStale(days, Set.of());
    }

    /**
     * {@link #pruneStale(int)}, never touching {@code active} (the players online right now, who are
     * stamped active first). Driven once per in-game day by {@code QuestTriggers}, as well as once at
     * start-up, so a long-running server still applies retention.
     */
    public int pruneStale(int days, Collection<UUID> active) {
        if (days <= 0) {
            return 0;
        }
        for (UUID player : active) {
            touch(player);
        }
        long threshold = System.currentTimeMillis() - days * MILLIS_PER_DAY;
        List<UUID> stale = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : lastUpdated.entrySet()) {
            // Rows with no usable stamp are left alone rather than deleted on a guess.
            if (entry.getValue() != null && entry.getValue() > 0L && entry.getValue() < threshold
                    && !active.contains(entry.getKey())) {
                stale.add(entry.getKey());
            }
        }
        for (UUID player : stale) {
            forgetPlayer(player);
            suppressed.remove(player); // retention only reaches offline players; nothing to suppress
        }
        if (!stale.isEmpty()) {
            // Count only — never which players.
            NeroQuestsCommon.LOGGER.info(
                    "[NeroQuests] Retention: purged quest progress for {} player record(s) inactive for "
                            + "more than {} day(s).", stale.size(), days);
        }
        return stale.size();
    }

    /**
     * A data-access export of exactly one player's own quest progress: their quest ids, objective
     * counters and timestamps, and nothing else. Shared server-scoped progress and every other
     * player's rows are intentionally absent. Mirrors Core's {@code MaterialMilestones.exportPlayer}.
     */
    public static JsonObject exportPlayer(MinecraftServer server, UUID player) {
        return get(server).export(player);
    }

    /** The instance-level body of {@link #exportPlayer(MinecraftServer, UUID)}. */
    public JsonObject export(UUID player) {
        JsonObject root = new JsonObject();
        root.addProperty("last_updated", lastUpdated.getOrDefault(player, 0L));
        JsonObject quests = new JsonObject();
        byPlayer.getOrDefault(player, Map.of()).forEach((quest, progress) -> {
            JsonObject row = new JsonObject();
            JsonArray counters = new JsonArray();
            progress.counters().forEach(counters::add);
            row.add("counters", counters);
            row.addProperty("completed_at", progress.completedAt());
            row.addProperty("complete", progress.isComplete());
            quests.add(quest.toString(), row);
        });
        root.add("quests", quests);
        JsonArray owedRows = new JsonArray();
        for (OwedReward reward : owed.getOrDefault(player, List.of())) {
            JsonObject row = new JsonObject();
            row.addProperty("type", reward.kind().serialName());
            row.addProperty("source", reward.source().toString());
            switch (reward.kind()) {
                case ITEM -> {
                    row.addProperty("item", reward.item().toString());
                    row.addProperty("count", reward.amount());
                    if (!reward.components().isEmpty()) {
                        row.addProperty("components", reward.components().toString());
                    }
                }
                case XP -> row.addProperty("amount", reward.amount());
                case CURRENCY -> {
                    row.addProperty("currency", reward.currency().toString());
                    row.addProperty("amount", reward.amount());
                }
            }
            row.addProperty("reason", reward.reason());
            row.addProperty("created_at", reward.createdAt());
            row.addProperty("attempts", reward.attempts());
            row.addProperty("permanent", reward.permanent());
            owedRows.add(row);
        }
        root.add("owed_rewards", owedRows);
        JsonArray paidRows = new JsonArray();
        paid.getOrDefault(player, Set.of()).forEach(id -> paidRows.add(id.toString()));
        root.add("paid_quests", paidRows);
        JsonArray revokedRows = new JsonArray();
        revoked.getOrDefault(player, Map.of()).keySet().forEach(id -> revokedRows.add(id.toString()));
        root.add("revoked_quests", revokedRows);
        JsonArray repeatRows = new JsonArray();
        repeatPaid.getOrDefault(player, Map.of()).forEach((id, at) -> {
            JsonObject row = new JsonObject();
            row.addProperty("quest", id.toString());
            row.addProperty("last_paid_at", at.longValue());
            repeatRows.add(row);
        });
        root.add("repeat_paid", repeatRows);
        if (contractDay.containsKey(player)) {
            JsonObject contracts = new JsonObject();
            contracts.addProperty("day", contractDay.get(player));
            JsonArray done = new JsonArray();
            contractsDone.getOrDefault(player, Set.of()).forEach(id -> done.add(id.toString()));
            contracts.add("done", done);
            root.add("daily_contracts", contracts);
        }
        return root;
    }

    // --- internals ----------------------------------------------------------

    /** Stamps a player's row as just-changed (retention input) and marks the store dirty. */
    private void touch(UUID player) {
        lastUpdated.put(player, System.currentTimeMillis());
        setDirty();
    }

    private static int clampedSum(int current, int amount) {
        long sum = (long) current + (long) amount;
        if (sum < 0L) {
            return 0;
        }
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    // --- world completion counters (C3) --------------------------------------

    /** Count one completion of {@code quest} in this world; returns the new world count (at least 1). */
    public long recordQuestCompletion(Identifier quest) {
        return bump(questCompletions, quest);
    }

    /** Count one completion of {@code chapter} in this world; returns the new world count (at least 1). */
    public long recordChapterCompletion(Identifier chapter) {
        return bump(chapterCompletions, chapter);
    }

    /** How many times {@code quest} has been completed in this world. */
    public long questCompletions(Identifier quest) {
        return questCompletions.getOrDefault(quest, Long.valueOf(0L)).longValue();
    }

    /** How many times {@code chapter} has been completed in this world. */
    public long chapterCompletions(Identifier chapter) {
        return chapterCompletions.getOrDefault(chapter, Long.valueOf(0L)).longValue();
    }

    private long bump(Map<Identifier, Long> counters, Identifier id) {
        Long current = counters.get(id);
        if (current == null && counters.size() >= MAX_COMPLETION_COUNTERS) {
            return 1L; // bounded: an id beyond the cap is reported as a first completion, never stored
        }
        long next = current == null ? 1L : (current.longValue() == Long.MAX_VALUE ? Long.MAX_VALUE : current + 1L);
        counters.put(id, Long.valueOf(next));
        setDirty();
        return next;
    }

    // --- persistence (same SavedDataType + Codec pattern as Core) -----------

    /** One world completion counter (C3): an id and its count. */
    private record CountRow(Identifier id, long count) {
        static final Codec<CountRow> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Identifier.CODEC.fieldOf("id").forGetter(CountRow::id),
                Codec.LONG.optionalFieldOf("count", 0L).forGetter(CountRow::count)
        ).apply(inst, CountRow::new));

        static List<CountRow> of(Map<Identifier, Long> counters) {
            List<CountRow> rows = new ArrayList<>(counters.size());
            counters.forEach((id, count) -> rows.add(new CountRow(id, count.longValue())));
            return rows;
        }

        static void load(List<CountRow> rows, Map<Identifier, Long> into) {
            for (CountRow row : rows) {
                if (row.count() > 0L && into.size() < MAX_COMPLETION_COUNTERS) {
                    into.put(row.id(), Long.valueOf(row.count()));
                }
            }
        }
    }

    private record QuestRow(Identifier quest, List<Integer> counters, long completedAt, int fingerprint,
                            long orphanSince) {
        static final Codec<QuestRow> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Identifier.CODEC.fieldOf("quest").forGetter(QuestRow::quest),
                Codec.INT.listOf().optionalFieldOf("counters", List.of()).forGetter(QuestRow::counters),
                Codec.LONG.optionalFieldOf("completed_at", 0L).forGetter(QuestRow::completedAt),
                Codec.INT.optionalFieldOf("fp", 0).forGetter(QuestRow::fingerprint),
                Codec.LONG.optionalFieldOf("orphan_since", 0L).forGetter(QuestRow::orphanSince)
        ).apply(inst, QuestRow::new));

        static QuestRow of(Identifier quest, QuestProgress progress) {
            return new QuestRow(quest, progress.counters(), progress.completedAt(), progress.fingerprint(),
                    progress.orphanSince());
        }

        QuestProgress toProgress() {
            return new QuestProgress(counters, completedAt, fingerprint, orphanSince);
        }
    }

    /** One revoked-until-changed marker: the quest and its counter snapshot. */
    private record RevokedRow(Identifier quest, List<Integer> counters) {
        static final Codec<RevokedRow> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Identifier.CODEC.fieldOf("quest").forGetter(RevokedRow::quest),
                Codec.INT.listOf().optionalFieldOf("counters", List.of()).forGetter(RevokedRow::counters)
        ).apply(inst, RevokedRow::new));

        static List<RevokedRow> of(Map<Identifier, List<Integer>> markers) {
            List<RevokedRow> rows = new ArrayList<>(markers.size());
            markers.forEach((quest, counters) -> rows.add(new RevokedRow(quest, counters)));
            return rows;
        }

        static Map<Identifier, List<Integer>> toMap(List<RevokedRow> rows) {
            Map<Identifier, List<Integer>> out = new LinkedHashMap<>();
            for (RevokedRow row : rows) {
                out.put(row.quest(), List.copyOf(row.counters()));
            }
            return out;
        }
    }

    private record RepeatRow(Identifier quest, long at) {
        static final Codec<RepeatRow> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Identifier.CODEC.fieldOf("quest").forGetter(RepeatRow::quest),
                Codec.LONG.fieldOf("at").forGetter(RepeatRow::at)
        ).apply(inst, RepeatRow::of));

        private static RepeatRow of(Identifier quest, Long at) {
            return new RepeatRow(quest, at.longValue());
        }

        static List<RepeatRow> of(Map<Identifier, Long> stamps) {
            List<RepeatRow> out = new ArrayList<>(stamps.size());
            stamps.forEach((quest, at) -> out.add(new RepeatRow(quest, at.longValue())));
            return out;
        }
    }

    private record PlayerRow(String player, long updatedAt, List<QuestRow> quests, List<OwedReward> owed,
                             long contractDay, List<Identifier> contractsDone, List<Identifier> paid,
                             List<RevokedRow> revoked, List<RepeatRow> repeatPaid) {
        static final Codec<PlayerRow> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.STRING.fieldOf("player").forGetter(PlayerRow::player),
                Codec.LONG.optionalFieldOf("updated_at", 0L).forGetter(PlayerRow::updatedAt),
                QuestRow.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(PlayerRow::quests),
                OwedReward.CODEC.listOf().optionalFieldOf("owed", List.of()).forGetter(PlayerRow::owed),
                Codec.LONG.optionalFieldOf("contract_day", -1L).forGetter(PlayerRow::contractDay),
                Identifier.CODEC.listOf().optionalFieldOf("contracts_done", List.of())
                        .forGetter(PlayerRow::contractsDone),
                Identifier.CODEC.listOf().optionalFieldOf("paid", List.of()).forGetter(PlayerRow::paid),
                RevokedRow.CODEC.listOf().optionalFieldOf("revoked", List.of()).forGetter(PlayerRow::revoked),
                RepeatRow.CODEC.listOf().optionalFieldOf("repeat_paid", List.of()).forGetter(PlayerRow::repeatPaid)
        ).apply(inst, PlayerRow::new));
    }

    /**
     * Store format: {@code 0} (absent) is everything before the paid ledger existed; {@code 1} adds
     * the ledger and the revoked markers; {@code 2} (0.4.0) is the first format after the three colony
     * quests left the spine for the NeroColonies pack ({@link #MOVED_QUESTS}).
     */
    private static final int FORMAT = 2;

    /**
     * Quest ids that moved out of the spine in 0.4.0 (I1): old spine id &rarr; the id the owning mod's pack
     * now ships. Rows stored under an old id are renamed on the first load of a format &lt; 2 store.
     */
    static final Map<Identifier, Identifier> MOVED_QUESTS = Map.of(
            Identifier.fromNamespaceAndPath("neroquests", "space_race/breathing_room"),
            Identifier.fromNamespaceAndPath("nerocolonies", "colony_chronicle/breathing_room"),
            Identifier.fromNamespaceAndPath("neroquests", "space_race/full_larder"),
            Identifier.fromNamespaceAndPath("nerocolonies", "colony_chronicle/full_larder"),
            Identifier.fromNamespaceAndPath("neroquests", "space_race/settled_ground"),
            Identifier.fromNamespaceAndPath("nerocolonies", "colony_chronicle/settled_ground"));

    /**
     * Migration to format 2 (I1): move every row keyed by a {@link #MOVED_QUESTS} old id to its new id —
     * player and server progress, the paid ledgers, revoked markers, repeat stamps, owed entries' source,
     * once-per-world slots and the world completion counter. Idempotent: an old id that is already gone
     * changes nothing, and where both ids hold a row the new id's row wins unless only the old one is
     * complete (counters are summed). Returns whether anything moved.
     */
    boolean migrateMovedQuests() {
        boolean changed = false;
        for (Map.Entry<Identifier, Identifier> move : MOVED_QUESTS.entrySet()) {
            Identifier from = move.getKey();
            Identifier to = move.getValue();
            for (Map<Identifier, QuestProgress> rows : byPlayer.values()) {
                changed |= moveProgress(rows, from, to);
            }
            changed |= moveProgress(serverScope, from, to);
            for (Set<Identifier> set : paid.values()) {
                changed |= moveInSet(set, from, to);
            }
            changed |= moveInSet(serverPaid, from, to);
            for (Map<Identifier, List<Integer>> markers : revoked.values()) {
                changed |= moveKey(markers, from, to);
            }
            changed |= moveKey(serverRevoked, from, to);
            for (Map<Identifier, Long> stamps : repeatPaid.values()) {
                changed |= moveKey(stamps, from, to);
            }
            for (List<OwedReward> entries : owed.values()) {
                for (int i = 0; i < entries.size(); i++) {
                    OwedReward entry = entries.get(i);
                    if (from.equals(entry.source())) {
                        entries.set(i, new OwedReward(entry.requestKey(), entry.currency(), entry.amount(),
                                entry.reason(), to, entry.createdAt(), entry.attempts(), entry.permanent(),
                                entry.kind(), entry.components()));
                        changed = true;
                    }
                }
            }
            String prefix = from + "#";
            List<String> slots = new ArrayList<>(claimedOnce);
            for (String slot : slots) {
                if (slot.startsWith(prefix)) {
                    claimedOnce.remove(slot);
                    claimedOnce.add(to + slot.substring(from.toString().length()));
                    changed = true;
                }
            }
            Long count = questCompletions.remove(from);
            if (count != null) {
                Long existing = questCompletions.get(to);
                long sum = existing == null ? count.longValue() : existing.longValue() + count.longValue();
                questCompletions.put(to, Long.valueOf(sum < 0L ? Long.MAX_VALUE : sum));
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    private static boolean moveProgress(Map<Identifier, QuestProgress> rows, Identifier from, Identifier to) {
        QuestProgress old = rows.remove(from);
        if (old == null) {
            return false;
        }
        QuestProgress existing = rows.get(to);
        if (existing == null || (old.isComplete() && !existing.isComplete())) {
            rows.put(to, old);
        }
        return true;
    }

    private static boolean moveInSet(Set<Identifier> set, Identifier from, Identifier to) {
        if (!set.remove(from)) {
            return false;
        }
        set.add(to);
        return true;
    }

    private static <V> boolean moveKey(Map<Identifier, V> map, Identifier from, Identifier to) {
        V value = map.remove(from);
        if (value == null) {
            return false;
        }
        map.putIfAbsent(to, value);
        return true;
    }

    /**
     * Migration from format 0: every completion recorded before the ledger existed was paid by the
     * code of its day, so it enters the ledger now. Without this, revoking a pre-ledger completion
     * would let it pay a second time.
     */
    private void seedLedgerFromCompletions() {
        byPlayer.forEach((player, quests) -> quests.forEach((quest, progress) -> {
            if (progress.isComplete()) {
                paid.computeIfAbsent(player, i -> new LinkedHashSet<>()).add(quest);
            }
        }));
        serverScope.forEach((quest, progress) -> {
            if (progress.isComplete()) {
                serverPaid.add(quest);
            }
        });
    }

    /** The persistence codec, exposed to the package for round-trip tests. */
    static Codec<QuestProgressState> persistenceCodec() {
        return codec();
    }

    private static Codec<QuestProgressState> codec() {
        return RecordCodecBuilder.create(inst -> inst.group(
                PlayerRow.CODEC.listOf().optionalFieldOf("players", List.of())
                        .forGetter(QuestProgressState::playerRows),
                QuestRow.CODEC.listOf().optionalFieldOf("server", List.of())
                        .forGetter(QuestProgressState::serverRows),
                Codec.LONG.optionalFieldOf("reward_seq", 0L).forGetter(state -> Long.valueOf(state.rewardSequence)),
                Codec.STRING.listOf().optionalFieldOf("claimed_once", List.of())
                        .forGetter(state -> List.copyOf(state.claimedOnce)),
                Identifier.CODEC.listOf().optionalFieldOf("server_paid", List.of())
                        .forGetter(state -> List.copyOf(state.serverPaid)),
                RevokedRow.CODEC.listOf().optionalFieldOf("server_revoked", List.of())
                        .forGetter(state -> RevokedRow.of(state.serverRevoked)),
                Codec.INT.optionalFieldOf("format", 0).forGetter(state -> Integer.valueOf(FORMAT)),
                CountRow.CODEC.listOf().optionalFieldOf("quest_completions", List.of())
                        .forGetter(state -> CountRow.of(state.questCompletions)),
                CountRow.CODEC.listOf().optionalFieldOf("chapter_completions", List.of())
                        .forGetter(state -> CountRow.of(state.chapterCompletions))
        ).apply(inst, QuestProgressState::fromRows));
    }

    private List<PlayerRow> playerRows() {
        List<PlayerRow> out = new ArrayList<>();
        Set<UUID> players = new LinkedHashSet<>(byPlayer.keySet());
        players.addAll(owed.keySet());
        players.addAll(contractDay.keySet());
        players.addAll(paid.keySet());
        players.addAll(revoked.keySet());
        players.addAll(repeatPaid.keySet());
        for (UUID player : players) {
            Map<Identifier, QuestProgress> quests = byPlayer.getOrDefault(player, Map.of());
            List<QuestRow> rows = new ArrayList<>(quests.size());
            quests.forEach((quest, progress) -> rows.add(QuestRow.of(quest, progress)));
            out.add(new PlayerRow(player.toString(), lastUpdated.getOrDefault(player, 0L), rows,
                    owed.getOrDefault(player, List.of()), contractDay.getOrDefault(player, -1L).longValue(),
                    List.copyOf(contractsDone.getOrDefault(player, Set.of())),
                    List.copyOf(paid.getOrDefault(player, Set.of())),
                    RevokedRow.of(revoked.getOrDefault(player, Map.of())),
                    RepeatRow.of(repeatPaid.getOrDefault(player, Map.of()))));
        }
        return out;
    }

    private List<QuestRow> serverRows() {
        List<QuestRow> out = new ArrayList<>(serverScope.size());
        serverScope.forEach((quest, progress) -> out.add(QuestRow.of(quest, progress)));
        return out;
    }

    private static QuestProgressState fromRows(List<PlayerRow> players, List<QuestRow> server, Long rewardSeq,
                                               List<String> claimedOnce, List<Identifier> serverPaid,
                                               List<RevokedRow> serverRevoked, Integer format,
                                               List<CountRow> questCompletions, List<CountRow> chapterCompletions) {
        QuestProgressState state = new QuestProgressState();
        CountRow.load(questCompletions, state.questCompletions);
        CountRow.load(chapterCompletions, state.chapterCompletions);
        for (PlayerRow row : players) {
            UUID player;
            try {
                player = UUID.fromString(row.player());
            } catch (IllegalArgumentException ignored) {
                continue; // skip malformed UUID rows
            }
            Map<Identifier, QuestProgress> quests = new LinkedHashMap<>();
            for (QuestRow questRow : row.quests()) {
                quests.put(questRow.quest(), questRow.toProgress());
            }
            if (!quests.isEmpty()) {
                state.byPlayer.put(player, quests);
            }
            state.lastUpdated.put(player, row.updatedAt());
            if (!row.owed().isEmpty()) {
                state.owed.put(player, new ArrayList<>(row.owed()));
            }
            if (row.contractDay() >= 0L) {
                state.contractDay.put(player, Long.valueOf(row.contractDay()));
                state.contractsDone.put(player, new LinkedHashSet<>(row.contractsDone()));
            }
            if (!row.paid().isEmpty()) {
                state.paid.put(player, new LinkedHashSet<>(row.paid()));
            }
            if (!row.revoked().isEmpty()) {
                state.revoked.put(player, RevokedRow.toMap(row.revoked()));
            }
            if (!row.repeatPaid().isEmpty()) {
                Map<Identifier, Long> stamps = new LinkedHashMap<>();
                row.repeatPaid().forEach(stamp -> stamps.put(stamp.quest(), Long.valueOf(stamp.at())));
                state.repeatPaid.put(player, stamps);
            }
        }
        state.rewardSequence = rewardSeq == null ? 0L : rewardSeq.longValue();
        state.claimedOnce.addAll(claimedOnce);
        state.serverPaid.addAll(serverPaid);
        state.serverRevoked.putAll(RevokedRow.toMap(serverRevoked));
        for (QuestRow questRow : server) {
            state.serverScope.put(questRow.quest(), questRow.toProgress());
        }
        if (format == null || format.intValue() < 1) {
            state.seedLedgerFromCompletions();
        }
        if (format == null || format.intValue() < 2) {
            state.migrateMovedQuests();
        }
        return state;
    }
}
