package za.co.neroland.neroquests.forge;

//? if >=1.20.5 {
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
//? if <1.21.6 {
/*import net.minecraftforge.eventbus.api.IEventBus;
*///?} else if <1.21.9 {
/*import net.minecraftforge.eventbus.api.bus.BusGroup;
*///?}

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.client.screen.QuestUnlockHud;

/**
 * The "Quest Unlocked" HUD layer. Only this class names Forge's overlay-layer event, so the client wiring
 * still links on a Forge build that predates it (checked with {@link #EVENT_CLASS} first). Before 1.20.5 the
 * layer is drawn from {@code RenderGuiEvent.Post} in {@link ForgeQuestClientEvents} instead.
 */
final class ForgeQuestHud {

    static final String EVENT_CLASS = "net.minecraftforge.client.event.AddGuiOverlayLayersEvent";

    private ForgeQuestHud() {
    }

    private static Identifier id() {
        return Identifier.fromNamespaceAndPath(NeroQuestsCommon.MOD_ID, "quest_unlocked");
    }

    //? if >=1.21.9 {
    static void register() {
        AddGuiOverlayLayersEvent.BUS.addListener(event -> event.getLayeredDraw().add(id(),
                (graphics, delta) -> QuestUnlockHud.render(graphics)));
    }
    //?} else if >=1.21.6 {
    /*static void register(BusGroup modBusGroup) {
        AddGuiOverlayLayersEvent.getBus(modBusGroup).addListener(event -> event.getLayeredDraw().add(id(),
                (graphics, delta) -> QuestUnlockHud.render(graphics)));
    }
    *///?} else {
    /*static void register(IEventBus modEventBus) {
        modEventBus.addListener((AddGuiOverlayLayersEvent event) -> event.getLayeredDraw().add(id(),
                (graphics, delta) -> QuestUnlockHud.render(graphics)));
    }
    *///?}
}
//?}
