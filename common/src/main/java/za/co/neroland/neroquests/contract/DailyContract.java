package za.co.neroland.neroquests.contract;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.objective.ItemTarget;

/**
 * One daily contract: hand in {@code count} matching items, get {@code reward} credits. Loaded from
 * {@code data/<namespace>/neroquests/contracts/<path>.json}; the id is taken from the file path.
 *
 * <pre>{@code { "title": "contract.mypack.wheat", "item": "minecraft:wheat", "count": 64, "reward": 40 }}</pre>
 *
 * @param id     the contract id
 * @param title  a translation key (or plain text) naming the contract
 * @param target the {@code item} or {@code tag} to hand in (exactly one)
 * @param count  how many to hand in
 * @param reward credits paid on submission (1 to 1,000)
 * @param weight relative chance of being drawn for a day
 */
public record DailyContract(Identifier id, String title, ItemTarget target, int count, long reward, int weight) {

    /** The file body: everything except the id. */
    public record Data(String title, ItemTarget target, int count, long reward, int weight) {

        public static final Codec<Data> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.STRING.optionalFieldOf("title", "").forGetter(Data::title),
                ItemTarget.MAP_CODEC.forGetter(Data::target),
                Codec.intRange(1, 4096).optionalFieldOf("count", 1).forGetter(Data::count),
                Codec.LONG.validate(value -> value != null && value.longValue() >= 1L && value.longValue() <= 1000L
                                ? DataResult.success(value)
                                : DataResult.error(() -> "a contract's 'reward' must be 1 to 1000 credits"))
                        .fieldOf("reward").forGetter(Data::boxedReward),
                Codec.intRange(1, 1000).optionalFieldOf("weight", 1).forGetter(Data::weight)
        ).apply(inst, Data::of));

        private static Data of(String title, ItemTarget target, Integer count, Long reward, Integer weight) {
            return new Data(title, target, count.intValue(), reward.longValue(), weight.intValue());
        }

        private Long boxedReward() {
            return Long.valueOf(reward);
        }
    }

    public DailyContract(Identifier id, Data data) {
        this(id, data.title().isEmpty() ? "contract." + id.getNamespace() + "." + id.getPath().replace('/', '.')
                : data.title(), data.target(), data.count(), data.reward(), data.weight());
    }
}
