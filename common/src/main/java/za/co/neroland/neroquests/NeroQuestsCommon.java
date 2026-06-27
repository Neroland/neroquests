package za.co.neroland.neroquests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic entry point for NeroQuests. Each loader entry point
 * (Fabric / Forge / NeoForge) calls {@link #init()} once during mod
 * construction. This is a barebones skeleton — no content is registered yet;
 * add shared blocks, items and systems here and reach loader-specific
 * behaviour through a platform seam.
 */
public final class NeroQuestsCommon {

    public static final String MOD_ID = "neroquests";
    public static final Logger LOGGER = LoggerFactory.getLogger("NeroQuests");

    private NeroQuestsCommon() {
    }

    /** Called once per loader during mod construction. */
    public static void init() {
        LOGGER.info("[NeroQuests] common init");
    }
}
