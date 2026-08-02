package za.co.neroland.neroquests.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 * goes through {@link SavedDataRecovery}, so a corrupt file degrades to empty progress instead of
 * crashing the server.
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
     * The server whose retention sweep has already run, so the lazy check in {@link #get} fires at
     * most once per server instance. Written on the server thread; {@code volatile} only so an
     * integrated-server restart in the same JVM is seen promptly.
     */
    private static volatile MinecraftServer prunedFor;

    private final Map<UUID, Map<Identifier, QuestProgress>> byPlayer = new LinkedHashMap<>();
    private final Map<UUID, Long> lastUpdated = new LinkedHashMap<>();
    private final Map<Identifier, QuestProgress> serverScope = new LinkedHashMap<>();

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
            state.pruneStale(NeroQuestsConfig.RETENTION_DAYS.get());
        }
        return state;
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
     * Adds {@code amount} to one objective's counter for a player, saturating at
     * {@code [0, Integer.MAX_VALUE]}.
     *
     * @param objectiveIndex the objective's position in the quest definition; out-of-range indices
     *                       are ignored
     * @return the counter's value after the change (0 for an ignored index)
     */
    public int incrementObjective(UUID player, Identifier quest, int objectiveIndex, int amount) {
        if (objectiveIndex < 0 || objectiveIndex >= MAX_OBJECTIVES) {
            return 0;
        }
        Map<Identifier, QuestProgress> quests = byPlayer.computeIfAbsent(player, i -> new LinkedHashMap<>());
        QuestProgress current = quests.getOrDefault(quest, QuestProgress.EMPTY);
        int updatedValue = clampedSum(current.counter(objectiveIndex), amount);
        QuestProgress updated = current.withCounter(objectiveIndex, updatedValue);
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

    /** Whether a {@code scope: server} quest is complete for the whole world. */
    public boolean isServerComplete(Identifier quest) {
        QuestProgress progress = serverScope.get(quest);
        return progress != null && progress.isComplete();
    }

    /** {@link #incrementObjective} for the shared server-scoped section. */
    public int incrementServerObjective(Identifier quest, int objectiveIndex, int amount) {
        if (objectiveIndex < 0 || objectiveIndex >= MAX_OBJECTIVES) {
            return 0;
        }
        QuestProgress current = serverScope.getOrDefault(quest, QuestProgress.EMPTY);
        int updatedValue = clampedSum(current.counter(objectiveIndex), amount);
        QuestProgress updated = current.withCounter(objectiveIndex, updatedValue);
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

    // --- privacy: erasure, retention, export --------------------------------

    /**
     * POPIA/GDPR erasure: drop everything stored for a player — every quest row and the
     * last-updated stamp. Shared server-scoped progress is deliberately untouched: it belongs to
     * the world, not to any player, and holds no identifiers. Never logs player identity.
     */
    public void forgetPlayer(UUID player) {
        boolean changed = byPlayer.remove(player) != null;
        changed |= lastUpdated.remove(player) != null;
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
        if (days <= 0) {
            return 0;
        }
        long threshold = System.currentTimeMillis() - days * MILLIS_PER_DAY;
        List<UUID> stale = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : lastUpdated.entrySet()) {
            // Rows with no usable stamp are left alone rather than deleted on a guess.
            if (entry.getValue() != null && entry.getValue() > 0L && entry.getValue() < threshold) {
                stale.add(entry.getKey());
            }
        }
        for (UUID player : stale) {
            forgetPlayer(player);
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

    // --- persistence (same SavedDataType + Codec pattern as Core) -----------

    private record QuestRow(Identifier quest, List<Integer> counters, long completedAt) {
        static final Codec<QuestRow> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Identifier.CODEC.fieldOf("quest").forGetter(QuestRow::quest),
                Codec.INT.listOf().optionalFieldOf("counters", List.of()).forGetter(QuestRow::counters),
                Codec.LONG.optionalFieldOf("completed_at", 0L).forGetter(QuestRow::completedAt)
        ).apply(inst, QuestRow::new));

        static QuestRow of(Identifier quest, QuestProgress progress) {
            return new QuestRow(quest, progress.counters(), progress.completedAt());
        }

        QuestProgress toProgress() {
            return new QuestProgress(counters, completedAt);
        }
    }

    private record PlayerRow(String player, long updatedAt, List<QuestRow> quests) {
        static final Codec<PlayerRow> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.STRING.fieldOf("player").forGetter(PlayerRow::player),
                Codec.LONG.optionalFieldOf("updated_at", 0L).forGetter(PlayerRow::updatedAt),
                QuestRow.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(PlayerRow::quests)
        ).apply(inst, PlayerRow::new));
    }

    private static Codec<QuestProgressState> codec() {
        return RecordCodecBuilder.create(inst -> inst.group(
                PlayerRow.CODEC.listOf().optionalFieldOf("players", List.of())
                        .forGetter(QuestProgressState::playerRows),
                QuestRow.CODEC.listOf().optionalFieldOf("server", List.of())
                        .forGetter(QuestProgressState::serverRows)
        ).apply(inst, QuestProgressState::fromRows));
    }

    private List<PlayerRow> playerRows() {
        List<PlayerRow> out = new ArrayList<>();
        byPlayer.forEach((player, quests) -> {
            List<QuestRow> rows = new ArrayList<>(quests.size());
            quests.forEach((quest, progress) -> rows.add(QuestRow.of(quest, progress)));
            out.add(new PlayerRow(player.toString(), lastUpdated.getOrDefault(player, 0L), rows));
        });
        return out;
    }

    private List<QuestRow> serverRows() {
        List<QuestRow> out = new ArrayList<>(serverScope.size());
        serverScope.forEach((quest, progress) -> out.add(QuestRow.of(quest, progress)));
        return out;
    }

    private static QuestProgressState fromRows(List<PlayerRow> players, List<QuestRow> server) {
        QuestProgressState state = new QuestProgressState();
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
            state.byPlayer.put(player, quests);
            state.lastUpdated.put(player, row.updatedAt());
        }
        for (QuestRow questRow : server) {
            state.serverScope.put(questRow.quest(), questRow.toProgress());
        }
        return state;
    }
}
