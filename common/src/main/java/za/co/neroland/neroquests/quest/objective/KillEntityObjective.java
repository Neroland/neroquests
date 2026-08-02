package za.co.neroland.neroquests.quest.objective;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.engine.ObjectiveContext;

/**
 * Objective {@code neroquests:kill_entity} — kill this many matching creatures.
 *
 * <pre>{@code { "type": "neroquests:kill_entity", "tag": "minecraft:skeletons", "count": 10 }}</pre>
 *
 * <p>A kill counts for whoever the game credits with it — the direct attacker if that is a player,
 * otherwise the entity's own kill-credit (which covers arrows, pets and other indirect kills).
 * Deaths with no player behind them credit nobody. The tally never goes down.
 */
public record KillEntityObjective(EntityTarget selector, int count) implements ObjectiveSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "kill_entity");

    public static final MapCodec<KillEntityObjective> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            EntityTarget.MAP_CODEC.forGetter(KillEntityObjective::selector),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1)
                    .forGetter(KillEntityObjective::count)
    ).apply(inst, KillEntityObjective::new));

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    @Override
    public int target() {
        return count;
    }

    @Override
    public int creditKill(Entity victim, ObjectiveContext context) {
        return selector.matches(victim) ? 1 : 0;
    }

    @Override
    public boolean contentPresent(ObjectiveContext context) {
        return selector.present();
    }

    @Override
    public String contentLabel() {
        return selector.label();
    }
}
