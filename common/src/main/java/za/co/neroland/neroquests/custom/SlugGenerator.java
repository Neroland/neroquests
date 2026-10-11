package za.co.neroland.neroquests.custom;

import java.security.SecureRandom;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * Generates the random path segments of custom ids (D1): {@code custom:<chapter>} and
 * {@code custom:<chapter>/<quest>}. Always server-side, always random lower-case base32 — never derived
 * from a title or an author's name, so an id can never leak who made a quest or what it said.
 */
public final class SlugGenerator {

    /** RFC 4648 base32, lower-case: valid in an {@code Identifier} path. */
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    /** Characters per slug: 32^8 ids, so collisions are vanishingly rare and simply retried. */
    public static final int LENGTH = 8;

    private static final RandomGenerator RUNTIME = new SecureRandom();

    private SlugGenerator() {
    }

    /** One random slug from {@code random}. */
    public static String next(RandomGenerator random) {
        char[] out = new char[LENGTH];
        for (int index = 0; index < LENGTH; index++) {
            out[index] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    /** A random slug that {@code taken} does not reject (retried; a longer slug after many collisions). */
    public static String unique(RandomGenerator random, Predicate<String> taken) {
        for (int attempt = 0; attempt < 64; attempt++) {
            String slug = next(random);
            if (!taken.test(slug)) {
                return slug;
            }
        }
        String slug;
        do {
            slug = next(random) + next(random);
        } while (taken.test(slug));
        return slug;
    }

    /** {@link #unique(RandomGenerator, Predicate)} with the server's secure random source. */
    public static String unique(Predicate<String> taken) {
        return unique(RUNTIME, taken);
    }

    /** Whether {@code slug} has the shape this class generates. */
    public static boolean isSlug(String slug) {
        if (slug == null || slug.length() < LENGTH || slug.length() > 2 * LENGTH) {
            return false;
        }
        for (int index = 0; index < slug.length(); index++) {
            char c = slug.charAt(index);
            if (!((c >= 'a' && c <= 'z') || (c >= '2' && c <= '7'))) {
                return false;
            }
        }
        return true;
    }
}
