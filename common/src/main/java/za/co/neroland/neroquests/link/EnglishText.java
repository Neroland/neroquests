package za.co.neroland.neroquests.link;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * English text for translation keys, resolved server-side for link schema 2's {@code title_text} /
 * {@code description_text} fields (G1).
 *
 * <p>Read from each mod's bundled {@code assets/<namespace>/lang/en_us.json} on the class path (every
 * jar ships its assets, also on a dedicated server), once per namespace, cached until the server stops.
 * Deliberately not {@code Language.getInstance()}: on an integrated server that is the host's chosen
 * language, and the app wants one stable language. A key whose file cannot be found or read resolves to
 * {@code null} and the field is simply omitted — the client then shows the key, as in schema 1.
 *
 * <p>Only translation keys of spine and pack definitions are looked up; custom (player-written) text never
 * passes through here. Server thread only.
 */
final class EnglishText {

    private static final Map<String, JsonObject> BY_NAMESPACE = new HashMap<>();

    private EnglishText() {
    }

    /**
     * The English text for {@code key}, or {@code null}: looked up in the owning definition's namespace
     * first, then in the namespace the key itself names ({@code <kind>.<namespace>.…}).
     */
    static synchronized String resolve(String ownerNamespace, String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String found = ownerNamespace == null ? null
                : lookup(BY_NAMESPACE.computeIfAbsent(ownerNamespace, EnglishText::load), key);
        if (found != null) {
            return found;
        }
        String named = namespaceOf(key);
        return named == null || named.equals(ownerNamespace) ? null
                : lookup(BY_NAMESPACE.computeIfAbsent(named, EnglishText::load), key);
    }

    /**
     * The namespace a key belongs to by the ecosystem convention {@code <kind>.<namespace>.<path>…}
     * ({@code quest.nerospace.first_light.title}, {@code chapter.neroquests.groundwork.title}); {@code null}
     * when the key has fewer than three segments.
     */
    static String namespaceOf(String key) {
        String[] parts = key.split("\\.", 3);
        return parts.length < 3 || parts[1].isEmpty() ? null : parts[1];
    }

    /** The string value of {@code key} in {@code lang}, or {@code null}. */
    static String lookup(JsonObject lang, String key) {
        if (lang == null || !lang.has(key)) {
            return null;
        }
        JsonElement value = lang.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /** Forget the cache (server stopped, or a reload may have changed the installed set). */
    static synchronized void clear() {
        BY_NAMESPACE.clear();
    }

    /** Merge every {@code assets/<namespace>/lang/en_us.json} on the class path; empty on any failure. */
    private static JsonObject load(String namespace) {
        JsonObject merged = new JsonObject();
        if (!namespace.matches("[a-z0-9_.-]+")) {
            return merged;
        }
        try {
            ClassLoader loader = EnglishText.class.getClassLoader();
            Enumeration<URL> files = loader.getResources("assets/" + namespace + "/lang/en_us.json");
            while (files.hasMoreElements()) {
                URL url = files.nextElement();
                try (InputStream in = url.openStream();
                     Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        parsed.getAsJsonObject().entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            NeroQuestsCommon.LOGGER.debug("[NeroQuests] No English language file for namespace '{}'.", namespace);
        }
        return merged;
    }
}
