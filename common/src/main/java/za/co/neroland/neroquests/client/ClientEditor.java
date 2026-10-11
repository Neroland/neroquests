package za.co.neroland.neroquests.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import za.co.neroland.neroquests.network.EditorPayloads;
import za.co.neroland.neroquests.platform.Services;

/**
 * The client half of the quest editor's request/reply loop (E1). Pure data with no client-only imports (like
 * the other mirrors in this package), so the reply handler can be registered on a dedicated server.
 *
 * <p>{@link #send} gives each request a fresh id and remembers an optional callback; {@link #accept} (the
 * {@link EditorPayloads.Result} handler) runs the callback and queues a toast for the open book. Nothing here
 * decides anything: every request is checked by the server, and the screen only learns what happened from
 * the reply and the next definitions sync.
 */
public final class ClientEditor {

    /** Most toasts kept while the book is closed (older ones are dropped). */
    private static final int MAX_TOASTS = 4;
    /** Most callbacks waiting at once (a stuck server must not grow this forever). */
    private static final int MAX_PENDING = 64;

    private static final Map<Integer, Consumer<EditorPayloads.Result>> PENDING = new HashMap<>();
    private static final Deque<Component> TOASTS = new ArrayDeque<>();
    private static int nextId = 1;

    private ClientEditor() {
    }

    /**
     * Send one editor request built by {@code request} from a fresh request id; {@code onReply} (may be null)
     * runs when the server's result arrives. Returns the request id.
     */
    public static synchronized int send(IntFunction<? extends CustomPacketPayload> request,
                                        Consumer<EditorPayloads.Result> onReply) {
        int id = nextId++;
        if (nextId == Integer.MAX_VALUE) {
            nextId = 1;
        }
        if (onReply != null) {
            if (PENDING.size() >= MAX_PENDING) {
                PENDING.clear();
            }
            PENDING.put(Integer.valueOf(id), onReply);
        }
        Services.NETWORK.sendToServer(request.apply(id));
        return id;
    }

    /** The server answered a request (client thread). */
    public static void accept(EditorPayloads.Result result) {
        Consumer<EditorPayloads.Result> callback;
        synchronized (ClientEditor.class) {
            callback = PENDING.remove(Integer.valueOf(result.requestId()));
            toast(result.toEditResult().message());
        }
        if (callback != null) {
            callback.accept(result);
        }
    }

    /** Queue a toast for the book (also used by the screen for its own short messages). */
    public static synchronized void toast(Component message) {
        while (TOASTS.size() >= MAX_TOASTS) {
            TOASTS.removeFirst();
        }
        TOASTS.addLast(message);
    }

    /** The oldest queued toast, or null. */
    public static synchronized Component pollToast() {
        return TOASTS.pollFirst();
    }

    /** Leaving a world: forget pending requests and toasts. */
    public static synchronized void clear() {
        PENDING.clear();
        TOASTS.clear();
    }
}
