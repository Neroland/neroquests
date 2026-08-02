package za.co.neroland.neroquests.quest.engine;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * The in-mod notification channel for quest completions — the seam reward execution subscribes to,
 * and the place any later system (network sync, advancements, a chat announcement) hooks in
 * without {@link QuestEngine} having to know about it.
 *
 * <p>Mirrors the shape of Neroland Core's own change-event channels
 * ({@code GateEvents} / {@code ThresholdEvents}): a copy-on-write listener list, listeners run on
 * the server thread, registration is permanent.
 *
 * <p>A listener that throws is logged and skipped — one misbehaving subscriber must never abort a
 * completion that has already been written to the progress store, nor stop the other subscribers.
 */
public final class QuestCompletionListeners {

    private static final List<Consumer<QuestCompletion>> LISTENERS = new CopyOnWriteArrayList<>();

    private QuestCompletionListeners() {
    }

    /** Register a completion listener (runs on the server thread). */
    public static void onCompletion(Consumer<QuestCompletion> listener) {
        LISTENERS.add(listener);
    }

    /** Publish a completion to every listener. Server thread only. */
    public static void fire(QuestCompletion completion) {
        for (Consumer<QuestCompletion> listener : LISTENERS) {
            try {
                listener.accept(completion);
            } catch (RuntimeException e) {
                // Resource id only — never who completed it.
                NeroQuestsCommon.LOGGER.warn("[NeroQuests] A completion listener failed for quest {}.",
                        completion.quest().id(), e);
            }
        }
    }
}
