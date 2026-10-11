package za.co.neroland.neroquests.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

import za.co.neroland.neroquests.quest.QuestSource;

/** E2: spine first, packs by namespace, custom chapters below by order; up/down swaps sort keys. */
class ChapterOrderTest {

    private static ChapterOrder.Row row(String id, int order) {
        Identifier parsed = Identifier.parse(id);
        return new ChapterOrder.Row(parsed, QuestSource.of(parsed), order);
    }

    @Test
    void sortsBySourceThenNamespaceThenOrder() {
        List<ChapterOrder.Row> rows = List.of(
                row("custom:bbbb", 0),
                row("nerotech:industrial_record", 0),
                row("neroquests:space_race", 0),
                row("custom:aaaa", 5),
                row("nerospace:starfarers_log", 0),
                row("neroquests:groundwork", 0),
                row("custom:cccc", -2));
        List<ChapterOrder.Row> sorted = ChapterOrder.sort(rows, Function.identity());
        assertEquals(List.of("neroquests:groundwork", "neroquests:space_race", "nerospace:starfarers_log",
                        "nerotech:industrial_record", "custom:cccc", "custom:bbbb", "custom:aaaa"),
                sorted.stream().map(entry -> entry.id().toString()).toList());
    }

    @Test
    void moveSwapsWithTheNeighbour() {
        List<ChapterOrder.Row> customs = List.of(row("custom:a", 0), row("custom:b", 1), row("custom:c", 2));
        assertEquals(List.of(new ChapterOrder.Change(Identifier.parse("custom:b"), 0),
                new ChapterOrder.Change(Identifier.parse("custom:a"), 1)), ChapterOrder.move(customs, 1, -1));
        assertEquals(List.of(new ChapterOrder.Change(Identifier.parse("custom:b"), 2),
                new ChapterOrder.Change(Identifier.parse("custom:c"), 1)), ChapterOrder.move(customs, 1, 1));
        assertTrue(ChapterOrder.move(customs, 0, -1).isEmpty(), "first cannot move up");
        assertTrue(ChapterOrder.move(customs, 2, 1).isEmpty(), "last cannot move down");
        assertTrue(ChapterOrder.move(customs, 7, 1).isEmpty(), "out of range");
    }

    @Test
    void equalKeysPushPastTheNeighbour() {
        List<ChapterOrder.Row> customs = List.of(row("custom:a", 3), row("custom:b", 3));
        assertEquals(List.of(new ChapterOrder.Change(Identifier.parse("custom:b"), 2)),
                ChapterOrder.move(customs, 1, -1));
        assertEquals(List.of(new ChapterOrder.Change(Identifier.parse("custom:a"), 4)),
                ChapterOrder.move(customs, 0, 1));
    }
}
