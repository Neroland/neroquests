package za.co.neroland.neroquests.quest.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

//? if >=1.20.5 {
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.nbt.StringTag;
//?}
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
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
     * Keep {@code count} of {@code item} (with the metadata of {@code template}) for the player and tell them, if they
     * are here to be told.
     */
    public static void keepItem(MinecraftServer server, UUID playerId, ServerPlayer online, ItemStack template, int count,
                                Identifier source) {
        if (count <= 0) {
            return;
        }
        QuestProgressState state = QuestProgressState.get(server);
        Item item = template.getItem();
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        OwedReward entry = OwedReward.item(state.nextRequestKey(ITEM_KEY_PREFIX, source), id, count,
                encodePatch(server, template), EconomyCompat.REASON_QUEST, source, System.currentTimeMillis());
        CurrencyPayouts.owe(state, playerId, online, entry);
        if (online != null && !online.hasDisconnected()) {
            online.sendSystemMessage(online.isAlive()
                    ? Component.translatable("message.neroquests.reward_kept_items", Integer.valueOf(count),
                            item.getName(new ItemStack(item)))
                    : Component.translatable("message.neroquests.reward_kept_respawn"));
        }
    }

    /**
     * Keep an escrow refund of {@code count} plain {@code item}s for a custom quest's author (J2, RT-10): queued
     * with reason {@link QuestProgressState#REASON_ESCROW_REFUND}, which custom-quest rewards can never evict.
     */
    public static void keepRefund(MinecraftServer server, UUID playerId, ServerPlayer online, Item item, int count,
                                  Identifier source) {
        if (count <= 0) {
            return;
        }
        QuestProgressState state = QuestProgressState.get(server);
        OwedReward entry = OwedReward.item(state.nextRequestKey(ITEM_KEY_PREFIX, source),
                BuiltInRegistries.ITEM.getKey(item), count, new CompoundTag(), QuestProgressState.REASON_ESCROW_REFUND,
                source, System.currentTimeMillis());
        CurrencyPayouts.owe(state, playerId, online, entry);
        if (online != null && !online.hasDisconnected()) {
            online.sendSystemMessage(Component.translatable("message.neroquests.reward_kept_items",
                    Integer.valueOf(count), item.getName(new ItemStack(item))));
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
    public static int insert(ServerPlayer player, Item item, int count) {
        return insert(player, new ItemStack(item), count);
    }

    public static int insert(ServerPlayer player, ItemStack template, int count) {
        int remaining = Math.max(0, count);
        while (remaining > 0) {
            ItemStack stack = template.copy();
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
                ItemStack template = new ItemStack(item);
                if (!applyPatch(server, template, components)) {
                    RewardLog.warnOnce("owed-item-data:" + id,
                            "[NeroQuests] A kept item reward for '{}' has incompatible item data; it stays kept.", id);
                    return 0;
                }
                return count - insert(player, template, count);
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
            // J2 (RT-17): items too leave the queue before they are handed over; what does not fit (or everything,
            // if the hand-over fails) is put back in the same place. No caller — on whatever thread — can see the
            // entry while it is being delivered, so it can never be handed over twice.
            int index = state.takeOwedByKey(playerId, entry.requestKey());
            if (index < 0) {
                continue; // already settled by someone else
            }
            int taken = 0;
            try {
                taken = Math.max(0, Math.min(amount, receiver.acceptItems(entry.item(), amount, entry.components())));
            } finally {
                int left = amount - taken;
                state.settleTaken(playerId, index, entry, left > 0 ? entry.withAmount(left) : null);
            }
            if (taken <= 0) {
                continue;
            }
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
    static CompoundTag encodePatch(MinecraftServer server, ItemStack template) {
        //? if >=1.20.5 {
        DataComponentPatch patch = template.getComponentsPatch();
        if (patch.isEmpty()) {
            return new CompoundTag();
        }
        Tag tag = DataComponentPatch.CODEC
                .encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), patch)
                .result().orElse(null);
        return tag instanceof CompoundTag compound ? compound : new CompoundTag();
        //?} else {
        /*CompoundTag result = new CompoundTag();
        if (template.hasTag()) {
            result.put("legacy_item_tag", template.getTag().copy());
            NbtUtils.addCurrentDataVersion(result);
        }
        return result;
        *///?}
    }

    static boolean applyPatch(MinecraftServer server, ItemStack template, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return true;
        }
        //? if >=1.20.5 {
        var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var patch = tag.get("legacy_item_tag") instanceof CompoundTag itemTag
                ? upgradeLegacyItem(BuiltInRegistries.ITEM.getKey(template.getItem()), tag, itemTag, ops)
                : DataComponentPatch.CODEC.parse(ops, tag).result();
        patch.ifPresent(template::applyComponents);
        return patch.isPresent();
        //?} else {
        /*if (!tag.contains("legacy_item_tag", Tag.TAG_COMPOUND)) {
            return false;
        }
        template.setTag(tag.getCompound("legacy_item_tag").copy());
        return true;
        *///?}
    }

    //? if >=1.20.5 {
    static Optional<DataComponentPatch> upgradeLegacyItem(Identifier itemId, CompoundTag stored,
            CompoundTag itemTag, DynamicOps<Tag> ops) {
        int sourceVersion = NbtUtils.getDataVersion(stored, -1);
        if (sourceVersion < 0) {
            return Optional.empty();
        }
        CompoundTag legacy = new CompoundTag();
        legacy.putString("id", itemId.toString());
        legacy.putByte("Count", (byte) 1);
        legacy.put("tag", itemTag.copy());
        var current = NbtUtils.addCurrentDataVersion(new CompoundTag());
        int targetVersion = NbtUtils.getDataVersion(current, -1);
        var upgraded = DataFixers.getDataFixer().update(
                References.ITEM_STACK,
                new Dynamic<>(NbtOps.INSTANCE, legacy), sourceVersion, targetVersion);
        if (!(upgraded.getValue() instanceof CompoundTag item)
                || !StringTag.valueOf(itemId.toString()).equals(item.get("id"))) {
            return Optional.empty();
        }
        Tag components = item.get("components");
        return components == null ? Optional.of(DataComponentPatch.EMPTY)
                : DataComponentPatch.CODEC.parse(ops, components).result();
    }
    //?}
}
