package za.co.neroland.neroquests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * J1 (§13 text, privacy): a source scan of the shipped code. Author-written text (custom titles,
 * descriptions, manual labels) never reaches a log line, a Sentry event, a NeroLink alert or a translation
 * key, and no code resolves a stored UUID to a player name. The runtime half (literal rendering, sanitiser)
 * is covered by {@code TextSanitizerTest} and {@code QuestText}; this keeps a later edit from re-opening it.
 */
class AuthorTextHygieneTest {

    private static final Pattern AUTHOR_TEXT =
            Pattern.compile("\\.(title|description|label|titleText|descriptionText)\\(\\)");
    private static final Pattern LOG_CALL =
            Pattern.compile("LOGGER\\.(trace|debug|info|warn|error)\\(|RewardLog\\.\\w+\\(|warnOnce\\(|debugOnce\\(");
    private static final Pattern KEY_FROM_TEXT =
            Pattern.compile("translatable\\(\\s*[A-Za-z_.()]*(title|description|label)\\(\\)");
    private static final Pattern NAME_LOOKUP = Pattern.compile(
            "GameProfile|getGameProfile|getScoreboardName|getPlainTextName|ProfileCache|nameToIdCache|getProfileCache");

    private static Path root;
    private static final List<Path> SOURCES = new ArrayList<>();

    @BeforeAll
    static void collect() throws IOException {
        String configured = System.getProperty("neroquests.commonResources");
        Path resources = configured != null ? Path.of(configured) : Path.of("../../../common/src/main/resources");
        root = resources.toAbsolutePath().normalize().getParent().resolve("java/za/co/neroland/neroquests");
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(SOURCES::add);
        }
        assertTrue(SOURCES.size() > 50, "sources found under " + root);
    }

    private static String statementAt(List<String> lines, int start) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < lines.size() && i < start + 6; i++) {
            out.append(lines.get(i)).append('\n');
            if (lines.get(i).trim().endsWith(";")) {
                break;
            }
        }
        return out.toString();
    }

    @Test
    void authorTextIsNeverLogged() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path file : SOURCES) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (LOG_CALL.matcher(lines.get(i)).find() && AUTHOR_TEXT.matcher(statementAt(lines, i)).find()) {
                    hits.add(root.relativize(file) + ":" + (i + 1));
                }
            }
        }
        assertTrue(hits.isEmpty(), "log call with author text: " + hits);
    }

    @Test
    void telemetryNeverTouchesQuestText() throws IOException {
        for (Path file : SOURCES) {
            if (root.relativize(file).toString().replace('\\', '/').startsWith("telemetry/")) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                assertTrue(!AUTHOR_TEXT.matcher(source).find(), file.toString());
            }
        }
    }

    @Test
    void linkAlertsCarryOnlyFixedText() throws IOException {
        Path alerts = root.resolve("link/QuestLinkAlerts.java");
        String source = Files.readString(alerts, StandardCharsets.UTF_8);
        Matcher calls = Pattern.compile("\\braise\\(player, ([A-Z_]+), ([A-Za-z_]+)\\)").matcher(source);
        int count = 0;
        while (calls.find()) {
            count++;
            assertTrue(calls.group(2).endsWith("_TEXT"), "alert text must be a fixed constant: " + calls.group());
        }
        assertTrue(count >= 2, "both alerts found");
        assertTrue(!AUTHOR_TEXT.matcher(source).find(), "no quest text in alerts");
    }

    @Test
    void authorTextIsNeverATranslationKey() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path file : SOURCES) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = KEY_FROM_TEXT.matcher(source);
            while (matcher.find()) {
                // Daily contracts are datapack-only (no editor path): their title is a pack translation key.
                if (!matcher.group().contains("contract.title()")) {
                    hits.add(root.relativize(file) + ": " + matcher.group());
                }
            }
        }
        assertTrue(hits.isEmpty(), "translatable(<quest text>) outside QuestText: " + hits);
    }

    @Test
    void storedUuidsAreNeverResolvedToNames() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path file : SOURCES) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (relative.startsWith("custom/") || relative.startsWith("link/") || relative.startsWith("client/")
                    || relative.startsWith("network/") || relative.startsWith("data/")) {
                if (NAME_LOOKUP.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    hits.add(relative);
                }
            }
        }
        assertTrue(hits.isEmpty(), "name lookup in " + hits);
    }
}
