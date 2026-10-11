package za.co.neroland.neroquests.compat.neroeconomy;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.platform.Services;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.engine.EconomyActivity;
import za.co.neroland.neroquests.quest.engine.QuestTriggers;

/**
 * NeroQuests' only door to the optional NeroEconomy mod. This class imports nothing from it: it asks
 * the platform whether {@code neroeconomy} is loaded and only then touches {@link NeroEconomyBridge},
 * the one class that does. With NeroEconomy absent (or older than its cross-mod API) everything here
 * reports "inactive" and callers keep Neroland Core's {@code CurrencyApi} path.
 *
 * <p>Privacy (POPIA/GDPR): player ids pass straight through to the economy and are never logged here.
 */
public final class EconomyCompat {

    public static final String ECONOMY_MOD_ID = "neroeconomy";

    /** Reason code for {@code neroquests:currency} quest rewards. */
    public static final String REASON_QUEST = "quest_reward";

    /** Reason code for daily-contract rewards. */
    public static final String REASON_CONTRACT = "daily_contract";

    /**
     * What a budgeted payout did.
     *
     * @param ok             whether the payment stands (applied now or already applied under this key)
     * @param translationKey the economy's lang key explaining the outcome, for telling the player
     * @param permanent      whether retrying can never succeed (a malformed request rather than a budget)
     */
    public record Payout(boolean ok, String translationKey, boolean permanent) {
    }

    private static volatile boolean active;

    private EconomyCompat() {
    }

    /** Called once from common init, before any server starts (the economy only accepts claims then). */
    public static void init() {
        if (!Services.PLATFORM.isModLoaded(ECONOMY_MOD_ID)) {
            NeroQuestsCommon.LOGGER.debug("[NeroQuests] NeroEconomy not installed; quest credits use Core's currency contract.");
            return;
        }
        try {
            active = NeroEconomyBridge.init();
        } catch (LinkageError | RuntimeException e) {
            active = false;
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] NeroEconomy is installed but its cross-mod API could not be "
                    + "used ({}); quest credits fall back to Core's currency contract.", e.getClass().getSimpleName());
        }
        if (active) {
            NeroQuestsCommon.LOGGER.info("[NeroQuests] NeroEconomy detected: budgeted quest payouts and economy objectives enabled.");
        }
    }

    /** Whether NeroEconomy's budgeted payout API is in use. */
    public static boolean active() {
        return active;
    }

    /** Reserve to player through NeroQuests' claimed caller. Only call when {@link #active()}. */
    public static Payout pay(MinecraftServer server, UUID player, long amount, String reason, String requestKey) {
        if (!active) {
            return new Payout(false, "message.neroquests.economy_unavailable", false);
        }
        return NeroEconomyBridge.pay(server, player, amount, reason, requestKey);
    }

    /** Whether a payout of {@code amount} to {@code player} would pass the economy's checks right now. */
    public static boolean canPay(MinecraftServer server, UUID player, long amount) {
        return active && NeroEconomyBridge.canPay(server, player, amount);
    }

    /** What NeroQuests may still pay out today; 0 when inactive. */
    public static long remainingToday(MinecraftServer server) {
        return active ? NeroEconomyBridge.remainingToday(server) : 0L;
    }

    /** Hand one economy happening to the quest engine. Called by the bridge's event listeners. */
    /**
     * Whether a sibling payout reported by NeroEconomy's {@code onSiblingPaid} counts towards an
     * {@code economy} objective's {@code earned}: every paying mod except NeroQuests itself, so quest and
     * contract rewards can never complete an "earn credits" objective (§6.4). Pure.
     */
    public static boolean countsAsEarned(String payingModId) {
        return payingModId != null && !NeroQuestsCommon.MOD_ID.equals(payingModId);
    }

    static void activity(UUID player, EconomyActivity.Kind kind, String itemId, long amount) {
        if (!active || player == null || amount <= 0L) {
            return; // a bridge that failed to link part-way stays fully dormant
        }
        QuestTriggers.economyActivity(player, new EconomyActivity(kind, itemId, amount));
    }
}
