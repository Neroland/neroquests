package za.co.neroland.neroquests.quest.objective;

import java.util.Set;

import za.co.neroland.nerolandcore.platform.Services;

/**
 * Namespace questions behind cross-mod degradation (AUD-02/AUD-03): is a resource id's owner here,
 * and could a missing id be a typo rather than an absent mod?
 *
 * <ul>
 *   <li>A namespace is <b>owned by a loaded mod</b> when that mod is installed, or when it is one of
 *       {@code minecraft}, {@code neroquests} and {@code nerolandcore}, which are always present. An
 *       id in such a namespace that does not resolve is a <b>typo</b>, not missing content: its owner
 *       is here and does not define it.</li>
 *   <li>A namespace is <b>a mod id</b> when a mod by that id is loaded or it names a mod of the
 *       Neroland ecosystem (whose absence is the normal "smaller pack" case). Anything else — a
 *       server datapack's own namespace, a channel family nobody ships as a mod — is not a mod id.
 *       The loader can only answer "is it loaded", so the ecosystem list is what lets an absent
 *       sibling be told apart from a namespace that was never a mod.</li>
 * </ul>
 */
public final class ContentIds {

    /** Namespaces that are present in every installation NeroQuests runs in. */
    private static final Set<String> ALWAYS_PRESENT = Set.of("minecraft", "neroquests", "nerolandcore");

    /** Neroland ecosystem mod ids: an absent one is a missing mod, not a non-mod namespace. */
    private static final Set<String> ECOSYSTEM_MOD_IDS = Set.of(
            "nerolandcore", "neroquests", "nerospace", "nerotech", "neropower", "nerologistics",
            "nerocolonies", "neroeconomy", "nerolink", "neroagriculture", "nerocreatures", "nerodecor",
            "neroevents", "nerofactions", "neronotes", "neroruins", "nerosecurity", "nerocompanion");

    private ContentIds() {
    }

    /** Whether ids in {@code namespace} are defined by something installed right now. */
    public static boolean ownedByLoadedMod(String namespace) {
        return ALWAYS_PRESENT.contains(namespace) || Services.PLATFORM.isModLoaded(namespace);
    }

    /**
     * Whether {@code namespace}'s full content set is known here, so an empty tag in it is a typo
     * rather than "filled by some optional mod". Only the always-present namespaces qualify: any other
     * mod's tag may legitimately be filled by a third mod that is not installed.
     */
    public static boolean closedNamespace(String namespace) {
        return ALWAYS_PRESENT.contains(namespace);
    }

    /** Whether {@code namespace} is a mod id (loaded, or a known ecosystem mod). */
    public static boolean isModId(String namespace) {
        return ECOSYSTEM_MOD_IDS.contains(namespace) || Services.PLATFORM.isModLoaded(namespace);
    }

    /** Whether a mod with this id is loaded. */
    public static boolean modLoaded(String namespace) {
        return Services.PLATFORM.isModLoaded(namespace);
    }
}
