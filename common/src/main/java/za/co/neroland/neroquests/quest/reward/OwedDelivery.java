package za.co.neroland.neroquests.quest.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroquests.compat.neroeconomy.EconomyCompat;
import za.co.neroland.neroquests.data.OwedReward;
import za.co.neroland.neroquests.data.QuestProgressState;

/**
 * The item and experience half of the owed-reward queue (AUD-05). Currency lives in
 * {@link CurrencyPayouts}; both share one bounded per-player list in {@link QuestProgressState}.
 *
 * <p>A reward that needs a body — items, experience — is <b>kept instead of lost</b> when its player
 * cannot take it right now: offline, dead, disconnecting, or (items) with no inventory room. Kept
 * entries are delivered on join, on respawn and on the periodic owed retry, oldest first; an item
 * entry may be delivered in part (the rest stays). Each entry is identified by its request key, so a
 * delivery removes or shrinks exactly that entry and can never hand it over twice.
 *
 * <p>Privacy (POPIA/GDPR): entries live in the quest progress store under the player's game UUID,
 * exported with {@code /neroquests export} and erased with the player. Nothing here logs a player
 * id or name.
 *
 * <p>Server thread only.
 */
public final class OwedDelivery {

    /** Request-key prefix of owed item entries. */
    public static final String ITEM_KEY_PREFIX = "nq:i:";

    /** Request-key prefix of owed experience entries. */
    public static final String XP_KEY_PREFIX = "nq:x:";

    private OwedDelivery() {
    }

    /** One entry (or part of one) that was just handed over. */
    public record Delivered(OwedReward.Kind kind, Identifier id, long amount) {
    }

    /** Whether {@code player} can take a body-bound reward right now (online, alive, not leaving). */
    public static boolean canReceive(ServerPlayer player) {
        return player != null && player.isAlive() && !player.hasDisconnected();
    }

    // --- queueing --------------------------------------------------------------

    /**
     * Keep {@code count} of {@code item} (with {@code patch}) for the player and tell them, if they
     * are here to be told.
     */
    public static void keepItem(MinecraftServer server, UUID playerId, ServerPlayer online, Item item, int count,
                                DataComponentPatch patch, Identifier source) {
        if (count <= 0) {
            return;
        }
        QuestProgressState state = QuestProgressState.get(server);
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        OwedReward entry = OwedReward.item(state.nextRequestKey(ITEM_KEY_PREFIX, source), id, count,
                encodePatch(server, patch), EconomyCompat.REASON_QUEST, source, System.currentTimeMillis());
        CurrencyPayouts.owe(state, playerId, online, entry);
        if (online != null && !online.hasDisconnected()) {
            online.sendSystemMessage(online.isAlive()
                    ? Component.translatable("message.neroquests.reward_kept_items", Integer.valueOf(count),
                            item.getName(new ItemStack(item)))
                    : Component.translatable("message.neroquests.reward_kept_respawn"));
        }
    }

    /** Keep {@code points} of experience for the player and tell them, if they are here to be told. */
    public static void keepXp(MinecraftServer server, UUID playerId, ServerPlayer online, int points,
                              Identifier source) {
        if (points <= 0) {
            return;
        }
        QuestProgressState state = QuestProgressState.get(server);
        OwedReward entry = OwedReward.xp(state.nextRequestKey(XP_KEY_PREFIX, source), points,
                EconomyCompat.REASON_QUEST, source, System.currentTimeMillis());
        CurrencyPayouts.owe(state, playerId, online, entry);
        if (online != null && !online.hasDisconnected()) {
            online.sendSystemMessage(Component.translatable("message.neroquests.reward_kept_respawn"));
        }
    }

    // --- handing over ----------------------------------------------------------

    /**
     * Put up to {@code count} of {@code item} into the player's inventory, whole stacks at a time.
     *
     * @return how many did not fit (0 when everything went in)
     */
    public static int insert(ServerPlayer player, Item item, int count, DataComponentPatch patch) {
        int remaining = Math.max(0, count);
        while (remaining > 0) {
            ItemStack stack = new ItemStack(item);
            if (patch != null && !patch.isEmpty()) {
                stack.applyComponents(patch);
            }
            int size = Math.min(remaining, Math.max(1, stack.getMaxStackSize()));
            stack.setCount(size);
            player.getInventory().add(stack); // shrinks the stack by what went in
            int left = Math.max(0, stack.getCount());
            remaining -= size - left;
            if (left > 0) {
                break; // no more room
            }
        }
        return remaining;
    }

    /**
     * Hand every kept item and experience entry to an online, living player, oldest first. An item
     * entry that only partly fits is shrunk to the rest; one that does not fit at all stays. Entries
     * whose item is not registered here (a mod was removed) stay too, untouched.
     *
     * @param source   only entries from this quest/contract id, or {@code null} for all
     * @param announce whether to tell the player what is still kept (on join and respawn)
     * @return what was handed over (empty when nothing was)
     */
    public static List<Delivered> deliver(ServerPlayer player, Identifier source, boolean announce) {
        return deliver(player, source, null, announce);
    }

    /**
     * {@link #deliver(ServerPlayer, Identifier, boolean)} limited to the entry whose request key is
     * {@code key} (when not null) — the link {@code claim_reward} {@code key} parameter (G1).
     */
    public static List<Delivered> deliver(ServerPlayer player, Identifier source, String key, boolean announce) {
        List<Delivered> delivered = new ArrayList<>();
        MinecraftServer server = player.level().getServer();
        if (server == null || !canReceive(player)) {
            return delivered;
        }
        QuestProgressState state = QuestProgressState.get(server);
        UUID playerId = player.getUUID();
        if (!state.hasOwed(playerId)) {
            return delivered;
        }
        delivered.addAll(deliverTo(state, playerId, source, key, new Receiver() {
            @Override
            public int acceptItems(Identifier id, int count, CompoundTag components) {
                if (!BuiltInRegistries.ITEM.containsKey(id)) {
                    RewardLog.warnOnce("owed-item-missing:" + id,
                            "[NeroQuests] A kept item reward names '{}', which is not registered here; it stays kept.",
                            id);
                    return 0;
                }
                Item item = BuiltInRegistries.ITEM.getValue(id);
                return count - insert(player, item, count, decodePatch(server, components));
            }

            @Override
            public void acceptXp(int points) {
                player.giveExperiencePoints(points);
            }
        }));
        if (!delivered.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.neroquests.owed_delivered",
                    Integer.valueOf(delivered.size())));
        }
        if (announce) {
            int still = pendingBodyBound(state, playerId);
            if (still > 0) {
                player.sendSystemMessage(Component.translatable("message.neroquests.owed_kept_pending",
                        Integer.valueOf(still)));
            }
        }
        return delivered;
    }

    /** Where kept entries are handed to: a live player, or a test double. */
    public interface Receiver {

        /**
         * Take up to {@code count} of item {@code id}.
         *
         * @return how many were taken (0 when none fit or the item is unknown here)
         */
        int acceptItems(Identifier id, int count, CompoundTag components);

        /** Take {@code points} of experience (always succeeds). */
        void acceptXp(int points);
    }

    /**
     * The queue walk behind {@link #deliver}: every kept item/experience entry of {@code playerId}
     * (optionally only from {@code source}), oldest first, handed to {@code receiver}. A fully taken
     * entry is removed by its key, a partly taken item entry shrunk to the rest, an untaken one left
     * alone. Currency entries and unkeyed entries are never touched.
     */
    public static List<Delivered> deliverTo(QuestProgressState state, UUID playerId, Identifier source,
                                            Receiver receiver) {
        return deliverTo(state, playerId, source, null, receiver);
    }

    /** {@link #deliverTo(QuestProgressState, UUID, Identifier, Receiver)}, only the entry keyed {@code key} when given. */
    public static List<Delivered> deliverTo(QuestProgressState state, UUID playerId, Identifier source, String key,
                                            Receiver receiver) {
        List<Delivered> delivered = new ArrayList<>();
        for (OwedReward entry : state.owed(playerId)) {
            if (entry.isCurrency() || (source != null && !source.equals(entry.source()))
                    || (key != null && !key.equals(entry.requestKey()))) {
                continue;
            }
            if (entry.requestKey().isEmpty()) {
                continue; // every item/xp entry is keyed at creation; never touch one that is not
            }
            int amount = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, entry.amount()));
            if (entry.kind() == OwedReward.Kind.XP) {
                // Removed first: the entry is gone before anything is granted, so it can never be granted twice.
                if (state.replaceOwedByKey(playerId, entry.requestKey(), null)) {
                    receiver.acceptXp(amount);
                    delivered.add(new Delivered(OwedReward.Kind.XP, entry.item(), amount));
                }
                continue;
            }
            int taken = Math.max(0, Math.min(amount, receiver.acceptItems(entry.item(), amount, entry.components())));
            if (taken <= 0) {
                continue;
            }
            int left = amount - taken;
            state.replaceOwedByKey(playerId, entry.requestKey(), left > 0 ? entry.withAmount(left) : null);
            delivered.add(new Delivered(OwedReward.Kind.ITEM, entry.item(), taken));
        }
        return delivered;
    }

    /** How many item/experience entries are still kept for a player. */
    public static int pendingBodyBound(QuestProgressState state, UUID playerId) {
        int count = 0;
        for (OwedReward entry : state.owed(playerId)) {
            if (!entry.isCurrency()) {
                count++;
            }
        }
        return count;
    }

    // --- component patch (registry-aware) ----------------------------------------

    /** The patch as NBT, encoded with the server's registries; empty for a plain stack. */
    static CompoundTag encodePatch(MinecraftServer server, DataComponentPatch patch) {
        if (patch == null || patch.isEmpty()) {
            return new CompoundTag();
        }
        Tag tag = DataComponentPatch.CODEC
                .encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), patch)
                .result().orElse(null);
        return tag instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    /** The stored patch, decoded with the server's registries; empty if absent or undecodable. */
    static DataComponentPatch decodePatch(MinecraftServer server, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return DataComponentPatch.EMPTY;
        }
        return DataComponentPatch.CODEC
                .parse(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
                .result().orElse(DataComponentPatch.EMPTY);
    }
}
