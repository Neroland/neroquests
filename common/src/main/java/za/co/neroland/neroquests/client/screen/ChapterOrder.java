package za.co.neroland.neroquests.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.QuestSource;

/**
 * The chapter column's order (E2), pure: the story spine first (in id order, as the book always showed it),
 * then mod packs grouped by namespace, then custom chapters <em>below</em> them by their {@code order} extra
 * (ties by id). Also the reorder rule for the up/down buttons.
 */
public final class ChapterOrder {

    /** What the sort needs to know about one chapter. */
    public record Row(Identifier id, QuestSource source, int order) {
    }

    /** One chapter's new sort key after a reorder. */
    public record Change(Identifier id, int order) {
    }

    /** Lowest and highest custom order the server accepts. */
    public static final int MIN_ORDER = -1000;
    public static final int MAX_ORDER = 1000;

    private static final Comparator<Row> ORDER = Comparator
            .comparingInt((Row row) -> rank(row.source()))
            .thenComparing(row -> row.source() == QuestSource.PACK ? row.id().getNamespace() : "")
            .thenComparingInt(row -> row.source() == QuestSource.CUSTOM ? row.order() : 0)
            .thenComparing(row -> row.id().toString());

    private ChapterOrder() {
    }

    private static int rank(QuestSource source) {
        return switch (source) {
            case SPINE -> 0;
            case PACK -> 1;
            case CUSTOM -> 2;
        };
    }

    /** {@code items} sorted by the column's rule. */
    public static <T> List<T> sort(List<T> items, Function<T, Row> row) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(row, ORDER));
        return sorted;
    }

    /**
     * Move the custom chapter at {@code index} of {@code customs} (custom rows only, already sorted) one place
     * up ({@code direction < 0}) or down. Swaps sort keys with the neighbour; when both have the same key the
     * moved chapter is pushed one past it. Empty when the move is impossible (first/last, out of range).
     */
    public static List<Change> move(List<Row> customs, int index, int direction) {
        int other = index + (direction < 0 ? -1 : 1);
        if (index < 0 || index >= customs.size() || other < 0 || other >= customs.size() || direction == 0) {
            return List.of();
        }
        Row moving = customs.get(index);
        Row neighbour = customs.get(other);
        if (moving.order() != neighbour.order()) {
            return List.of(new Change(moving.id(), neighbour.order()), new Change(neighbour.id(), moving.order()));
        }
        int pushed = clamp(neighbour.order() + (direction < 0 ? -1 : 1));
        if (pushed == moving.order()) {
            return List.of();
        }
        return List.of(new Change(moving.id(), pushed));
    }

    private static int clamp(int order) {
        return Math.max(MIN_ORDER, Math.min(MAX_ORDER, order));
    }
}
