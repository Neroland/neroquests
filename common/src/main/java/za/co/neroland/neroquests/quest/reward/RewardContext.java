package za.co.neroland.neroquests.quest.reward;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;
import za.co.neroland.neroquests.quest.engine.QuestCompletion;

/**
 * Everything a reward needs to pay itself out, resolved once per completion.
 *
 * <p>Two identities live here on purpose:
 *
 * <ul>
 *   <li>{@link #playerId()} — always present. Neroland Core's currency and reputation APIs are
 *       UUID-keyed, so those rewards land even if the recipient has since logged off.</li>
 *   <li>{@link #player()} — the live {@link ServerPlayer}, or {@code null} if they are offline.
 *       Rewards that need a body (items, experience, a chat line, a player-scope gate) must check
 *       {@link #isOnline()} first and skip with a debug log otherwise.</li>
 * </ul>
 *
 * <p><b>Server scope.</b> For a {@code scope: server} quest the completion belongs to the world, but
 * the player-targeted rewards ({@code item}, {@code xp}, {@code currency}, {@code reputation}) still
 * need someone to receive them: they go to the <em>triggering</em> player — whoever's action tipped
 * the shared quest over — if they are online. Only {@code gate} behaves differently, taking Core's
 * server-gate path instead of the per-player one.
 *
 * <p>Server thread only. Instances are short-lived and never stored.
 *
 * <p><b>POPIA/GDPR:</b> the {@code playerId} is the existing Minecraft game UUID already held by the
 * progress store. Rewards must never log it — resource ids and amounts only.
 *
 * @param server   the running server
 * @param playerId the completing player, or the triggering player for {@link QuestScope#SERVER}
 * @param player   that player if they are currently online, otherwise {@code null}
 * @param quest    the quest that completed
 */
public record RewardContext(MinecraftServer server, UUID playerId, ServerPlayer player, Quest quest) {

    /** Resolve a context from a completion, looking the player up in the server's player list. */
    public static RewardContext of(QuestCompletion completion) {
        MinecraftServer server = completion.server();
        ServerPlayer online = server.getPlayerList().getPlayer(completion.player());
        return new RewardContext(server, completion.player(), online, completion.quest());
    }

    /** Whether the receiving player is online, i.e. whether {@link #player()} is non-null. */
    public boolean isOnline() {
        return player != null;
    }

    /** The completed quest's scope — {@code server} rewards route their gate writes differently. */
    public QuestScope scope() {
        return quest.scope();
    }
}
