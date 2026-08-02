package za.co.neroland.neroquests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.data.QuestData;
import za.co.neroland.neroquests.link.QuestLinkModule;
import za.co.neroland.neroquests.network.QuestNetwork;
import za.co.neroland.neroquests.quest.QuestTypes;
import za.co.neroland.neroquests.quest.engine.QuestTriggers;
import za.co.neroland.neroquests.quest.reward.QuestRewards;
import za.co.neroland.neroquests.registry.ModItems;
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
        // Content registration. On Fabric this registers eagerly here; on NeoForge/Forge it only
        // builds the DeferredRegisters, which each of those entry points then attaches to its mod
        // event bus with RegistrationProvider.attach(...).
        ModItems.init();
        // The book joins Neroland Core's shared creative tab — no NeroQuests tab of its own. Core's
        // tab reads its contents lazily when the tab is displayed, so contributing after Core has
        // already built the tab is fine.
        ModItems.addToCreativeTab();
        // Objective/reward types must exist before any datapack quest is parsed, otherwise
        // every spec would decode as "unknown type" and drop its quest.
        QuestTypes.init();
        QuestData.init();
        // Declare the server -> client payloads before any loader registers them: every loader
        // entry point runs this method first, then wires its own networking.
        QuestNetwork.init();
        // Objective triggers that need no loader wiring: Neroland Core's progression-gate bus is
        // plain server-side Java, so subscribing here covers all three loaders at once. The
        // per-loader triggers (server tick, entity death) are registered by each loader entry
        // point; crafting is caught by the shared ItemStackMixin.
        QuestTriggers.init();
        // Reward execution subscribes to the completion channel the triggers above feed, so it is
        // registered after them (order within a run is by subscription, and rewards should see a
        // completion only once the engine has recorded it).
        QuestRewards.init();
        // The NeroLink module registers NeroQuests' read/write/live surfaces with Neroland Core's
        // link registry. Last, because its completion listener should run behind reward execution —
        // a companion client is never told about a completion before its payout was attempted. Pure
        // server-side Java against Core's API; with no bridge mod installed it costs one registry
        // entry and a listener.
        QuestLinkModule.init();
    }
}
