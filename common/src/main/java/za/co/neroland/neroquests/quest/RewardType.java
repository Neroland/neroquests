package za.co.neroland.neroquests.quest;

import com.mojang.serialization.MapCodec;

import net.minecraft.resources.Identifier;

/**
 * A registered reward kind: its type id and the codec for its JSON body (everything except
 * the {@code type} field, which {@link RewardSpec#CODEC} writes itself).
 *
 * @param <T> the concrete {@link RewardSpec} record this type produces
 */
public record RewardType<T extends RewardSpec>(Identifier id, MapCodec<T> codec) {
}
