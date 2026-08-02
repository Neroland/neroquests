package za.co.neroland.neroquests.quest;

import com.mojang.serialization.MapCodec;

import net.minecraft.resources.Identifier;

/**
 * A registered objective kind: its type id and the codec for its JSON body (everything
 * except the {@code type} field, which {@link ObjectiveSpec#CODEC} writes itself).
 *
 * @param <T> the concrete {@link ObjectiveSpec} record this type produces
 */
public record ObjectiveType<T extends ObjectiveSpec>(Identifier id, MapCodec<T> codec) {
}
