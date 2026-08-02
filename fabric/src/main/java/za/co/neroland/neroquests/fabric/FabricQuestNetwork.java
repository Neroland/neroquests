package za.co.neroland.neroquests.fabric;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.network.QuestNetwork;
import za.co.neroland.neroquests.platform.NetworkPlatform;

/**
 * Fabric side of the networking seam. {@link #registerCommon()} (mod init, both sides) registers
 * the payload types; {@link #registerClient()} (client init) registers the receivers, keeping
 * {@code ClientPlayNetworking} off the dedicated server. Registered as the
 * {@link NetworkPlatform} implementation via {@code META-INF/services}.
 *
 * <p>Receivers hop to the client thread via {@code context.client().execute}, which is what makes
 * the plain-data mirror caches in {@code client/} safe without any locking.
 */
public final class FabricQuestNetwork implements NetworkPlatform {

    /** Mod-init (both sides): payload types only — NeroQuests has no serverbound payload yet. */
    public static void registerCommon() {
        for (QuestNetwork.Clientbound<?> cb : QuestNetwork.clientbound()) {
            registerClientboundType(cb);
        }
    }

    /** Client-init: clientbound receivers (client-only API). */
    public static void registerClient() {
        for (QuestNetwork.Clientbound<?> cb : QuestNetwork.clientbound()) {
            registerClientReceiver(cb);
        }
    }

    private static <T extends CustomPacketPayload> void registerClientboundType(QuestNetwork.Clientbound<T> cb) {
        PayloadTypeRegistry.clientboundPlay().register(cb.type(), cb.codec());
    }

    private static <T extends CustomPacketPayload> void registerClientReceiver(QuestNetwork.Clientbound<T> cb) {
        ClientPlayNetworking.registerGlobalReceiver(cb.type(), (payload, context) ->
                context.client().execute(() -> cb.handler().accept(payload)));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }
}
