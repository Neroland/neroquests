package za.co.neroland.neroquests.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import za.co.neroland.nerolandcore.economy.CoreCurrencies;
import za.co.neroland.nerolandcore.economy.Currency;
import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroquests.NeroQuestsCommon;
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
 * {@link CurrencyApi#hasRealProvider()} first and, when no real provider is installed, logs once at
 * debug and grants nothing. The quest still completes and its other rewards still pay out.
 *
 * <p>Balances are keyed by UUID, so this reward lands whether or not the recipient is online.
 */
public record CurrencyReward(Identifier currency, long amount) implements RewardSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "currency");

    /** The ecosystem default when a reward names no currency. */
    public static final Identifier DEFAULT_CURRENCY = CoreCurrencies.CREDITS.id();

    public static final MapCodec<CurrencyReward> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Identifier.CODEC.optionalFieldOf("currency", DEFAULT_CURRENCY)
                    .forGetter(CurrencyReward::currency),
            // Codec has no longRange, so validate by hand: a reward pays out, it never charges.
            Codec.LONG.validate(value -> value != null && value.longValue() >= 1L
                            ? DataResult.success(value)
                            : DataResult.error(() -> "a currency reward's 'amount' must be at least 1"))
                    .fieldOf("amount").forGetter(CurrencyReward::boxedAmount)
    ).apply(inst, CurrencyReward::of));

    /** Boxed-parameter factory for the codec (avoids the ECJ unboxing null-safety warning). */
    private static CurrencyReward of(Identifier currency, Long amount) {
        return new CurrencyReward(currency, amount.longValue());
    }

    private Long boxedAmount() {
        return Long.valueOf(amount);
    }

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public void grant(RewardContext context) {
        if (!CurrencyApi.hasRealProvider()) {
            RewardLog.debugOnce("currency-no-provider",
                    "[NeroQuests] No currency provider is installed, so '{}' rewards grant nothing. "
                            + "Install a mod that implements Neroland Core's currency contract "
                            + "(NeroEconomy) to enable them.", TYPE_ID);
            return;
        }
        CurrencyApi.deposit(context.playerId(), resolveCurrency(), amount);
    }

    /**
     * The {@link Currency} this reward pays in. The shared credits constant is reused so its
     * translation key matches Core's; any other id gets the conventional
     * {@code currency.<namespace>.<path>} key.
     */
    private Currency resolveCurrency() {
        if (DEFAULT_CURRENCY.equals(currency)) {
            return CoreCurrencies.CREDITS;
        }
        return Currency.of(currency, "currency." + currency.getNamespace() + "." + currency.getPath());
    }
}
