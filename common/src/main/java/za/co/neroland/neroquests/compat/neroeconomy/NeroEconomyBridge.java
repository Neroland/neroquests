package za.co.neroland.neroquests.compat.neroeconomy;

import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.api.EconomyCaller;
import za.co.neroland.neroeconomy.api.EconomyEvents;
import za.co.neroland.neroeconomy.api.EconomyResult;
import za.co.neroland.neroeconomy.api.SiblingEconomy;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.engine.EconomyActivity;

/**
 * The one class that imports NeroEconomy. Loaded only by {@link EconomyCompat} after the platform has
 * confirmed {@code neroeconomy} is installed. NeroEconomy is {@code compileOnly}: never bundled.
 *
 * <p>Event feed: {@code onSold} and {@code onBought} carry each player's own side of every settled
 * trade, including a resting order's owner, so {@code onFilled} is deliberately not counted — it
 * would credit the owner's fill a second time. {@code onSiblingPaid} feeds "earned", except payouts
 * made by NeroQuests itself.
 */
final class NeroEconomyBridge {

    private static EconomyCaller caller;

    private NeroEconomyBridge() {
    }

    /** Claim NeroQuests' caller and subscribe the event feed; {@code false} keeps the bridge dormant. */
    static boolean init() {
        // Version check without reflection: SiblingEconomy.API_VERSION is a compile-time constant (javac
        // inlines it, so reading it says nothing about the installed jar). Instead the API-1 surface is
        // simply used: SiblingEconomy.claim and the EconomyEvents feeds exist from API version 1 on, and
        // an older or reshaped NeroEconomy fails to link them (NoClassDefFoundError / NoSuchMethodError,
        // both LinkageErrors), which keeps the bridge dormant.
        EconomyCaller claimed;
        try {
            claimed = SiblingEconomy.claim(NeroQuestsCommon.MOD_ID,
                    Set.of(EconomyCompat.REASON_QUEST, EconomyCompat.REASON_CONTRACT));
            EconomyEvents.onSold(event -> EconomyCompat.activity(event.seller(), EconomyActivity.Kind.SOLD,
                    event.itemId(), event.quantity()));
            EconomyEvents.onBought(event -> EconomyCompat.activity(event.buyer(), EconomyActivity.Kind.BOUGHT,
                    event.itemId(), event.quantity()));
            EconomyEvents.onSiblingPaid(event -> {
                if (EconomyCompat.countsAsEarned(event.modId())) {
                    EconomyCompat.activity(event.player(), EconomyActivity.Kind.EARNED, null, event.amount());
                }
            });
        } catch (LinkageError e) {
            NeroQuestsCommon.LOGGER.info("[NeroQuests] NeroEconomy's cross-mod API (version 1) is not available "
                    + "in the installed NeroEconomy; staying on Core's currency contract.");
            return false;
        }
        caller = claimed;
        return true;
    }

    static EconomyCompat.Payout pay(MinecraftServer server, UUID player, long amount, String reason, String requestKey) {
        EconomyResult result = caller.pay(server, player, amount, reason, requestKey);
        boolean permanent = result == EconomyResult.INVALID || result == EconomyResult.UNKNOWN_REASON;
        return new EconomyCompat.Payout(result.ok(), result.translationKey(), permanent);
    }

    static boolean canPay(MinecraftServer server, UUID player, long amount) {
        return caller.canPay(server, player, amount).ok();
    }

    static long remainingToday(MinecraftServer server) {
        return caller.remainingToday(server);
    }
}
