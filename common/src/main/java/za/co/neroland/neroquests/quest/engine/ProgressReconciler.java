package za.co.neroland.neroquests.quest.engine;

import java.util.Map;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.data.QuestProgressState;
import za.co.neroland.neroquests.network.QuestSync;
import za.co.neroland.neroquests.quest.QuestDefinitions;

/**
 * Runs {@link QuestProgressState#reconcile} once per definitions load (AUD-17), before the engine
 * evaluates anything against the new definitions: from the server tick and, defensively, at the top
 * of every evaluation (a no-op unless the definitions generation moved).
 *
 * <p>An empty definition set is never reconciled against: that is far more likely a broken pack
 * than a deliberate removal of every quest, and it would turn every row into an orphan.
 *
 * <p>Server thread only. Logs counts only — never which players.
 */
public final class ProgressReconciler {

    private static MinecraftServer reconciledFor;
    private static int reconciledGeneration;

    private ProgressReconciler() {
    }

    /** The server stopped (AUD-31): drop the reference to it. */
    public static void serverStopped() {
        reconciledFor = null;
        reconciledGeneration = 0;
    }

    /** Reconcile stored progress with the current definitions if that has not happened yet. */
    public static void ensure(MinecraftServer server) {
        if (server == reconciledFor && QuestDefinitions.generation() == reconciledGeneration) {
            return;
        }
        Map<Identifier, Integer> fingerprints = QuestDefinitions.fingerprintsForServer(server);
        if (QuestDefinitions.contentChecksPending() || fingerprints.isEmpty()) {
            return; // the typo pass may still drop quests; reconcile against the final set
        }
        reconciledFor = server;
        reconciledGeneration = QuestDefinitions.generation();
        QuestProgressState.ReconcileResult result = QuestProgressState.get(server)
                .reconcile(fingerprints, NeroQuestsConfig.RETENTION_DAYS.get().intValue(), System.currentTimeMillis());
        if (!result.changed()) {
            return;
        }
        NeroQuestsCommon.LOGGER.info("[NeroQuests] Progress reconciled with the quest definitions: {} row(s) reset "
                        + "(objectives changed), {} stamped, {} newly orphaned, {} orphan row(s) pruned.",
                Integer.valueOf(result.reset()), Integer.valueOf(result.stamped()),
                Integer.valueOf(result.orphaned()), Integer.valueOf(result.pruned()));
        if (result.reset() > 0 || result.pruned() > 0) {
            QuestSync.markAllProgressDirty(); // sent at the end of the tick (AUD-20)
        }
    }
}
