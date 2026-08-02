package za.co.neroland.neroquests.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * Reward {@code neroquests:xp} — grants raw experience points on completion.
 *
 * <pre>{@code { "type": "neroquests:xp", "amount": 50 }}</pre>
 *
 * <p>{@code amount} is required and must be at least 1; a zero or negative amount fails the
 * codec, which drops the owning quest with a logged warning rather than silently granting
 * nothing.
 */
public record XpReward(int amount) implements RewardSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "xp");

    public static final MapCodec<XpReward> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("amount").forGetter(XpReward::amount)
    ).apply(inst, XpReward::new));

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }
}
