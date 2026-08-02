package za.co.neroland.neroquests.quest.engine;

import java.util.Set;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.quest.QuestScope;

/**
 * Everything an objective needs to evaluate itself, resolved once per quest per engine pass.
 *
 * <p>{@code completedQuests} is already scope-resolved: for a {@code scope: player} quest it is the
 * context player's own completed set, and for a {@code scope: server} quest it is the world's
 * shared completed set. Objectives therefore never have to know which scope they are in.
 *
 * <p>For a server-scoped quest the {@code player} is simply whoever triggered this pass; server
 * scope progress belongs to the world, not to them.
 *
 * <p>Server thread only. Instances are short-lived and never stored.
 *
 * @param server          the running server
 * @param player          the player who triggered this evaluation
 * @param scope           the owning quest's scope
 * @param completedQuests the quest ids already complete for the owning scope holder
 */
public record ObjectiveContext(MinecraftServer server,
                               ServerPlayer player,
                               QuestScope scope,
                               Set<Identifier> completedQuests) {
}
