package za.co.neroland.neroquests.link;

import java.util.List;

import za.co.neroland.nerolandcore.link.LinkModuleInfo;
import za.co.neroland.nerolandcore.link.NeroLinkRegistry;
import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.platform.Services;

/**
 * NeroQuests' plug into Neroland Core's link API — the seam a companion client reaches quest
 * progress through, without NeroQuests knowing that any such client exists.
 *
 * <p>The whole module is plain server-side Java against Core's
 * {@link za.co.neroland.nerolandcore.link} package: no loader wiring, no networking of its own, no
 * HTTP. NeroQuests registers what it can show and what it can do; the separate bridge mod reads
 * Core's registry and serves it. With no bridge installed this costs one registry entry and a
 * completion listener.
 *
 * <p>Three surfaces, all registered from {@link NeroQuestsCommon#init()}:
 *
 * <ul>
 *   <li><b>Read</b> — {@link QuestLinkSnapshots}, serving the {@code quests} and {@code chapters}
 *       sections;</li>
 *   <li><b>Write</b> — {@link QuestLinkActions}, accepting the single {@code claim_reward}
 *       action;</li>
 *   <li><b>Live</b> — {@link QuestLinkEvents}, publishing {@code quest_completed} and
 *       {@code progress} events onto Core's shared event bus.</li>
 * </ul>
 *
 * <p><b>Privacy (POPIA/GDPR).</b> Everything this module hands out is the requesting player's own
 * quest data (quest ids, objective counters, timestamps) plus identifier-free shared world
 * progress — never another player's rows, never names, never coordinates. Quests hidden behind an
 * unopened {@code visible_gate} are filtered out server-side, exactly as they are in game. Erasure
 * needs no separate wiring: every read here goes to the live progress store, so a player erased
 * through Core's {@code PlayerDataErasure} hook immediately reads as having no progress. See
 * {@code PRIVACY.md} and {@code wiki/Link-Module.md}.
 *
 * <p><b>Schema version 1.</b> Bump {@link #SCHEMA_VERSION} whenever the shape of a snapshot section
 * changes, so a companion client can tell what it is parsing.
 */
public final class QuestLinkModule {

    /** The link module id — the same string as the mod id, as the ecosystem convention requires. */
    public static final String MODULE_ID = NeroQuestsCommon.MOD_ID;

    /** The snapshot schema revision. Bump on any change to a section's shape. */
    public static final int SCHEMA_VERSION = 1;

    /** Section: every quest visible to the requesting player, with their own progress. */
    public static final String SECTION_QUESTS = "quests";

    /** Section: the quest-book chapters, each listing the quest ids the player may see. */
    public static final String SECTION_CHAPTERS = "chapters";

    /** Action: the stable, schema-safe reward-claim id (see {@link QuestLinkActions}). */
    public static final String ACTION_CLAIM_REWARD = "claim_reward";

    /** Topic: one quest finished. */
    public static final String TOPIC_QUEST_COMPLETED = "quest_completed";

    /** Topic: one or more objective counters moved. */
    public static final String TOPIC_PROGRESS = "progress";

    private QuestLinkModule() {
    }

    /**
     * Register the read, write and live surfaces with Core. Called once from
     * {@link NeroQuestsCommon#init()}, after rewards are wired (so the completion listener this
     * adds runs behind reward execution, and a companion client is never told about a completion
     * before its payout has been attempted).
     *
     * <p>A failure here must never take the mod down with it: quests work perfectly well with no
     * link module, so any problem is logged and swallowed.
     */
    public static void init() {
        try {
            LinkModuleInfo info = new LinkModuleInfo(MODULE_ID, modVersion(), SCHEMA_VERSION,
                    List.of(SECTION_QUESTS, SECTION_CHAPTERS),
                    List.of(ACTION_CLAIM_REWARD));
            // One provider and one handler cover the whole module; Core keys both on the module id.
            NeroLinkRegistry.registerSnapshotProvider(new QuestLinkSnapshots(), info);
            NeroLinkRegistry.registerActionHandler(new QuestLinkActions(), info);
            QuestLinkEvents.init();
        } catch (RuntimeException e) {
            NeroQuestsCommon.LOGGER.warn(
                    "[NeroQuests] Could not register the NeroLink module; companion clients will not "
                            + "see NeroQuests data. Quests themselves are unaffected.", e);
        }
    }

    /** This mod's public version string for discovery, or {@code "unknown"} if the seam is unhappy. */
    private static String modVersion() {
        try {
            String version = Services.PLATFORM.getModVersion();
            return version == null || version.isBlank() ? "unknown" : version;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
