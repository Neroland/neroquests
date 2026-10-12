package za.co.neroland.neroquests.quest.reward;

import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import za.co.neroland.nerolandcore.economy.CoreCurrencies;
import za.co.neroland.nerolandcore.economy.Currency;
import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.compat.neroeconomy.EconomyCompat;
import za.co.neroland.neroquests.data.OwedReward;
import za.co.neroland.neroquests.data.PayoutJournal;
import za.co.neroland.neroquests.data.QuestProgressState;

/**
 * Pays NeroQuests' currency (quest rewards and daily contracts) and keeps what could not be paid.
 *
 * <ul>
 *   <li><b>Credits with NeroEconomy</b> — a budgeted payout through NeroQuests' claimed caller, with a
 *       request key of {@code nq:<sequence>:<source id>} (never a player id or name).</li>
 *   <li><b>Anything else</b> — Neroland Core's {@code CurrencyApi.deposit}, with its result checked.</li>
 * </ul>
 *
 * <p>A refused payout is told to the player (the economy's own explanation) and recorded as
 * <b>owed</b> in the quest progress store, then retried on the player's next join and every few
 * minutes while they are online — with the same request key, so a retry can never pay twice.
 *
 * <p>Privacy (POPIA/GDPR): nothing here logs a player id or name; log lines carry source ids and
 * amounts only.
 */
public final class CurrencyPayouts {

    /** Request-key prefix of quest rewards. */
    public static final String QUEST_KEY_PREFIX = "nq:";

    /** Request-key prefix of daily-contract rewards. */
    public static final String CONTRACT_KEY_PREFIX = "nq:dc:";

    private CurrencyPayouts() {
    }

    /**
     * Pay {@code amount} of {@code currency} to {@code playerId}; on refusal tell them (if online) and
     * record it as owed.
     *
     * @param online the player if online, for the chat line; may be {@code null}
     * @return {@code true} if the payment stands now
     */
    public static boolean pay(MinecraftServer server, UUID playerId, ServerPlayer online, Identifier currency,
                              long amount, String reason, String keyPrefix, Identifier source) {
        QuestProgressState state = QuestProgressState.get(server);
        boolean credits = CurrencyReward.DEFAULT_CURRENCY.equals(currency);
        if (credits && EconomyCompat.active()) {
            recoverJournal(server);
            String key = state.nextRequestKey(keyPrefix, source);
            // J1: the payout and its key reach the disk before the economy is asked, and leave it once the
            // call has returned and the outcome is recorded; a crash in between is settled on the next start
            // with the same key (DUPLICATE = already paid), so it can never pay twice or be lost.
            OwedReward attempt = new OwedReward(key, currency, amount, reason, source, System.currentTimeMillis(), 0);
            PayoutJournal journal = journal(server);
            journal.begin(playerId, attempt);
            EconomyCompat.Payout payout;
            try {
                payout = EconomyCompat.pay(server, playerId, amount, reason, key);
            } catch (RuntimeException e) {
                // Unknown outcome: keep it owed under the same key; a retry settles it either way.
                owe(state, playerId, online, attempt);
                journal.end(key);
                tell(online, Component.translatable("message.neroquests.reward_owed", Long.valueOf(amount)));
                return false;
            }
            if (payout.ok()) {
                journal.end(key);
                return true;
            }
            if (payout.permanent()) {
                // A malformed request (e.g. above the economy's single-payout cap) never succeeds on
                // retry; the pack must split or lower it. Source id and amount only.
                RewardLog.warnOnce("currency-invalid-" + source,
                        "[NeroQuests] The economy refused a {}-credit payout from {} as invalid; lower or split "
                                + "it in the data. It is kept as owed but not retried.", amount, source);
            }
            owe(state, playerId, online, new OwedReward(key, currency, amount, reason, source,
                    attempt.createdAt(), 0, payout.permanent()));
            journal.end(key);
            tell(online, Component.translatable(payout.translationKey()));
            tell(online, Component.translatable("message.neroquests.reward_owed", Long.valueOf(amount)));
            return false;
        }
        if (!CurrencyApi.hasRealProvider()) {
            RewardLog.debugOnce("currency-no-provider",
                    "[NeroQuests] No currency provider is installed, so currency rewards grant nothing. "
                            + "Install a mod that implements Neroland Core's currency contract (NeroEconomy) to enable them.");
            return false;
        }
        // J2 (RT-14): the Core path is journaled too. Its key is minted before the deposit and kept by any owed entry,
        // so every retry — including one that later goes through NeroEconomy's idempotent payout — reuses it. Core's
        // deposit itself takes no key: a crash mid-deposit is settled by not repeating it (counted on start-up).
        String key = state.nextRequestKey(keyPrefix, source);
        OwedReward attempt = new OwedReward(key, currency, amount, reason, source, System.currentTimeMillis(), 0);
        PayoutJournal journal = journal(server);
        journal.begin(new PayoutJournal.Entry(playerId, attempt, true));
        boolean deposited;
        try {
            deposited = CurrencyApi.deposit(playerId, resolveCurrency(currency), amount);
        } catch (RuntimeException e) {
            deposited = false;
        }
        if (deposited) {
            journal.end(key);
            return true;
        }
        owe(state, playerId, online, attempt);
        journal.end(key);
        tell(online, Component.translatable("message.neroquests.reward_owed", Long.valueOf(amount)));
        return false;
    }

    /** What one retry of an owed entry came to. */
    private enum RetryOutcome {
        PAID,
        /** Refused for now (budget, Reserve, no provider): try again later. */
        TEMPORARY,
        /** Refused as invalid: will never succeed, stop retrying. */
        PERMANENT
    }

    /**
     * Retry everything owed to an online player, oldest first. Entries already marked permanent are
     * skipped (kept for export and a later manual claim); a temporary refusal does not stop the walk,
     * so one stuck entry can never block the rest behind it. A refused retry does not count as player
     * activity for retention.
     *
     * @param announce whether to tell the player that rewards are still owed (on join)
     */
    public static void retryOwed(ServerPlayer player, boolean announce) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        QuestProgressState state = QuestProgressState.get(server);
        UUID playerId = player.getUUID();
        if (!state.hasOwed(playerId)) {
            return;
        }
        long paid = 0L;
        for (OwedReward entry : state.owed(playerId)) {
            if (entry.permanent() || !entry.isCurrency()) {
                continue; // items and experience are handed over by OwedDelivery
            }
            OwedReward reward = entry;
            if (reward.requestKey().isEmpty()) {
                // An entry from before stable keys: mint its key once and keep it.
                String prefix = EconomyCompat.REASON_CONTRACT.equals(reward.reason())
                        ? CONTRACT_KEY_PREFIX
                        : QUEST_KEY_PREFIX;
                reward = reward.withRequestKey(state.nextRequestKey(prefix, reward.source()));
                state.updateOwed(playerId, entry, reward, false);
            }
            switch (retryOne(server, playerId, reward)) {
                case PAID -> {
                    state.updateOwed(playerId, reward, null);
                    paid += reward.amount();
                }
                case PERMANENT -> {
                    state.updateOwed(playerId, reward, reward.retried().asPermanent(), false);
                    // Told once: a permanent entry is never retried again.
                    player.sendSystemMessage(Component.translatable("message.neroquests.owed_permanent",
                            Long.valueOf(reward.amount())));
                }
                case TEMPORARY -> state.updateOwed(playerId, reward, reward.retried(), false);
            }
        }
        if (paid > 0L) {
            player.sendSystemMessage(Component.translatable("message.neroquests.owed_paid", Long.valueOf(paid)));
        }
        if (announce && state.hasOwed(playerId)) {
            long still = 0L;
            for (OwedReward reward : state.owed(playerId)) {
                if (!reward.permanent() && reward.isCurrency()) {
                    still += reward.amount();
                }
            }
            if (still > 0L) {
                player.sendSystemMessage(Component.translatable("message.neroquests.owed_pending", Long.valueOf(still)));
            }
        }
    }

    /**
     * One owed currency entry, retried on demand (NeroLink {@code claim_reward}), online or offline —
     * balances are UUID-keyed. Same request key as every earlier attempt, so it can never pay twice.
     * A permanent entry is not retried.
     *
     * @return {@code true} if it was paid now (and removed)
     */
    public static boolean claimOne(MinecraftServer server, UUID playerId, OwedReward entry) {
        if (!entry.isCurrency() || entry.permanent()) {
            return false;
        }
        QuestProgressState state = QuestProgressState.get(server);
        OwedReward reward = entry;
        if (reward.requestKey().isEmpty()) {
            String prefix = EconomyCompat.REASON_CONTRACT.equals(reward.reason())
                    ? CONTRACT_KEY_PREFIX
                    : QUEST_KEY_PREFIX;
            reward = reward.withRequestKey(state.nextRequestKey(prefix, reward.source()));
            state.updateOwed(playerId, entry, reward, false);
        }
        switch (retryOne(server, playerId, reward)) {
            case PAID -> {
                state.updateOwed(playerId, reward, null);
                return true;
            }
            case PERMANENT -> state.updateOwed(playerId, reward, reward.retried().asPermanent(), false);
            case TEMPORARY -> state.updateOwed(playerId, reward, reward.retried(), false);
        }
        return false;
    }

    /** One retry, on the same path and with the same (stored) request key as the original attempt. */
    private static RetryOutcome retryOne(MinecraftServer server, UUID playerId, OwedReward reward) {
        boolean credits = CurrencyReward.DEFAULT_CURRENCY.equals(reward.currency());
        if (credits && EconomyCompat.active()) {
            EconomyCompat.Payout payout =
                    EconomyCompat.pay(server, playerId, reward.amount(), reward.reason(), reward.requestKey());
            if (payout.ok()) {
                return RetryOutcome.PAID;
            }
            return payout.permanent() ? RetryOutcome.PERMANENT : RetryOutcome.TEMPORARY;
        }
        return CurrencyApi.hasRealProvider()
                && CurrencyApi.deposit(playerId, resolveCurrency(reward.currency()), reward.amount())
                ? RetryOutcome.PAID
                : RetryOutcome.TEMPORARY;
    }

    /** How many owed entries the per-player cap has dropped this run (a count, never whose). */
    private static int droppedThisRun;

    /** The server stopped (AUD-31): the per-run count starts again with the next server. */
    public static void serverStopped() {
        droppedThisRun = 0;
        journal = null;
        journalFor = null;
        recoveredFor = null;
    }

    // --- J1: the payout journal ---------------------------------------------------------------

    private static PayoutJournal journal;
    private static MinecraftServer journalFor;
    private static MinecraftServer recoveredFor;

    /** This server's payout journal ({@code <world>/data/neroquests_payout_journal.json}). */
    public static synchronized PayoutJournal journal(MinecraftServer server) {
        if (journalFor != server || journal == null) {
            journalFor = server;
            journal = new PayoutJournal(server.getWorldPath(LevelResource.ROOT).resolve("data")
                    .resolve(PayoutJournal.FILE_NAME));
        }
        return journal;
    }

    /**
     * Settle payouts a crash left in flight, once per server (first tick, or the first payout before it):
     * each is retried with its own request key — NeroEconomy answers DUPLICATE for one it already applied,
     * which counts as paid — and kept as owed when still refused. Counts only are logged.
     */
    public static void recoverJournal(MinecraftServer server) {
        synchronized (CurrencyPayouts.class) {
            if (recoveredFor == server) {
                return;
            }
            recoveredFor = server;
        }
        QuestProgressState state = QuestProgressState.get(server);
        int[] unsettled = {0};
        int found = PayoutJournal.recover(journal(server), new PayoutJournal.Settler() {
            @Override
            public boolean retry(UUID player, OwedReward reward) {
                return retryOne(server, player, reward) == RetryOutcome.PAID;
            }

            @Override
            public void keep(UUID player, OwedReward reward) {
                if (state.owed(player).stream().noneMatch(entry -> entry.requestKey().equals(reward.requestKey()))) {
                    owe(state, player, null, reward);
                }
            }

            @Override
            public void unsettled(UUID player, OwedReward reward) {
                unsettled[0]++;
            }
        });
        if (unsettled[0] > 0) {
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] {} currency deposit(s) through Neroland Core were interrupted by an "
                    + "unclean stop; Core's deposit takes no request key, so they were not repeated (they may or may "
                    + "not have been paid).", Integer.valueOf(unsettled[0]));
        }
        if (found > 0) {
            NeroQuestsCommon.LOGGER.info("[NeroQuests] Settled {} payout(s) left in flight by an unclean stop.",
                    Integer.valueOf(found));
        }
    }

    /** Add an entry to the shared bounded owed list; tells the player (and counts) when the cap drops one. */
    static void owe(QuestProgressState state, UUID playerId, ServerPlayer online, OwedReward reward) {
        int dropped = state.addOwed(playerId, reward);
        if (dropped > 0) {
            droppedThisRun += dropped;
            tell(online, Component.translatable("message.neroquests.owed_dropped", Integer.valueOf(dropped)));
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] The owed-reward cap dropped {} oldest entry(ies); {} dropped "
                    + "this run.", Integer.valueOf(dropped), Integer.valueOf(droppedThisRun));
        }
    }

    private static void tell(ServerPlayer online, Component message) {
        if (online != null) {
            online.sendSystemMessage(message);
        }
    }

    /**
     * The {@link Currency} for an id. The shared credits constant is reused so its translation key
     * matches Core's; any other id gets the conventional {@code currency.<namespace>.<path>} key.
     */
    static Currency resolveCurrency(Identifier currency) {
        if (CurrencyReward.DEFAULT_CURRENCY.equals(currency)) {
            return CoreCurrencies.CREDITS;
        }
        return Currency.of(currency, "currency." + currency.getNamespace() + "." + currency.getPath());
    }
}
