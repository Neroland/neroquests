package za.co.neroland.neroquests.custom;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.network.QuestSync;
import za.co.neroland.neroquests.quest.EditorPermissions;
import za.co.neroland.neroquests.quest.engine.SeenChannels;

/**
 * The per-player editor block of the definitions sync (D2/E1): sent to each player about themselves only.
 *
 * <pre>{@code
 * { "can_author": true,
 *   "locked": false, "escrow": false,
 *   "limits": { "max_quests": 500, "max_chapters": 20, "max_objectives": 12, "max_rewards": 9,
 *               "max_stacks": 9, "max_xp": 500, "max_currency": 1000, "title_max": 64,
 *               "description_max": 512, "label_max": 64, "coord_min": -512, "coord_max": 512,
 *               "cooldown_min": 60, "cooldown_max": 2592000, "escrow_copies_max": 64,
 *               "prerequisites_max": 16 },
 *   "counts": { "quests": 3, "chapters": 1 },
 *   "channels": [ "nerotech:machine_built" ],
 *   "authored": [ "custom:abcd2345/efgh6723" ] }
 * }</pre>
 *
 * <p>{@code can_author} is a hint for the UI only — every editor request is re-checked on the server.
 * {@code limits}/{@code counts}/{@code channels} are present only when {@code can_author} is true
 * ({@code channels}: the custom-event channels seen this session, at most 256 ids, for the channel picker). {@code authored} lists the
 * custom quests the <em>receiving</em> player wrote, so the book can say "you"; every other custom quest reads
 * "another player". No UUID and no name is ever sent.
 *
 * <p>Changes are noticed and re-sent: allow-list and lock changes mark the players affected; game-mode and
 * permission changes are caught by {@link #sweep} (the 1 Hz engine sweep), which marks a player whose
 * {@code can_author} flipped. In memory only; dropped on server stop.
 */
public final class EditorSync {

    /** Wire key of the authorisation hint. */
    public static final String CAN_AUTHOR = "can_author";

    private static final Map<UUID, Boolean> LAST = new HashMap<>();

    private EditorSync() {
    }

    /** The editor block for {@code player}. */
    public static JsonObject info(MinecraftServer server, ServerPlayer player) {
        CustomQuestState state = CustomQuestState.get(server);
        boolean canAuthor = EditorPermissions.canAuthor(player);
        synchronized (LAST) {
            LAST.put(player.getUUID(), Boolean.valueOf(canAuthor));
        }
        JsonObject root = new JsonObject();
        root.addProperty(CAN_AUTHOR, canAuthor);
        if (canAuthor) {
            CustomLimits limits = CustomLimits.current();
            root.addProperty("locked", state.isLocked());
            root.addProperty("escrow", limits.escrow());
            JsonObject caps = new JsonObject();
            caps.addProperty("max_quests", limits.maxQuests());
            caps.addProperty("max_chapters", limits.maxChapters());
            caps.addProperty("max_objectives", CustomLimits.MAX_OBJECTIVES);
            caps.addProperty("max_rewards", CustomLimits.MAX_REWARDS);
            caps.addProperty("max_stacks", limits.maxStacks());
            caps.addProperty("max_xp", limits.maxXp());
            caps.addProperty("max_currency", limits.maxCurrency());
            caps.addProperty("title_max", TextSanitizer.TITLE_MAX);
            caps.addProperty("description_max", TextSanitizer.DESCRIPTION_MAX);
            caps.addProperty("label_max", TextSanitizer.LABEL_MAX);
            caps.addProperty("coord_min", CustomLimits.MIN_COORD);
            caps.addProperty("coord_max", CustomLimits.MAX_COORD);
            caps.addProperty("cooldown_min", CustomLimits.MIN_COOLDOWN_SECONDS);
            caps.addProperty("cooldown_max", CustomLimits.MAX_COOLDOWN_SECONDS);
            caps.addProperty("escrow_copies_max", CustomLimits.MAX_ESCROW_COPIES);
            caps.addProperty("prerequisites_max", CustomQuests.MAX_PREREQUISITES);
            root.add("limits", caps);
            JsonObject counts = new JsonObject();
            counts.addProperty("quests", state.quests().size());
            counts.addProperty("chapters", state.chapters().size());
            root.add("counts", counts);
            // E4: the custom-event channels seen this session (ids only, bounded by SeenChannels), for the
            // editor's channel picker.
            JsonArray channels = new JsonArray();
            for (Identifier channel : SeenChannels.sortedIds()) {
                channels.add(channel.toString());
            }
            root.add("channels", channels);
        }
        JsonArray authored = new JsonArray();
        for (CustomQuest quest : state.quests().values()) {
            if (quest.authoredBy(player.getUUID())) {
                authored.add(quest.id().toString());
            }
        }
        root.add("authored", authored);
        return root;
    }

    /** Re-check every online player's authorisation; mark those whose {@code can_author} flipped (1 Hz). */
    public static void sweep(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            online.add(id);
            Boolean last;
            synchronized (LAST) {
                last = LAST.get(id);
            }
            if (flipped(last, EditorPermissions.canAuthor(player))) {
                QuestSync.markDefinitionsDirty(id);
            }
        }
        synchronized (LAST) {
            LAST.keySet().retainAll(online);
        }
    }

    /**
     * Whether a player's {@code can_author} changed since it was last sent (pure): a re-send is due, which
     * drops a client that lost the right back to view mode ({@link EditGate#editModeAfterSync}). Nothing was
     * sent yet ({@code last == null}) means the first sync carries it anyway.
     */
    public static boolean flipped(Boolean last, boolean now) {
        return last != null && last.booleanValue() != now;
    }

    /** One player's editor state changed (allow-list). */
    public static void markDirty(UUID player) {
        QuestSync.markDefinitionsDirty(player);
    }

    /** Everyone's editor state may have changed (lock). */
    public static void markAllDirty() {
        QuestSync.markDefinitionsDirtyAll();
    }

    static void serverStopped() {
        synchronized (LAST) {
            LAST.clear();
        }
    }
}
