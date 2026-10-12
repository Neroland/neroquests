package za.co.neroland.neroquests.pack;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * H11: every built sibling's quest pack validates with {@link QuestPackValidator} — real codecs, the real
 * load pipeline as if the sibling were installed, and its en_us.json keys — against NeroQuests' own spine.
 *
 * <p>The siblings are found next to this repository ({@code ../<repo>}, relative to the repository root).
 * A sibling that is not checked out next to it, or that ships no pack, is skipped (JUnit assumption), so a
 * lone clone on CI stays green. Test-only: no runtime dependency in either direction.
 */
class SiblingPacksTest {

    /**
     * Siblings that ship a pack (the four skeleton mods ship data-only packs); the repository folder name is also
     * the mod id / pack namespace.
     */
    private static final List<String> SIBLINGS = List.of("nerospace", "nerotech", "neropower", "nerologistics",
            "neroeconomy", "nerocolonies", "nerocreatures", "neroagriculture", "nerodecor", "nerofactions",
            "neronotes", "neroevents", "neroruins", "nerocompanion", "nerosecurity");

    /** NeroQuests' common resources root (same lookup as the other content tests). */
    private static Path resources() {
        String configured = System.getProperty("neroquests.commonResources");
        return configured != null ? Path.of(configured) : Path.of("../../../common/src/main/resources");
    }

    /** {@code <repo root>/../<repo>/common/src/main/resources}. */
    static Path siblingResources(String repo) {
        Path repoRoot = resources().toAbsolutePath().normalize().getParent().getParent().getParent().getParent();
        return repoRoot.resolveSibling(repo).resolve("common/src/main/resources");
    }

    @TestFactory
    Stream<DynamicTest> everySiblingPackValidates() {
        return SIBLINGS.stream().map(repo -> DynamicTest.dynamicTest(repo, () -> {
            Path root = siblingResources(repo);
            assumeTrue(Files.isDirectory(root.resolve("data/" + repo + "/neroquests")),
                    () -> "../" + repo + " not checked out next to this repository (or ships no pack); skipped");
            List<String> problems = QuestPackValidator.validate(root, repo, resources());
            assertTrue(problems.isEmpty(), "../" + repo + " pack problems:\n  " + String.join("\n  ", problems));
        }));
    }
}
