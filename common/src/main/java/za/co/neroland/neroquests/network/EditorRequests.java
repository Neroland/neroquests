package za.co.neroland.neroquests.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.custom.CustomLimits;
import za.co.neroland.neroquests.custom.CustomQuests;
import za.co.neroland.neroquests.custom.EditResult;
import za.co.neroland.neroquests.platform.Services;
import za.co.neroland.neroquests.quest.ObjectiveSpec;
import za.co.neroland.neroquests.quest.RewardSpec;

/**
 * Server side of the quest editor's packets (E1): each handler passes the per-player inbound cap, hops onto
 * the server thread, refuses a player who is dead, spectating or gone, re-derives the editor permission and
 * spends a rate-limit token <em>before decoding anything</em> (J2, {@link EditorIntake}), then hands the
 * request's fields to the matching {@link CustomQuests} method — which checks immutability, validates and
 * previews — and answers with an {@link EditorPayloads.Result} (refusals at most twice a second).
 *
 * <p>The client's {@code can_author} hint plays no part here. Objective and reward JSON is decoded with the
 * same codecs the store uses (registry-aware), strictly: a list over the per-quest cap or an element that
 * does not decode is a {@link EditResult#BAD_REQUEST} refusal. Nothing is
 * logged about the player or the text.
 */
public final class EditorRequests {

    /** The pre-decode limits (J2, RT-02). */
    private static final EditorIntake INTAKE = new EditorIntake();

    private EditorRequests() {
    }

    public static void createChapter(EditorPayloads.CreateChapter p, ServerPlayer player) {
        run(player, p.requestId(), actor -> CustomQuests.createChapter(actor, p.title(), p.icon().orElse(null)));
    }

    public static void updateChapter(EditorPayloads.UpdateChapter p, ServerPlayer player) {
        run(player, p.requestId(), actor -> CustomQuests.updateChapter(actor, p.chapter(), p.title(),
                p.icon().orElse(null), p.order()));
    }

    public static void deleteChapter(EditorPayloads.DeleteChapter p, ServerPlayer player) {
        run(player, p.requestId(), actor -> CustomQuests.deleteChapter(actor, p.chapter()));
    }

    public static void createQuest(EditorPayloads.CreateQuest p, ServerPlayer player) {
        run(player, p.requestId(), actor -> decode(player, p.objectivesJson(), ObjectiveSpec.CODEC,
                CustomLimits.MAX_OBJECTIVES)
                .map(objectives -> CustomQuests.createQuest(actor, p.chapter(), p.title(), objectives, p.x(), p.y()))
                .orElseGet(() -> EditResult.error(EditResult.BAD_REQUEST)));
    }

    public static void updateQuest(EditorPayloads.UpdateQuest p, ServerPlayer player) {
        run(player, p.requestId(), actor -> CustomQuests.updateQuest(actor, p.quest(), p.title(), p.description(),
                p.icon().orElse(null)));
    }

    public static void deleteQuest(EditorPayloads.DeleteQuest p, ServerPlayer player) {
        run(player, p.requestId(), actor -> CustomQuests.deleteQuest(actor, p.quest()));
    }

    public static void moveNode(EditorPayloads.MoveNode p, ServerPlayer player) {
        run(player, p.requestId(), actor -> CustomQuests.moveNode(actor, p.quest(), p.x(), p.y()));
    }

    public static void setPrerequisites(EditorPayloads.SetPrerequisites p, ServerPlayer player) {
        run(player, p.requestId(), actor -> CustomQuests.setPrerequisites(actor, p.quest(), p.prerequisites()));
    }

    public static void setObjectives(EditorPayloads.SetObjectives p, ServerPlayer player) {
        run(player, p.requestId(), actor -> decode(player, p.objectivesJson(), ObjectiveSpec.CODEC,
                CustomLimits.MAX_OBJECTIVES)
                .map(objectives -> CustomQuests.setObjectives(actor, p.quest(), objectives))
                .orElseGet(() -> EditResult.error(EditResult.BAD_REQUEST)));
    }

    public static void setRewards(EditorPayloads.SetRewards p, ServerPlayer player) {
        run(player, p.requestId(), actor -> decode(player, p.rewardsJson(), RewardSpec.CODEC, CustomLimits.MAX_REWARDS)
                .map(rewards -> CustomQuests.setRewards(actor, p.quest(), rewards, p.escrowCopies()))
                .orElseGet(() -> EditResult.error(EditResult.BAD_REQUEST)));
    }

    public static void setRepeatable(EditorPayloads.SetRepeatable p, ServerPlayer player) {
        run(player, p.requestId(), actor -> CustomQuests.setRepeatable(actor, p.quest(), p.repeatable(),
                p.cooldownSeconds()));
    }

    /** Server stop: forget the intake buckets. */
    public static void serverStopped() {
        INTAKE.clear();
    }

    // --- shared ------------------------------------------------------------------------------------

    /**
     * J2 (RT-02): the inbound cap first (excess dropped unanswered, nothing queued); then, on the server thread,
     * the editor permission and the per-player buckets ({@link CustomQuests#admit}) and a server-wide slot —
     * all <em>before</em> {@code action} decodes any JSON. A refusal is answered at most twice a second.
     */
    private static void run(ServerPlayer player, int requestId, Function<CustomQuests.Actor, EditResult> action) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        UUID key = player.getUUID();
        if (!INTAKE.inbound(key, System.currentTimeMillis())) {
            return;
        }
        // execute() runs at once when already on the server thread (NeoForge's enqueueWork), else queues.
        server.execute(() -> {
            if (player.hasDisconnected()) {
                return;
            }
            long now = System.currentTimeMillis();
            Optional<String> refusal = !player.isAlive() || player.isSpectator()
                    ? Optional.of(EditResult.DENIED)
                    : CustomQuests.admit(player);
            if (refusal.isEmpty() && !INTAKE.globalSlot(now)) {
                refusal = Optional.of(EditResult.RATE_LIMITED);
            }
            if (refusal.isPresent()) {
                if (INTAKE.replyToRefusal(key, now)) {
                    Services.NETWORK.sendToPlayer(player,
                            EditorPayloads.Result.of(requestId, EditResult.error(refusal.get())));
                }
                return;
            }
            EditResult result;
            try {
                result = action.apply(CustomQuests.Actor.admitted(player));
            } catch (RuntimeException e) {
                NeroQuestsCommon.LOGGER.warn("[NeroQuests] An editor request failed ({}).",
                        e.getClass().getSimpleName());
                result = EditResult.error(EditResult.BAD_REQUEST);
            }
            Services.NETWORK.sendToPlayer(player, EditorPayloads.Result.of(requestId, result));
        });
    }

    /**
     * A JSON array of at most {@code max} elements, each decoded strictly with {@code codec}; empty when the
     * text is not such an array or any element fails (the server API then sees nothing and the client gets
     * {@link EditResult#BAD_REQUEST}). An over-long list is passed through cut to cap+1 elements so the API
     * reports its own cap refusal.
     */
    static <T> Optional<List<T>> decode(ServerPlayer player, String json, Codec<T> codec, int max) {
        JsonArray array = parseArray(json);
        if (array == null) {
            return Optional.empty();
        }
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, player.level().registryAccess());
        List<T> out = new ArrayList<>(Math.min(array.size(), max + 1));
        for (JsonElement element : array) {
            if (out.size() > max) {
                break;
            }
            Optional<T> value = codec.parse(ops, element).result();
            if (value.isEmpty()) {
                return Optional.empty();
            }
            out.add(value.get());
        }
        return Optional.of(List.copyOf(out));
    }

    /** The text as a JSON array, or null. Pure (for tests). */
    static JsonArray parseArray(String json) {
        if (json == null || json.isEmpty() || json.length() > EditorPayloads.MAX_JSON) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
