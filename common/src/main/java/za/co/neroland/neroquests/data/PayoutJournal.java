package za.co.neroland.neroquests.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.Identifier;

/**
 * Write-ahead record of currency payouts that are in flight (§13 rewards, J1). Before NeroQuests asks the
 * economy to pay, the payout — with the request key it will use — is written to this small file and forced to
 * disk; when the call returns it is removed again (paid, or moved into the owed queue of the quest store). A
 * file that still holds entries on start-up means the server stopped in between: each entry is retried with
 * its <em>same</em> key (the economy answers {@code DUPLICATE} for a key it already applied, which counts as
 * paid) or, when still refused, kept as owed. So a crash between the economy paying and our save can never pay
 * twice, and never loses the payout either.
 *
 * <p>The file normally holds nothing: entries live for the duration of one economy call, or until the first
 * server tick after a crash. Pure apart from file I/O (the path is given), so the state machine is
 * unit-tested.
 *
 * <p>Privacy (POPIA/GDPR): an entry holds the payee's game UUID next to amount, reason, source quest id and
 * request key — nothing else, never a name. Erasure removes a player's entries ({@link #erase}); nothing here
 * is logged.
 */
public final class PayoutJournal {

    /** File name under the world's {@code data/} folder. */
    public static final String FILE_NAME = "neroquests_payout_journal.json";

    private static final int FORMAT = 1;

    /**
     * An in-flight payout: who it is for and the owed-shaped record (with its request key).
     *
     * @param coreDeposit J2 (RT-14): paid through Core's {@code CurrencyApi.deposit}, which takes no request key —
     *                    so a left-over entry cannot be told apart from a deposit that went through
     */
    public record Entry(UUID player, OwedReward reward, boolean coreDeposit) {

        /** An economy payout (keyed, safe to retry). */
        public Entry(UUID player, OwedReward reward) {
            this(player, reward, false);
        }
    }

    /** What start-up recovery does with each left-over entry (the server half of {@link #recover}). */
    public interface Settler {

        /** Ask the economy again with the entry's own request key; true when it stands as paid (incl. DUPLICATE). */
        boolean retry(UUID player, OwedReward reward);

        /** Still refused: keep it in the owed queue (retried later with the same key). */
        void keep(UUID player, OwedReward reward);

        /**
         * J2 (RT-14): a left-over Core deposit. Core's deposit takes no key, so whether it went through is unknown;
         * it is neither repeated nor kept as owed (that could pay twice) — only counted. Default: nothing.
         */
        default void unsettled(UUID player, OwedReward reward) {
        }
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private boolean loaded;

    public PayoutJournal(Path file) {
        this.file = file;
    }

    /** The entries on disk (a copy). */
    public synchronized List<Entry> pending() {
        load();
        return List.copyOf(entries);
    }

    /**
     * Record a payout before it is attempted and force it to disk.
     *
     * @return whether the record is durable (false: the write failed; the caller pays anyway, as before)
     */
    public synchronized boolean begin(UUID player, OwedReward reward) {
        return begin(new Entry(player, reward));
    }

    /** {@link #begin(UUID, OwedReward)} for any entry (J2: Core deposits too). */
    public synchronized boolean begin(Entry entry) {
        load();
        entries.add(entry);
        return write();
    }

    /** The payout with {@code requestKey} returned (paid, or now kept as owed): forget it. */
    public synchronized void end(String requestKey) {
        load();
        if (entries.removeIf(entry -> entry.reward().requestKey().equals(requestKey))) {
            write();
        }
    }

    /** POPIA/GDPR erasure: drop every entry of {@code player}. */
    public synchronized void erase(UUID player) {
        load();
        if (entries.removeIf(entry -> entry.player().equals(player))) {
            write();
        }
    }

    /**
     * Start-up recovery: every left-over entry is retried with its own key; still-refused ones are kept as
     * owed. The journal is empty afterwards.
     *
     * @return how many left-over entries were found
     */
    public static int recover(PayoutJournal journal, Settler settler) {
        List<Entry> left = journal.pending();
        for (Entry entry : left) {
            if (entry.coreDeposit()) {
                settler.unsettled(entry.player(), entry.reward());
                journal.end(entry.reward().requestKey());
                continue;
            }
            boolean paid;
            try {
                paid = settler.retry(entry.player(), entry.reward());
            } catch (RuntimeException e) {
                paid = false;
            }
            if (!paid) {
                settler.keep(entry.player(), entry.reward());
            }
            journal.end(entry.reward().requestKey());
        }
        return left.size();
    }

    // --- file ---------------------------------------------------------------------------

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        entries.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("pending")) {
                return;
            }
            for (JsonElement element : root.getAsJsonObject().getAsJsonArray("pending")) {
                decode(element).ifPresent(entries::add);
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable: treated as empty and replaced on the next write. Never logged with content.
            entries.clear();
        }
    }

    private static Optional<Entry> decode(JsonElement element) {
        try {
            JsonObject row = element.getAsJsonObject();
            UUID player = UUID.fromString(row.get("player").getAsString());
            OwedReward reward = new OwedReward(row.get("key").getAsString(),
                    id(row.get("currency").getAsString()), row.get("amount").getAsLong(),
                    row.get("reason").getAsString(), id(row.get("source").getAsString()),
                    row.get("created_at").getAsLong(), 0);
            if (reward.requestKey().isEmpty() || reward.amount() < 1L) {
                return Optional.empty();
            }
            boolean core = row.has("path") && "core".equals(row.get("path").getAsString());
            return Optional.of(new Entry(player, reward, core));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Identifier id(String raw) {
        Identifier id = Identifier.tryParse(raw);
        if (id == null) {
            throw new IllegalArgumentException("bad id");
        }
        return id;
    }

    private boolean write() {
        try {
            if (entries.isEmpty()) {
                Files.deleteIfExists(file);
                return true;
            }
            JsonObject root = new JsonObject();
            root.addProperty("format", FORMAT);
            JsonArray pending = new JsonArray();
            for (Entry entry : entries) {
                JsonObject row = new JsonObject();
                row.addProperty("player", entry.player().toString());
                row.addProperty("key", entry.reward().requestKey());
                row.addProperty("currency", entry.reward().currency().toString());
                row.addProperty("amount", entry.reward().amount());
                row.addProperty("reason", entry.reward().reason());
                row.addProperty("source", entry.reward().source().toString());
                row.addProperty("created_at", entry.reward().createdAt());
                if (entry.coreDeposit()) {
                    row.addProperty("path", "core");
                }
                pending.add(row);
            }
            root.add("pending", pending);
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(root.toString().getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
