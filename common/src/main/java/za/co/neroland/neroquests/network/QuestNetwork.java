package za.co.neroland.neroquests.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.client.ClientEditor;
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
 * <p>The server is authoritative for every quest decision and the client merely renders what it is
 * told. The only client &rarr; server traffic is the quest editor's requests (E1, {@link EditorPayloads}):
 * one payload per custom-quest API call, each re-authorised and validated by the server
 * ({@link EditorRequests}) and answered with a {@link EditorPayloads.Result} toast.
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

    /** A client &rarr; server payload plus the server-side handler (with the sending player). */
    public record Serverbound<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {
    }

    private static final List<Clientbound<?>> CLIENTBOUND = new ArrayList<>();
    private static final List<Serverbound<?>> SERVERBOUND = new ArrayList<>();

    private QuestNetwork() {
    }

    public static <T extends CustomPacketPayload> void clientbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {
        CLIENTBOUND.add(new Clientbound<>(type, codec, handler));
    }

    public static <T extends CustomPacketPayload> void serverbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {
        SERVERBOUND.add(new Serverbound<>(type, codec, handler));
    }

    /** Every declared server &rarr; client payload, for each loader's registration pass. */
    public static List<Clientbound<?>> clientbound() {
        return CLIENTBOUND;
    }

    /** Every declared client &rarr; server payload, for each loader's registration pass. */
    public static List<Serverbound<?>> serverbound() {
        return SERVERBOUND;
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
        // E1: the quest editor. The reply is a plain-data mirror update (safe on a dedicated server).
        clientbound(EditorPayloads.Result.TYPE, EditorPayloads.Result.STREAM_CODEC, ClientEditor::accept);
        serverbound(EditorPayloads.CreateChapter.TYPE, EditorPayloads.CreateChapter.STREAM_CODEC,
                EditorRequests::createChapter);
        serverbound(EditorPayloads.UpdateChapter.TYPE, EditorPayloads.UpdateChapter.STREAM_CODEC,
                EditorRequests::updateChapter);
        serverbound(EditorPayloads.DeleteChapter.TYPE, EditorPayloads.DeleteChapter.STREAM_CODEC,
                EditorRequests::deleteChapter);
        serverbound(EditorPayloads.CreateQuest.TYPE, EditorPayloads.CreateQuest.STREAM_CODEC,
                EditorRequests::createQuest);
        serverbound(EditorPayloads.UpdateQuest.TYPE, EditorPayloads.UpdateQuest.STREAM_CODEC,
                EditorRequests::updateQuest);
        serverbound(EditorPayloads.DeleteQuest.TYPE, EditorPayloads.DeleteQuest.STREAM_CODEC,
                EditorRequests::deleteQuest);
        serverbound(EditorPayloads.MoveNode.TYPE, EditorPayloads.MoveNode.STREAM_CODEC, EditorRequests::moveNode);
        serverbound(EditorPayloads.SetPrerequisites.TYPE, EditorPayloads.SetPrerequisites.STREAM_CODEC,
                EditorRequests::setPrerequisites);
        serverbound(EditorPayloads.SetObjectives.TYPE, EditorPayloads.SetObjectives.STREAM_CODEC,
                EditorRequests::setObjectives);
        serverbound(EditorPayloads.SetRewards.TYPE, EditorPayloads.SetRewards.STREAM_CODEC,
                EditorRequests::setRewards);
        serverbound(EditorPayloads.SetRepeatable.TYPE, EditorPayloads.SetRepeatable.STREAM_CODEC,
                EditorRequests::setRepeatable);
    }

    /**
     * Drops every client-side mirror. Each loader calls this when the client leaves a world or
     * server, so one session's quests can never bleed into the next — or appear at all on a
     * server that does not run NeroQuests.
     */
    public static void clearClientCaches() {
        ClientQuestDefinitions.clear();
        ClientQuestProgress.clear();
        ClientEditor.clear();
    }
}
