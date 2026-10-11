package za.co.neroland.neroquests.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * Extra lines for {@code /neroquests reload-check}, appended after the per-source report (B5). The
 * hook later stages hang their own state on — the editor's lock state and custom-store counts (Stage D)
 * — without the command having to know about them.
 *
 * <p>A section returns translatable components only; it must never include player names, UUIDs or
 * author-written text. A section that throws is skipped (logged by class name only).
 */
public final class ReloadCheckSections {

    private static final List<Function<MinecraftServer, List<Component>>> SECTIONS = new CopyOnWriteArrayList<>();

    private ReloadCheckSections() {
    }

    /** Register a section (permanent; call once during init). */
    public static void register(Function<MinecraftServer, List<Component>> section) {
        SECTIONS.add(section);
    }

    /** Every registered section's lines, in registration order. Server thread only. */
    static List<Component> lines(MinecraftServer server) {
        List<Component> out = new ArrayList<>();
        for (Function<MinecraftServer, List<Component>> section : SECTIONS) {
            try {
                List<Component> lines = section.apply(server);
                if (lines != null) {
                    out.addAll(lines);
                }
            } catch (RuntimeException e) {
                NeroQuestsCommon.LOGGER.warn("[NeroQuests] A reload-check section ({}) failed.",
                        section.getClass().getName(), e);
            }
        }
        return out;
    }
}
