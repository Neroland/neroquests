package za.co.neroland.neroquests.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * Reward {@code neroquests:xp} — grants raw experience points on completion.
 *
 * <pre>{@code { "type": "neroquests:xp", "amount": 50 }}</pre>
 *
 * <p>{@code amount} is required and must be at least 1; a zero or negative amount fails the
 * codec, which drops the owning quest with a logged warning rather than silently granting
 * nothing.
 *
 * <p>Experience needs a body to land in: when the recipient is offline, dead or disconnecting it is
 * kept in the owed queue and granted on their next join or respawn ({@link OwedDelivery}, AUD-05).
 */
public record XpReward(int amount) implements RewardSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "xp");

    /** Upper bound on {@code amount} (AUD-22); beyond it the codec fails and the quest is dropped. */
    public static final int MAX_AMOUNT = 1_000_000;

    public static final MapCodec<XpReward> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Codec.intRange(1, MAX_AMOUNT).fieldOf("amount").forGetter(XpReward::amount)
    ).apply(inst, XpReward::new));

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public void grant(RewardContext context) {
        ServerPlayer player = context.player();
        if (!OwedDelivery.canReceive(player)) {
            OwedDelivery.keepXp(context.server(), context.playerId(), player, amount, context.quest().id());
            return;
        }
        player.giveExperiencePoints(amount);
    }
}
