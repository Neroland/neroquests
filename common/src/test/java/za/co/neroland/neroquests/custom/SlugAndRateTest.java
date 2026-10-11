package za.co.neroland.neroquests.custom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

/** D4: slugs never carry input text; the rate limiter refills. */
class SlugAndRateTest {

    @Test
    void slugsAreRandomBase32AndValidIdPaths() {
        Random random = new Random(42);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String slug = SlugGenerator.next(random);
            assertEquals(SlugGenerator.LENGTH, slug.length());
            assertTrue(SlugGenerator.isSlug(slug));
            Identifier.fromNamespaceAndPath("custom", slug + "/" + slug); // never throws
            seen.add(slug);
        }
        assertTrue(seen.size() > 490);
    }

    @Test
    void slugGenerationTakesNoInputText() {
        // The generator has no text parameter at all; a title like this can never shape an id.
        String title = "Dario's secret base";
        String slug = SlugGenerator.unique(new Random(1), taken -> false);
        assertFalse(slug.contains("dario"));
        assertFalse(title.toLowerCase().contains(slug));
    }

    @Test
    void uniqueRetriesCollisions() {
        Random fixed = new Random(7);
        String first = SlugGenerator.next(new Random(7));
        String next = SlugGenerator.unique(fixed, candidate -> candidate.equals(first));
        assertFalse(next.equals(first));
    }

    @Test
    void rateLimiterAllowsBurstThenRefills() {
        RateLimiter limiter = new RateLimiter(3, 3_000L);
        Object key = "p";
        assertTrue(limiter.tryAcquire(key, 0L));
        assertTrue(limiter.tryAcquire(key, 0L));
        assertTrue(limiter.tryAcquire(key, 0L));
        assertFalse(limiter.tryAcquire(key, 0L));
        assertTrue(limiter.tryAcquire(key, 1_000L)); // one token back per second
        assertFalse(limiter.tryAcquire(key, 1_000L));
        assertTrue(limiter.tryAcquire("other", 1_000L)); // per key
    }
}
