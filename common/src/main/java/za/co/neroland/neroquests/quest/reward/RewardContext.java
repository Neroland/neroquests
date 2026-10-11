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
 * <p><b>Server scope (AUD-06).</b> For a {@code scope: server} quest the completion belongs to the
 * world. Player-targeted rewards ({@code item}, {@code xp}, {@code currency}, {@code reputation},
 * player/team gates) are granted once per <em>recipient</em> — the triggering player plus every
 * online player who has the quest available — each with a context of their own
 * ({@link #forRecipient}). World-level rewards ({@link za.co.neroland.neroquests.quest.RewardSpec#worldLevel})
 * are granted once, with the triggering player's context.
 *
 * <p>Server thread only. Instances are short-lived and never stored.
 *
 * <p><b>POPIA/GDPR:</b> the {@code playerId} is the existing Minecraft game UUID already held by the
 * progress store. Rewards must never log it — resource ids and amounts only.
 *
 * @param server   the running server
 * @param playerId the completing player, or the triggering player for {@link QuestScope#SERVER}
 * @param player      that player if they are currently online, otherwise {@code null}
 * @param quest       the quest that completed
 * @param rewardIndex the position of the reward being granted in the quest's {@code rewards} list, or
 *                    {@code -1} before grants start; keys per-reward state such as a once-per-world claim
 */
public record RewardContext(MinecraftServer server, UUID playerId, ServerPlayer player, Quest quest,
                            int rewardIndex) {

    /** Resolve a context from a completion, looking the player up in the server's player list. */
    public static RewardContext of(QuestCompletion completion) {
        MinecraftServer server = completion.server();
        ServerPlayer online = server.getPlayerList().getPlayer(completion.player());
        return new RewardContext(server, completion.player(), online, completion.quest(), -1);
    }

    /** A context for one recipient of a {@code scope: server} quest's player-targeted rewards. */
    public static RewardContext forRecipient(MinecraftServer server, UUID playerId, Quest quest) {
        return new RewardContext(server, playerId, server.getPlayerList().getPlayer(playerId), quest, -1);
    }

    /** This context for the reward at {@code index} of the quest's {@code rewards} list. */
    public RewardContext withRewardIndex(int index) {
        return index == rewardIndex ? this : new RewardContext(server, playerId, player, quest, index);
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
