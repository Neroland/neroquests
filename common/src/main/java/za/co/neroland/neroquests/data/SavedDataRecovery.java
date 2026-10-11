package za.co.neroland.neroquests.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * Crash-proof loading for NeroQuests {@link SavedData} (AUD-16). Every NeroQuests saved-data accessor
 * routes through {@link #get}; nothing calls {@code getDataStorage().computeIfAbsent(...)} directly.
 *
 * <p>The work is Neroland Core's {@code za.co.neroland.nerolandcore.data.SavedDataRecovery} (Core
 * 1.15.0): vanilla load, verification of the primary file against the store's codec on first access
 * (26.x decodes silently to an empty or partial store), restore from Core's last-known-good backup
 * ({@code data/neroquests_quest_progress_backup.dat}), and a fresh store only when no backup is usable.
 * Core keeps that backup current and re-encodes it registry-aware.
 *
 * <p>Core does <em>not</em> keep the corrupt primary file itself: after a recovery it is rewritten at
 * the next save. So before the first access per world, this class decodes the primary file once
 * with the store's codec and, if it is unreadable or fails to decode, copies it aside as
 * {@code <name>.corrupt-<epochMillis>} next to it — an operator can still inspect or repair what was
 * there, and nothing measurable silently re-pays because of a lost file.
 *
 * <p><b>Privacy (POPIA/GDPR):</b> logs carry the store label, dimension and file names only. The
 * backup is a second copy of the same player rows, so every erasure and retention purge calls
 * {@link #backupNow} straight after (see {@link QuestProgressState#backupNow}). A {@code .corrupt-*}
 * copy is an undecodable file kept for the operator; PRIVACY.md asks them to delete it after review.
 */
public final class SavedDataRecovery {

    /** Stores already checked, per data-storage instance (weak: a closed world is forgotten). */
    private static final Map<Object, Set<String>> CHECKED = Collections.synchronizedMap(new WeakHashMap<>());

    private SavedDataRecovery() {
    }

    /**
     * Fetches {@code type} from {@code level}'s data storage through Core's recovering accessor, having
     * first copied an undecodable primary file aside (once per world and store).
     *
     * @param level    the level whose data storage holds the file (NeroQuests uses the overworld)
     * @param type     the saved-data type (id + factory + codec)
     * @param fallback supplies the fresh empty instance used when recovery is needed
     * @param name     stable non-identifying label for logs/telemetry and Core's backup file name, e.g.
     *                 {@code "neroquests:quest_progress"}
     * @return the loaded, restored or fresh instance
     */
    public static <T extends SavedData> T get(ServerLevel level, SavedDataType<T> type,
                                              Supplier<T> fallback, String name) {
        if (firstAccess(level, name)) {
            preserveCorruptPrimary(level, type, name);
        }
        return za.co.neroland.nerolandcore.data.SavedDataRecovery.get(level, type, fallback, name);
    }

    /**
     * Refresh Core's backup of a store now (registry-aware), so an erasure or purge reaches the second
     * copy in the same request.
     */
    public static <T extends SavedData> void backupNow(ServerLevel level, SavedDataType<T> type, T instance,
                                                       String name, HolderLookup.Provider registries) {
        za.co.neroland.nerolandcore.data.SavedDataRecovery.backupNow(level, type, instance, name, registries);
    }

    private static boolean firstAccess(ServerLevel level, String name) {
        Object storage = level.getDataStorage();
        synchronized (CHECKED) {
            return CHECKED.computeIfAbsent(storage, ignored -> new HashSet<>()).add(name);
        }
    }

    private static <T extends SavedData> void preserveCorruptPrimary(ServerLevel level, SavedDataType<T> type,
                                                                     String name) {
        try {
            Path primary = primaryFile(level, type.id());
            if (primary == null) {
                return; // nothing on disk yet
            }
            boolean bad;
            try {
                CompoundTag root = NbtIo.readCompressed(primary, NbtAccounter.unlimitedHeap());
                Tag data = root.get("data");
                bad = data != null && type.codec()
                        .parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), data)
                        .error().isPresent();
            } catch (Exception unreadable) {
                bad = true;
            }
            if (!bad) {
                return;
            }
            String file = primary.getFileName().toString();
            String stem = file.endsWith(".dat") ? file.substring(0, file.length() - 4) : file;
            Path aside = primary.resolveSibling(stem + ".corrupt-" + System.currentTimeMillis());
            Files.copy(primary, aside);
            NeroQuestsCommon.LOGGER.warn("[NeroQuests] Saved data '{}' in {} could not be decoded; the file was "
                    + "copied to {} before recovery.", name, level.dimension(), aside.getFileName());
        } catch (Exception failure) {
            // Best-effort: preserving the bad file must never stop the store from loading.
            NeroQuestsCommon.LOGGER.debug("[NeroQuests] Could not check saved data '{}' before loading.", name,
                    failure);
        }
    }

    /**
     * The vanilla primary file for {@code id}, or {@code null}: {@code <dimension folder>/data/<ns>/<path>.dat},
     * also probed under {@code dimensions/<dim ns>/<dim path>/} (the same layouts Core checks).
     */
    private static Path primaryFile(ServerLevel level, Identifier id) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        Path storage = DimensionType.getStorageFolder(level.dimension(), worldRoot);
        Identifier dim = level.dimension().identifier();
        Path[] candidates = {
                storage.resolve("data").resolve(id.getNamespace()).resolve(id.getPath() + ".dat"),
                worldRoot.resolve("dimensions").resolve(dim.getNamespace()).resolve(dim.getPath())
                        .resolve("data").resolve(id.getNamespace()).resolve(id.getPath() + ".dat"),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
