package za.co.neroland.neroquests.quest.engine;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroquests.quest.Quest;
import za.co.neroland.neroquests.quest.QuestScope;

/**
 * One quest just finished. Handed to every {@link QuestCompletionListeners} subscriber the moment
 * {@link QuestEngine} records the completion, on the server thread.
 *
 * <p>For a {@code scope: server} quest the completion belongs to the world; {@code player} is then
 * simply whoever's action tipped it over, and the event fires exactly once for everyone.
 *
 * <p>POPIA/GDPR: the {@code player} field is the player's existing Minecraft game UUID, the same
 * identifier the progress store already uses. Subscribers must not log it.
 *
 * @param server  the running server
 * @param player  the completing player, or the triggering player for {@link QuestScope#SERVER}
 * @param quest   the quest that completed
 */
public record QuestCompletion(MinecraftServer server, UUID player, Quest quest) {
}
