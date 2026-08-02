package za.co.neroland.neroquests.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerolandcore.reputation.ReputationApi;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * Reward {@code neroquests:reputation} — shifts the player's standing with a faction through
 * Neroland Core's reputation contract.
 *
 * <pre>{@code { "type": "neroquests:reputation", "faction": "nerofactions:miners_union", "amount": 25 }}</pre>
 *
 * <p>{@code amount} is a <b>delta</b> and may be negative: a quest that sides with one faction can
 * legitimately cost you standing with its rival, so this is the one reward type that is allowed to
 * take something away.
 *
 * <p><b>Degradation.</b> As with {@code neroquests:currency}, Core defines the contract and a
 * sibling mod (NeroFactions) supplies the store. With no real provider registered the adjustment
 * would land in a volatile in-memory map, so this reward checks
 * {@link ReputationApi#hasRealProvider()} first and otherwise logs once at debug and does nothing.
 * The quest still completes and its other rewards still pay out.
 *
 * <p>Reputation is keyed by UUID, so this reward lands whether or not the recipient is online.
 */
public record ReputationReward(Identifier faction, int amount) implements RewardSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "reputation");

    public static final MapCodec<ReputationReward> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Identifier.CODEC.fieldOf("faction").forGetter(ReputationReward::faction),
            Codec.INT.fieldOf("amount").forGetter(ReputationReward::amount)
    ).apply(inst, ReputationReward::of));

    /** Boxed-parameter factory for the codec (avoids the ECJ unboxing null-safety warning). */
    private static ReputationReward of(Identifier faction, Integer amount) {
        return new ReputationReward(faction, amount.intValue());
    }

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public void grant(RewardContext context) {
        if (amount == 0) {
            return;
        }
        if (!ReputationApi.hasRealProvider()) {
            RewardLog.debugOnce("reputation-no-provider",
                    "[NeroQuests] No reputation provider is installed, so '{}' rewards grant nothing. "
                            + "Install a mod that implements Neroland Core's reputation contract "
                            + "(NeroFactions) to enable them.", TYPE_ID);
            return;
        }
        ReputationApi.adjust(context.playerId(), faction, amount);
    }
}
