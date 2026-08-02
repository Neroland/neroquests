package za.co.neroland.neroquests.data;

import java.util.function.Supplier;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.telemetry.NeroQuestsTelemetry;

/**
 * Crash-proof loading for NeroQuests {@link SavedData}. Every NeroQuests saved-data accessor
 * routes through {@link #get}; nothing calls {@code getDataStorage().computeIfAbsent(...)}
 * directly.
 *
 * <p>Vanilla's {@code SavedDataStorage.computeIfAbsent} reads {@code data/<id>.dat} on first
 * access and lets any failure (corrupt, truncated or unreadable file) propagate unchecked. Since
 * quest progress is fetched from tick/command paths, one bad file would otherwise crash the
 * server repeatedly. Instead this helper:</p>
 *
 * <ol>
 *   <li>tries vanilla storage;</li>
 *   <li>on failure, substitutes a fresh empty instance, installs it into the storage cache
 *       (so later calls hit the cache instead of re-reading the bad file) and marks it dirty
 *       (so a clean file is written at the next level save).</li>
 * </ol>
 *
 * <p>The result is degraded-but-playable rather than a hard crash. This is the minimal port of
 * Nerospace's {@code SavedDataRecovery}; NeroQuests keeps no side-car backup files, so a
 * recovery starts the store empty.</p>
 *
 * <p><b>Privacy (POPIA/GDPR):</b> the failure is logged with the saved-data resource name and
 * the dimension key only — never a player name, UUID or any stored value — and reported through
 * the existing scrubbed, opt-out telemetry pipeline as a handled (non-fatal) event.</p>
 */
public final class SavedDataRecovery {

    private SavedDataRecovery() {
    }

    /**
     * Fetches {@code type} from {@code level}'s data storage, recovering to a fresh instance if
     * the stored file cannot be read.
     *
     * @param level    the level whose data storage holds the file (NeroQuests uses the overworld)
     * @param type     the saved-data type (id + factory + codec)
     * @param fallback supplies the fresh empty instance used when recovery is needed
     * @param name     stable non-identifying label for logs/telemetry, e.g.
     *                 {@code "neroquests:quest_progress"}
     * @return the loaded instance, or a fresh one if loading failed
     */
    public static <T extends SavedData> T get(ServerLevel level, SavedDataType<T> type,
                                              Supplier<T> fallback, String name) {
        try {
            T instance = level.getDataStorage().computeIfAbsent(type);
            if (instance != null) {
                return instance;
            }
        } catch (Exception e) {
            return recover(level, type, fallback, name, e);
        }
        // computeIfAbsent's generic return is unannotated; a null here means the same thing as a
        // read failure, so recover the same way.
        return recover(level, type, fallback, name, null);
    }

    private static <T extends SavedData> T recover(ServerLevel level, SavedDataType<T> type,
                                                   Supplier<T> fallback, String name, Exception failure) {
        T fresh = fallback.get();
        if (fresh == null) {
            throw new IllegalStateException("SavedData fallback supplier returned null for " + name);
        }
        try {
            level.getDataStorage().set(type, fresh);
            fresh.setDirty();
        } catch (Exception inner) {
            if (failure != null) {
                failure.addSuppressed(inner);
            }
        }
        NeroQuestsCommon.LOGGER.warn(
                "[NeroQuests] Could not read saved data '{}' in {} (missing, corrupt or unreadable); "
                        + "starting with fresh data. A clean file is written at the next save.",
                name, level.dimension(), failure);
        if (failure != null) {
            NeroQuestsTelemetry.captureHandledException(failure, "saved_data_recovery", name);
        }
        return fresh;
    }
}
