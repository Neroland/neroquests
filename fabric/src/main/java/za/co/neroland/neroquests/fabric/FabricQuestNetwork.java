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

    /**
     * Mod-init (both sides): the clientbound payload <em>types</em>, plus the serverbound (quest editor)
     * types and their receivers. Serverbound receivers belong here rather than in {@link #registerClient()},
     * because a dedicated server needs them — and keeping them out of the client method is what stops
     * {@code ClientPlayNetworking} ever loading on one.
     */
    public static void registerCommon() {
        for (QuestNetwork.Clientbound<?> cb : QuestNetwork.clientbound()) {
            registerClientboundType(cb);
        }
        for (QuestNetwork.Serverbound<?> sb : QuestNetwork.serverbound()) {
            registerServerbound(sb);
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

    private static <T extends CustomPacketPayload> void registerServerbound(QuestNetwork.Serverbound<T> sb) {
        PayloadTypeRegistry.serverboundPlay().register(sb.type(), sb.codec());
        // The common handler hops onto the server thread itself (EditorRequests), so it is called directly.
        ServerPlayNetworking.registerGlobalReceiver(sb.type(), (payload, context) ->
                sb.handler().accept(payload, context.player()));
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
