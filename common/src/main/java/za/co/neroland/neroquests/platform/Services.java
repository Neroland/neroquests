package za.co.neroland.neroquests.platform;

import java.util.ServiceLoader;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * Loads loader-specific platform-seam implementations via {@link ServiceLoader}.
 *
 * <p>Common code calls {@code Services.PLATFORM.xxx()}; the correct Fabric / Forge / NeoForge
 * implementation is resolved at runtime from the {@code META-INF/services} entry each loader
 * module ships. Resolve seams during mod construction (as {@link #PLATFORM} does by being
 * touched from init) rather than lazily mid-tick — a lazy {@link ServiceLoader} read can throw
 * {@code ServiceConfigurationError} out of gameplay code if the jar has become unreadable.
 */
public final class Services {

    public static final PlatformInfo PLATFORM = load(PlatformInfo.class);

    /** The loader's packet-send implementation (see {@link NetworkPlatform}). */
    public static final NetworkPlatform NETWORK = load(NetworkPlatform.class);

    private Services() {
    }

    public static <T> T load(Class<T> clazz) {
        final T loaded = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException(
                        "No implementation found for service " + clazz.getName()));
        NeroQuestsCommon.LOGGER.debug("Loaded service {} -> {}",
                clazz.getSimpleName(), loaded.getClass().getName());
        return loaded;
    }
}
