package za.co.neroland.neroquests.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import za.co.neroland.neroquests.client.QuestBookOpener;

/**
 * The quest book — used in hand it opens the quest book screen.
 *
 * <p>The book is a pure <b>view</b>: opening it sends nothing to the server and grants nothing. All
 * of its content comes from the caches the server already pushed
 * ({@code ClientQuestDefinitions} / {@code ClientQuestProgress}), so the item's whole job is to put
 * a screen on the client's display.
 *
 * <p>That screen is client-only code, so it is reached through {@link QuestBookOpener} rather than
 * referenced directly: on a dedicated server the branch below never runs, the opener is never
 * installed, and no client class is ever loaded.
 */
public class QuestBookItem extends Item {

    public QuestBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            QuestBookOpener.open();
        }
        return InteractionResult.SUCCESS;
    }
}
