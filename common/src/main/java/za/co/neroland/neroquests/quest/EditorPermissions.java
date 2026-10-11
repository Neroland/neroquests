package za.co.neroland.neroquests.quest;

import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.custom.CustomQuestState;

/**
 * Who may author custom quests — and tick {@code manual} objectives — on this server (§3.3, D2). The single
 * place the decision is made; always re-derived on the server from the player's real state (server-side
 * game mode, permission level, the persisted allow-list and lock). Nothing a client sends is ever read.
 *
 * <p>The rule ({@link #decide}):
 * <ul>
 *   <li>never a spectator, never a dead player;</li>
 *   <li>otherwise a player qualifies when they are in Creative mode (while {@code editorRequiresCreative}
 *       is on, the default), <em>or</em> have permission level {@code editorMinOpLevel} (default 2) or above,
 *       <em>or</em> are on the editor allow-list ({@code /neroquests editor allow});</li>
 *   <li>while the editor is locked ({@code /neroquests editor lock}) only permission level 4 qualifies.</li>
 * </ul>
 */
public final class EditorPermissions {

    private EditorPermissions() {
    }

    /**
     * Everything the decision depends on, as plain values (pure, so the matrix is unit-tested).
     *
     * @param spectator      the player is in spectator mode
     * @param alive          the player is alive
     * @param creative       the player is in Creative mode (server-side)
     * @param opLevel        the player's permission level, 0..4
     * @param onAllowList    the player is on the editor allow-list
     * @param locked         the editor is locked
     * @param creativeGrants {@code editorRequiresCreative}: Creative mode alone qualifies
     * @param minOpLevel     {@code editorMinOpLevel}
     */
    public record Access(boolean spectator, boolean alive, boolean creative, int opLevel, boolean onAllowList,
                         boolean locked, boolean creativeGrants, int minOpLevel) {
    }

    /** The permission level that bypasses the editor lock. */
    public static final int LOCK_BYPASS_LEVEL = 4;

    /** The decision itself (pure). */
    public static boolean decide(Access access) {
        if (access.spectator() || !access.alive()) {
            return false;
        }
        boolean qualifies = (access.creativeGrants() && access.creative())
                || access.opLevel() >= access.minOpLevel()
                || access.onAllowList();
        if (!qualifies) {
            return false;
        }
        return !access.locked() || access.opLevel() >= LOCK_BYPASS_LEVEL;
    }

    /** Whether {@code player} may author quests right now. Server thread only. */
    public static boolean canAuthor(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        return decide(accessOf(player, CustomQuestState.get(server)));
    }

    /** The inputs for {@code player} against {@code state} (the allow-list and lock). */
    public static Access accessOf(ServerPlayer player, CustomQuestState state) {
        return new Access(player.isSpectator(), player.isAlive(), player.isCreative(), opLevel(player),
                state.isAllowed(player.getUUID()), state.isLocked(), creativeGrants(), minOpLevel());
    }

    /** The player's permission level, 0..4. */
    public static int opLevel(ServerPlayer player) {
        if (Commands.LEVEL_OWNERS.check(player.permissions())) {
            return 4;
        }
        if (Commands.LEVEL_ADMINS.check(player.permissions())) {
            return 3;
        }
        if (Commands.LEVEL_GAMEMASTERS.check(player.permissions())) {
            return 2;
        }
        return Commands.LEVEL_MODERATORS.check(player.permissions()) ? 1 : 0;
    }

    private static boolean creativeGrants() {
        try {
            return NeroQuestsConfig.EDITOR_REQUIRES_CREATIVE.get().booleanValue();
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    private static int minOpLevel() {
        try {
            return NeroQuestsConfig.EDITOR_MIN_OP_LEVEL.get().intValue();
        } catch (RuntimeException | LinkageError e) {
            return 2;
        }
    }
}
