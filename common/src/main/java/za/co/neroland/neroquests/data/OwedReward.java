package za.co.neroland.neroquests.data;

import java.util.Locale;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

/**
 * A reward that could not be handed over when it was earned, kept for the player and delivered later.
 *
 * <ul>
 *   <li>{@link Kind#CURRENCY} — the economy refused it (daily budget spent, Reserve short, rate limit)
 *       or the currency provider reported a failed deposit; retried later with the <em>same</em> request
 *       key.</li>
 *   <li>{@link Kind#ITEM} — the player was offline, dead or had no inventory room (AUD-05). Stored as
 *       item id ({@link #currency()} holds it), count ({@link #amount()}) and the stack's component
 *       patch, encoded registry-aware when queued ({@link #components()}, empty for plain stacks).
 *       Delivered on join, on respawn and periodically, oldest first, possibly in part.</li>
 *   <li>{@link Kind#XP} — experience points ({@link #amount()}) for a player who was offline or dead.</li>
 * </ul>
 *
 * <p>Every entry carries a stable request key, which is its identity: delivery removes or shrinks
 * the entry with that key, so it can never be handed over twice.
 *
 * <p>Privacy (POPIA/GDPR): stored under the player's game UUID in the quest progress store, exported
 * with their quest data and erased with it. The request key never holds a UUID or a name: it is
 * {@code nq:<sequence>:<source id>}.
 *
 * @param requestKey the idempotency key of the original attempt ({@code ""} when the payout path takes none)
 * @param currency   the currency id
 * @param amount     credits owed (at least 1)
 * @param reason     the payout reason code ({@code quest_reward} or {@code daily_contract})
 * @param source     the quest or contract id the reward came from
 * @param createdAt  epoch millis of the first attempt
 * @param attempts   how many retries have been refused since
 * @param permanent  the economy refused it as invalid (e.g. above its single-payout cap): it is kept,
 *                   exported and erasable like any owed entry, but no longer retried automatically
 * @param kind       what is owed (currency by default, for entries written before AUD-05)
 * @param components for an item entry, the stack's component patch as NBT (registry-aware encoded); empty
 *                   otherwise
 */
public record OwedReward(String requestKey, Identifier currency, long amount, String reason, Identifier source,
                         long createdAt, int attempts, boolean permanent, Kind kind, CompoundTag components) {

    /** What an owed entry holds. */
    public enum Kind {
        CURRENCY, ITEM, XP;

        /** The lower-case name used in the save file and in exports. */
        public String serialName() {
            return name().toLowerCase(Locale.ROOT);
        }

        static final Codec<Kind> CODEC = Codec.STRING.comapFlatMap(Kind::parse, Kind::serialName);

        private static DataResult<Kind> parse(String raw) {
            for (Kind kind : values()) {
                if (kind.serialName().equals(raw)) {
                    return DataResult.success(kind);
                }
            }
            return DataResult.error(() -> "unknown owed-reward kind '" + raw + "'");
        }
    }

    /** A currency entry (permanent flag given). */
    public OwedReward(String requestKey, Identifier currency, long amount, String reason, Identifier source,
                      long createdAt, int attempts, boolean permanent) {
        this(requestKey, currency, amount, reason, source, createdAt, attempts, permanent, Kind.CURRENCY,
                new CompoundTag());
    }

    /** A retryable currency entry. */
    public OwedReward(String requestKey, Identifier currency, long amount, String reason, Identifier source,
                      long createdAt, int attempts) {
        this(requestKey, currency, amount, reason, source, createdAt, attempts, false);
    }

    /** Defensive copy of the mutable NBT, so an entry never shares a tag with its caller. */
    public OwedReward {
        components = components == null ? new CompoundTag() : components.copy();
        kind = kind == null ? Kind.CURRENCY : kind;
    }

    /** An owed item stack of {@code count} (at least 1). */
    public static OwedReward item(String requestKey, Identifier item, int count, CompoundTag components,
                                  String reason, Identifier source, long createdAt) {
        return new OwedReward(requestKey, item, count, reason, source, createdAt, 0, false, Kind.ITEM, components);
    }

    /** Owed experience points. */
    public static OwedReward xp(String requestKey, int points, String reason, Identifier source, long createdAt) {
        return new OwedReward(requestKey, XP_ID, points, reason, source, createdAt, 0, false, Kind.XP,
                new CompoundTag());
    }

    /** Placeholder id stored in the {@code currency} slot of an XP entry. */
    private static final Identifier XP_ID = Identifier.fromNamespaceAndPath("minecraft", "experience");

    /** Whether this is a currency entry (the only kind the economy retry path and an offline claim handle). */
    public boolean isCurrency() {
        return kind == Kind.CURRENCY;
    }

    /** For an item entry, the item id (stored in the {@code currency} slot). */
    public Identifier item() {
        return currency;
    }

    /** A copy holding {@code remaining} instead of {@link #amount()} (partial item delivery). */
    public OwedReward withAmount(long remaining) {
        return new OwedReward(requestKey, currency, remaining, reason, source, createdAt, attempts, permanent, kind,
                components);
    }

    public static final Codec<OwedReward> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.optionalFieldOf("key", "").forGetter(OwedReward::requestKey),
            Identifier.CODEC.fieldOf("currency").forGetter(OwedReward::currency),
            Codec.LONG.fieldOf("amount").forGetter(OwedReward::amount),
            Codec.STRING.optionalFieldOf("reason", "quest_reward").forGetter(OwedReward::reason),
            Identifier.CODEC.fieldOf("source").forGetter(OwedReward::source),
            Codec.LONG.optionalFieldOf("created_at", 0L).forGetter(OwedReward::createdAt),
            Codec.INT.optionalFieldOf("attempts", 0).forGetter(OwedReward::attempts),
            Codec.BOOL.optionalFieldOf("permanent", Boolean.FALSE).forGetter(OwedReward::boxedPermanent),
            Kind.CODEC.optionalFieldOf("kind", Kind.CURRENCY).forGetter(OwedReward::kind),
            CompoundTag.CODEC.optionalFieldOf("components", new CompoundTag()).forGetter(OwedReward::components)
    ).apply(inst, OwedReward::decode));

    private static OwedReward decode(String requestKey, Identifier currency, Long amount, String reason,
                                     Identifier source, Long createdAt, Integer attempts, Boolean permanent,
                                     Kind kind, CompoundTag components) {
        return new OwedReward(requestKey, currency, amount.longValue(), reason, source, createdAt.longValue(),
                attempts.intValue(), permanent.booleanValue(), kind, components);
    }

    private Boolean boxedPermanent() {
        return Boolean.valueOf(permanent);
    }

    /** A copy with one more refused attempt counted. */
    public OwedReward retried() {
        return new OwedReward(requestKey, currency, amount, reason, source, createdAt,
                attempts == Integer.MAX_VALUE ? attempts : attempts + 1, permanent, kind, components);
    }

    /** A copy marked as permanently refused (no more automatic retries). */
    public OwedReward asPermanent() {
        return new OwedReward(requestKey, currency, amount, reason, source, createdAt, attempts, true, kind,
                components);
    }

    /** A copy carrying {@code key} as its (stable) request key. */
    public OwedReward withRequestKey(String key) {
        return new OwedReward(key, currency, amount, reason, source, createdAt, attempts, permanent, kind,
                components);
    }
}
