package za.co.neroland.neroquests.platform;

import java.util.List;

/**
 * The loader-specific facts the common module is allowed to depend on.
 *
 * <p>Each loader module ships exactly one implementation, registered via a
 * {@code META-INF/services/za.co.neroland.neroquests.platform.PlatformInfo} file so
 * {@link Services} can load it with {@link java.util.ServiceLoader}. This is the
 * lightweight, dependency-free alternative to Architectury's {@code @ExpectPlatform},
 * and it keeps {@code common/} free of {@code net.neoforged.*} / {@code net.fabricmc.*} /
 * {@code net.minecraftforge.*} imports.
 *
 * <p>Deliberately minimal: it answers only what
 * {@link za.co.neroland.neroquests.telemetry.NeroQuestsTelemetry} needs. Neroland Core's own
 * {@code IPlatformHelper} covers the wider surface (config dir, {@code isModLoaded},
 * capability invalidation) but reports <em>Core's</em> mod version, so NeroQuests needs its
 * own seam to tag crash reports with the NeroQuests release. Everything exposed here is a
 * public manifest string — never personal data (POPIA/GDPR).
 */
public interface PlatformInfo {

    /** Human-readable platform name ("Fabric" / "Forge" / "NeoForge"). */
    String getPlatformName();

    /** True when running in a development (dev/data/test) environment. */
    boolean isDevelopmentEnvironment();

    /** True on the physical client (renderers, screens, HUD available). */
    boolean isClient();

    /** This mod's version string (a public manifest value — safe as a telemetry release tag), or "unknown". */
    String getModVersion();

    /**
     * The ids + versions of every loaded mod ("modid version"), sorted, for crash mod-conflict
     * triage. These are public manifest strings only — never personal data (POPIA/GDPR).
     */
    List<String> getLoadedModIds();
}
