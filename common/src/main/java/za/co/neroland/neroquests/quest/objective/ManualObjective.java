package za.co.neroland.neroquests.quest.objective;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.engine.QuestEngine;

/**
 * Objective {@code neroquests:manual} (C5) — the escape hatch for "build a thing" quests: it never
 * advances by itself and is ticked only by an authorised person with
 * {@code /neroquests manual complete <quest> <player>}.
 *
 * <pre>{@code { "type": "neroquests:manual", "label": "quest.mypack.build/tower.manual" }}</pre>
 *
 * <p>{@code label} is a translation key the book shows as the objective's text. The objective neither
 * measures nor answers any trigger event; the only credit it ever receives is the one
 * {@link #creditFor} builds, which only the command path (through
 * {@link QuestEngine#manualComplete}) hands to the engine. Each use is counted as an admin mutation
 * (count only, never who).
 */
public record ManualObjective(String label) implements ObjectiveSpec {

    public static final Identifier TYPE_ID =
            Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "manual");

    private static final Codec<String> LABEL = Codec.STRING.flatXmap(ManualObjective::validateLabel,
            ManualObjective::validateLabel);

    private static DataResult<String> validateLabel(String label) {
        return label.length() >= 1 && label.length() <= 256
                ? DataResult.success(label)
                : DataResult.error(() -> "a manual objective label must have 1 to 256 characters");
    }

    public static final MapCodec<ManualObjective> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
            LABEL.fieldOf("label").forGetter(ManualObjective::label)
    ).apply(inst, ManualObjective::new));

    @Override
    public Identifier typeId() {
        return TYPE_ID;
    }

    /**
     * The one credit that ticks manual objectives: 1 for every {@code manual} objective of {@code quest},
     * 0 for anything else.
     */
    public static QuestEngine.Credit creditFor(Identifier quest) {
        return (objective, context) -> objective instanceof ManualObjective
                && quest.equals(context.quest()) ? 1 : 0;
    }

    /**
     * The type id only: in a custom quest the label is author text, which must never reach a log line (D3).
     */
    @Override
    public String contentLabel() {
        return TYPE_ID.toString();
    }
}
