package za.co.neroland.neroquests.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroquests.compat.neroeconomy.EconomyCompat;
import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.quest.reward.CurrencyPayouts;
import za.co.neroland.neroquests.quest.reward.CurrencyReward;

/**
 * Daily contracts: each UTC day a few contracts are drawn from the data-driven pool (the same draw
 * for everyone), and each player may hand in each one once that day for credits.
 *
 * <p><b>Budget-first.</b> A contract is offered to a player only when the economy's budgeted payout
 * API says today's budget can still pay its reward ({@code canPay} and {@code remainingToday}), and
 * rewards are paid with reason {@code daily_contract}. With no such economy (NeroEconomy absent or
 * too old) nothing is offered at all: there is no unbudgeted fallback for a repeatable faucet.
 *
 * <p><b>Hand-in, not show-and-tell.</b> Submitting takes the items, so one item set cannot be shown on
 * every alternate account. Should the payout still be refused after the items are taken (the budget
 * ran out between the check and the payout), the reward is kept as owed and retried like a quest
 * reward.
 *
 * <p>Privacy (POPIA/GDPR): per player only the day and the contract ids done that day are stored,
 * with the rest of the quest data (exported and erased with it). Nothing is logged about who.
 */
public final class DailyContracts {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    private DailyContracts() {
    }

    /** Today's UTC day number. */
    public static long today() {
        return Math.floorDiv(System.currentTimeMillis(), MILLIS_PER_DAY);
    }

    /** Whether contracts can be offered at all right now. */
    public static boolean available() {
        return NeroQuestsConfig.DAILY_CONTRACTS_ENABLED.get() && EconomyCompat.active();
    }

    /** The contracts drawn for today: a weighted draw without repeats, seeded by the day. */
    public static List<DailyContract> drawn(MinecraftServer server) {
        List<DailyContract> pool = new ArrayList<>(ContractDefinitions.forServer(server));
        int wanted = Math.min(NeroQuestsConfig.DAILY_CONTRACTS_PER_DAY.get(), pool.size());
        Random random = new Random(today() * 0x9E3779B97F4A7C15L + pool.size());
        List<DailyContract> out = new ArrayList<>(wanted);
        while (out.size() < wanted && !pool.isEmpty()) {
            int total = 0;
            for (DailyContract contract : pool) {
                total += contract.weight();
            }
            int roll = random.nextInt(total);
            for (int i = 0; i < pool.size(); i++) {
                roll -= pool.get(i).weight();
                if (roll < 0) {
                    out.add(pool.remove(i));
                    break;
                }
            }
        }
        return out;
    }

    /** One line of a player's offer. */
    public record Offer(DailyContract contract, boolean done, boolean payable) {
    }

    /** Today's contracts as seen by {@code player}: done today, and whether the budget can pay it. */
    public static List<Offer> offerFor(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null || !available()) {
            return List.of();
        }
        QuestProgressState state = QuestProgressState.get(server);
        long day = today();
        long remaining = EconomyCompat.remainingToday(server);
        List<Offer> offers = new ArrayList<>();
        for (DailyContract contract : drawn(server)) {
            boolean done = state.contractDone(player.getUUID(), day, contract.id());
            boolean payable = !done && contract.reward() <= remaining
                    && EconomyCompat.canPay(server, player.getUUID(), contract.reward());
            offers.add(new Offer(contract, done, payable));
        }
        return offers;
    }

    /** Why a submission did not go through, or {@link Result#PAID} / {@link Result#OWED}. */
    public enum Result {
        PAID, OWED, UNAVAILABLE, NOT_OFFERED, ALREADY_DONE, NOT_PAYABLE, NOT_ENOUGH_ITEMS
    }

    /** Hand in today's contract {@code id} for {@code player}. */
    public static Result submit(ServerPlayer player, Identifier id) {
        MinecraftServer server = player.level().getServer();
        if (server == null || !available()) {
            return Result.UNAVAILABLE;
        }
        Optional<Offer> match = offerFor(player).stream().filter(o -> o.contract().id().equals(id)).findFirst();
        if (match.isEmpty()) {
            return Result.NOT_OFFERED;
        }
        Offer offer = match.get();
        if (offer.done()) {
            return Result.ALREADY_DONE;
        }
        if (!offer.payable()) {
            return Result.NOT_PAYABLE;
        }
        DailyContract contract = offer.contract();
        if (held(player, contract) < contract.count()) {
            return Result.NOT_ENOUGH_ITEMS;
        }
        take(player, contract);
        QuestProgressState.get(server).markContractDone(player.getUUID(), today(), contract.id());
        boolean paid = CurrencyPayouts.pay(server, player.getUUID(), player, CurrencyReward.DEFAULT_CURRENCY,
                contract.reward(), EconomyCompat.REASON_CONTRACT, CurrencyPayouts.CONTRACT_KEY_PREFIX, contract.id());
        return paid ? Result.PAID : Result.OWED;
    }

    private static int held(ServerPlayer player, DailyContract contract) {
        Inventory inventory = player.getInventory();
        int held = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (contract.target().matches(stack)) {
                held += stack.getCount();
            }
        }
        return held;
    }

    private static void take(ServerPlayer player, DailyContract contract) {
        Inventory inventory = player.getInventory();
        int remaining = contract.count();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (contract.target().matches(stack)) {
                int taken = Math.min(remaining, stack.getCount());
                stack.shrink(taken);
                remaining -= taken;
            }
        }
        inventory.setChanged();
    }
}
