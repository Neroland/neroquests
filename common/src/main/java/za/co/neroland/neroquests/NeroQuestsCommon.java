package za.co.neroland.neroquests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.data.QuestData;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.telemetry.NeroQuestsTelemetry;

/**
 * Loader-agnostic entry point for NeroQuests. Each loader entry point
 * (Fabric / Forge / NeoForge) calls {@link #init()} once during mod
 * construction. Init ordering mirrors Neroland Core's
 * {@code NerolandCoreCommon}: config first, then data/erasure registration,
 * then (in later stages) registries, network and content. Loader-specific
 * behaviour is reached through Core's platform seams.
 */
public final class NeroQuestsCommon {

    public static final String MOD_ID = "neroquests";
    public static final Logger LOGGER = LoggerFactory.getLogger("NeroQuests");

    private NeroQuestsCommon() {
    }

    /** Called once per loader during mod construction. */
    public static void init() {
        LOGGER.info("[NeroQuests] common init");
        NeroQuestsConfig.init();
        // Anonymous, NeroQuests-only crash reporting. Must follow the config registration (it reads
        // telemetryEnabled) and precede the rest of init so early failures are still reported.
        NeroQuestsTelemetry.init();
        // Objective/reward types must exist before any datapack quest is parsed, otherwise
        // every spec would decode as "unknown type" and drop its quest.
        QuestTypes.init();
        QuestData.init();
    }
}
