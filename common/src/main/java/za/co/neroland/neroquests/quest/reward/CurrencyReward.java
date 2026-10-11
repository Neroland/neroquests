package za.co.neroland.neroquests.quest.reward;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import za.co.neroland.nerolandcore.economy.CoreCurrencies;
import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.compat.neroeconomy.EconomyCompat;
import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * Reward {@code neroquests:currency} — pays money into the player's balance through Neroland Core's
 * currency contract.
 *
 * <pre>{@code { "type": "neroquests:currency", "amount": 250 }}
 * {@code { "type": "neroquests:currency", "currency": "mypack:scrip", "amount": 5 }}</pre>
 *
 * <p>{@code currency} defaults to {@code nerolandcore:credits}, the ecosystem's shared currency.
 *
 * <p><b>Degradation.</b> Core only defines the contract — an implementing mod (NeroEconomy) supplies
 * the store. Until one registers, Core falls back to an in-memory provider whose balances vanish on
 * restart, so paying into it would be a lie. This reward therefore checks
 * {@code CurrencyApi.hasRealProvider()} first and, when no real provider is installed, logs once at
 * debug and grants nothing. The quest still completes and its other rewards still pay out.
 *
 * <p>Balances are keyed by UUID, so this reward lands whether or not the recipient is online.
 *
 * <p><b>Budgeted payouts.</b> With NeroEconomy installed, credits are paid through its budgeted
 * payout API (reason {@code quest_reward}); a refused payout is told to the player and kept as
 * <em>owed</em>, then retried later with the same request key — see {@link CurrencyPayouts}. Other
 * currencies, or no NeroEconomy, keep Core's {@code CurrencyApi.deposit}, whose result is checked.
 *
 * <p><b>{@code "once_per_world": true}</b> pays the reward only to the first player in the world
 * who completes the quest; everyone after still completes it, without the money. The safer shape
 * for large amounts on quests that one item set could complete on every alternate account.
 */
public record CurrencyReward(Identifier currency, long amount, boolean oncePerWorld) implements RewardSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "currency");

    /** Upper bound on one currency reward's {@code amount} (AUD-22). */
    public static final long MAX_AMOUNT = 1_000_000L;

    /** The ecosystem default when a reward names no currency. */
    public static final Identifier DEFAULT_CURRENCY = CoreCurrencies.CREDITS.id();

    public static final MapCodec<CurrencyReward> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Identifier.CODEC.optionalFieldOf("currency", DEFAULT_CURRENCY)
                    .forGetter(CurrencyReward::currency),
            // Codec has no longRange, so validate by hand: a reward pays out, it never charges, and one
            // reward is capped (AUD-22) so a pack typo cannot mint unbounded money.
            Codec.LONG.validate(value -> value != null && value.longValue() >= 1L
                            && value.longValue() <= MAX_AMOUNT
                            ? DataResult.success(value)
                            : DataResult.error(() -> "a currency reward's 'amount' must be 1 to " + MAX_AMOUNT))
                    .fieldOf("amount").forGetter(CurrencyReward::boxedAmount),
            Codec.BOOL.optionalFieldOf("once_per_world", Boolean.FALSE).forGetter(CurrencyReward::boxedOnce)
    ).apply(inst, CurrencyReward::of));

    /** Boxed-parameter factory for the codec (avoids the ECJ unboxing null-safety warning). */
    private static CurrencyReward of(Identifier currency, Long amount, Boolean oncePerWorld) {
        return new CurrencyReward(currency, amount.longValue(), oncePerWorld.booleanValue());
    }

    private Boolean boxedOnce() {
        return Boolean.valueOf(oncePerWorld);
    }

    private Long boxedAmount() {
        return Long.valueOf(amount);
    }

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    /** The pre-index slot key of 0.4.0 development builds, honoured for a quest's first once-per-world currency reward. */
    private static final String LEGACY_SLOT_SUFFIX = "#currency";

    @Override
    public void grant(RewardContext context) {
        // One slot per reward, as <quest id>#<reward index>: two once-per-world rewards on one quest
        // must not share a claim.
        String slot = context.quest().id() + "#" + context.rewardIndex();
        QuestProgressState state = QuestProgressState.get(context.server());
        if (oncePerWorld && !EconomyCompat.active() && !CurrencyApi.hasRealProvider()) {
            return; // nothing could be paid; do not use up the world's one claim
        }
        if (oncePerWorld && firstOncePerWorld(context)
                && state.isClaimedOnce(context.quest().id() + LEGACY_SLOT_SUFFIX)) {
            state.claimOnce(slot); // migrate the old shared key onto this reward's own slot
        }
        if (oncePerWorld && !state.claimOnce(slot)) {
            if (context.player() != null) {
                context.player().sendSystemMessage(Component.translatable("message.neroquests.reward_claimed_once"));
            }
            return;
        }
        // An owed reward still counts as claimed: it will be paid on retry.
        CurrencyPayouts.pay(context.server(), context.playerId(), context.player(), currency, amount,
                EconomyCompat.REASON_QUEST, CurrencyPayouts.QUEST_KEY_PREFIX, context.quest().id());
    }

    /** Whether this is the first once-per-world currency reward of its quest (the one the legacy key meant). */
    private static boolean firstOncePerWorld(RewardContext context) {
        List<RewardSpec> rewards = context.quest().rewards();
        for (int index = 0; index < rewards.size(); index++) {
            if (rewards.get(index) instanceof CurrencyReward currency && currency.oncePerWorld()) {
                return index == context.rewardIndex();
            }
        }
        return false;
    }
}
