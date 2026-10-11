package za.co.neroland.neroquests.quest.engine;

import java.util.Map;
import java.util.Set;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;

/**
 * Everything an objective needs to evaluate itself, resolved once per quest per engine pass.
 *
 * <p>{@code completedQuests} is already scope-resolved: for a {@code scope: player} quest it is the
 * context player's own completed set, and for a {@code scope: server} quest it is the world's
 * shared completed set, so most objectives never have to know which scope they are in.
 * {@code quest_complete} instead resolves its target by the target's own scope, from
 * {@code playerCompleted} / {@code serverCompleted} and {@code definitions}.
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
 * @param quest           the id of the quest being evaluated (its namespace decides custom_event
 *                        presence, AUD-03)
 * @param playerCompleted the context player's own completed set
 * @param serverCompleted the world's completed set
 * @param definitions     the loaded quest definitions, so a {@code quest_complete} target resolves by
 *                        its own scope like a prerequisite does ({@link za.co.neroland.neroquests.quest.QuestStates})
 */
public record ObjectiveContext(MinecraftServer server,
                               ServerPlayer player,
                               QuestScope scope,
                               Set<Identifier> completedQuests,
                               Identifier quest,
                               Set<Identifier> playerCompleted,
                               Set<Identifier> serverCompleted,
                               Map<Identifier, Quest> definitions) {

    /**
     * The pre-0.4 shape: only the scope-resolved completed set is known, so it stands in for both
     * sections and no definitions are available (a target then counts if it is in that set).
     */
    public ObjectiveContext(MinecraftServer server, ServerPlayer player, QuestScope scope,
                            Set<Identifier> completedQuests, Identifier quest) {
        this(server, player, scope, completedQuests, quest, completedQuests, completedQuests, Map.of());
    }
}
