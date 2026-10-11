package za.co.neroland.neroquests.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** AUD-21: definitions paging — size-bounded split, oversized entries skipped, atomic reassembly. */
class DefinitionPagerTest {

    private static List<Integer> sizes(int... values) {
        List<Integer> out = new ArrayList<>();
        for (int value : values) {
            out.add(Integer.valueOf(value));
        }
        return out;
    }

    @Test
    void splitKeepsOrderAndEveryPageWithinBudget() {
        List<Integer> items = sizes(40, 30, 30, 50, 10, 60, 5);
        List<List<Integer>> pages = DefinitionPager.split(items, Integer::intValue, 100, i -> {
            throw new AssertionError("nothing is oversized here");
        });
        List<Integer> flattened = new ArrayList<>();
        for (List<Integer> page : pages) {
            int sum = page.stream().mapToInt(Integer::intValue).sum();
            assertTrue(sum <= 100, "page over budget: " + page);
            flattened.addAll(page);
        }
        assertEquals(items, flattened);
        assertEquals(List.of(sizes(40, 30, 30), sizes(50, 10), sizes(60, 5)), pages);
    }

    @Test
    void oversizedEntryIsSkippedNotThrown() {
        List<Integer> skipped = new ArrayList<>();
        List<List<Integer>> pages = DefinitionPager.split(sizes(10, 500, 20), Integer::intValue, 100, skipped::add);
        assertEquals(sizes(500), skipped);
        assertEquals(List.of(sizes(10, 20)), pages);
    }

    @Test
    void emptyInputIsOneEmptyPage() {
        List<List<Integer>> pages = DefinitionPager.split(List.<Integer>of(), Integer::intValue, 100, i -> { });
        assertEquals(List.of(List.of()), pages);
    }

    @Test
    void assemblerReleasesOnlyWhenEveryPageArrivedInAnyOrder() {
        DefinitionPager.Assembler<String> assembler = new DefinitionPager.Assembler<>();
        assertEquals(Optional.empty(), assembler.accept(7, 2, 3, List.of("e")));
        assertEquals(Optional.empty(), assembler.accept(7, 0, 3, List.of("a", "b")));
        assertEquals(Optional.empty(), assembler.accept(7, 0, 3, List.of("a", "b"))); // duplicate ignored
        assertEquals(Optional.of(List.of("a", "b", "c", "d", "e")), assembler.accept(7, 1, 3, List.of("c", "d")));
    }

    @Test
    void newerSyncDiscardsUnfinishedOlderOneAndMalformedPagesAreIgnored() {
        DefinitionPager.Assembler<String> assembler = new DefinitionPager.Assembler<>();
        assertEquals(Optional.empty(), assembler.accept(1, 0, 2, List.of("old")));
        assertEquals(Optional.empty(), assembler.accept(2, 0, 2, List.of("new0")));
        assertEquals(Optional.empty(), assembler.accept(2, 5, 2, List.of("bad index")));
        assertEquals(Optional.empty(), assembler.accept(2, 0, 0, List.of("bad total")));
        // Page 1 of sync 1 arrives late: it restarts sync 1 rather than completing a mix.
        assertEquals(Optional.empty(), assembler.accept(1, 1, 2, List.of("late")));
        assertEquals(Optional.empty(), assembler.accept(2, 1, 2, List.of("new1")));
        assertEquals(Optional.of(List.of("new0", "new1")), assembler.accept(2, 0, 2, List.of("new0")));
    }

    @Test
    void utfSizeMatchesVarIntPrefixPlusUtf8Bytes() {
        assertEquals(1 + 3, DefinitionPager.utfSize("abc"));
        assertEquals(1 + 2, DefinitionPager.utfSize("é"));
        assertEquals(2 + 200, DefinitionPager.utfSize("x".repeat(200)));
        assertEquals(1, DefinitionPager.varIntSize(0));
        assertEquals(3, DefinitionPager.varIntSize(2_097_151));
    }
}
