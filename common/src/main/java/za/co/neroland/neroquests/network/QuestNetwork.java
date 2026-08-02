package za.co.neroland.neroquests.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import za.co.neroland.neroquests.client.ClientQuestDefinitions;
import za.co.neroland.neroquests.client.ClientQuestProgress;
import za.co.neroland.neroquests.platform.Services;

/**
 * The cross-loader payload registry: NeroQuests declares its payloads here once (type + stream
 * codec + client handler), and each loader module iterates the list and wires it to its own
 * networking API — NeoForge's {@code PayloadRegistrar}, Forge's {@code ChannelBuilder}, Fabric's
 * {@code PayloadTypeRegistry} + {@code ClientPlayNetworking}. Sending goes through the
 * {@link Services#NETWORK} seam.
 *
 * <p>This is Neroland Core's {@code CoreNetwork} architecture reproduced on NeroQuests' own
 * channel. It cannot reuse Core's instance: Core drains its payload lists during Core's own
 * bootstrap (on Forge the channel is {@code build()}-sealed inside Core's constructor), so a
 * downstream registration would be silently dropped — see
 * {@link za.co.neroland.neroquests.platform.NetworkPlatform} for the full reasoning.
 *
 * <p>Stage 6 is server &rarr; client only: the server is authoritative for every quest decision and
 * the client merely renders what it is told, so there is nothing for a client to send yet. The
 * serverbound half of the seam ({@code Services.NETWORK.sendToServer}) exists for the quest book's
 * later interactions and follows the same declare-once pattern.
 *
 * <p>Both handlers are plain data updates on {@code client/} mirror caches, which hold no
 * client-only imports — so this class is safe to load on a dedicated server, where the handlers
 * are registered as types but never invoked.
 */
public final class QuestNetwork {

    /** A server &rarr; client payload plus the client-side handler that consumes it. */
    public record Clientbound<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {
    }

    private static final List<Clientbound<?>> CLIENTBOUND = new ArrayList<>();

    private QuestNetwork() {
    }

    public static <T extends CustomPacketPayload> void clientbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {
        CLIENTBOUND.add(new Clientbound<>(type, codec, handler));
    }

    /** Every declared server &rarr; client payload, for each loader's registration pass. */
    public static List<Clientbound<?>> clientbound() {
        return CLIENTBOUND;
    }

    /**
     * Declares the payloads. Called once from common init, before any loader registers them
     * (each loader entry point runs common init first, then its own network registration).
     */
    public static void init() {
        if (!CLIENTBOUND.isEmpty()) {
            return; // defensive: a second call must not duplicate registrations
        }
        clientbound(QuestDefinitionsPayload.TYPE, QuestDefinitionsPayload.STREAM_CODEC,
                ClientQuestDefinitions::accept);
        clientbound(QuestProgressPayload.TYPE, QuestProgressPayload.STREAM_CODEC,
                ClientQuestProgress::accept);
    }

    /**
     * Drops every client-side mirror. Each loader calls this when the client leaves a world or
     * server, so one session's quests can never bleed into the next — or appear at all on a
     * server that does not run NeroQuests.
     */
    public static void clearClientCaches() {
        ClientQuestDefinitions.clear();
        ClientQuestProgress.clear();
    }
}
