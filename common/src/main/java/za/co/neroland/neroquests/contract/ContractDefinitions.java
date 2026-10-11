package za.co.neroland.neroquests.contract;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.quest.QuestDefinitions;

/**
 * The daily-contract pool, read lazily from {@code data/<namespace>/neroquests/contracts/*.json} and
 * cached per server and {@link ResourceManager} instance, so a {@code /reload} is picked up the next
 * time the pool is asked for (the same detection {@code QuestDefinitions} uses). A file with any
 * decode error is dropped whole (AUD-04, never a partial decode) with a warning naming the file only,
 * and reported in {@link #validationIssues()} for {@code /neroquests reload-check}.
 */
public final class ContractDefinitions {

    private static final String DIRECTORY = "neroquests/contracts";
    private static final String EXTENSION = ".json";

    private static MinecraftServer loadedFor;
    private static ResourceManager loadedFrom;
    private static List<DailyContract> contracts = List.of();
    private static List<QuestDefinitions.ValidationIssue> issues = List.of();

    private ContractDefinitions() {
    }

    /** Every loaded contract, sorted by id (a stable order for the daily draw). */
    public static synchronized List<DailyContract> forServer(MinecraftServer server) {
        if (server != loadedFor || server.getResourceManager() != loadedFrom) {
            loadedFor = server;
            loadedFrom = server.getResourceManager();
            List<QuestDefinitions.ValidationIssue> collected = new ArrayList<>();
            contracts = load(loadedFrom, collected);
            issues = List.copyOf(collected);
        }
        return contracts;
    }

    /** The server stopped (AUD-31): drop the references to it and to what was read from it. */
    public static synchronized void serverStopped() {
        loadedFor = null;
        loadedFrom = null;
        contracts = List.of();
        issues = List.of();
    }

    /** The contract files the last load dropped, with reasons (resource ids and codec messages only). */
    public static synchronized List<QuestDefinitions.ValidationIssue> validationIssues() {
        return issues;
    }

    private static List<DailyContract> load(ResourceManager resources,
                                            List<QuestDefinitions.ValidationIssue> collected) {
        Map<String, DailyContract> loaded = new TreeMap<>();
        Map<Identifier, Resource> files =
                resources.listResources(DIRECTORY, file -> file.getPath().endsWith(EXTENSION));
        for (Map.Entry<Identifier, Resource> file : files.entrySet()) {
            String path = file.getKey().getPath();
            Identifier id = Identifier.fromNamespaceAndPath(file.getKey().getNamespace(),
                    path.substring(DIRECTORY.length() + 1, path.length() - EXTENSION.length()));
            try (BufferedReader reader = file.getValue().openAsReader()) {
                QuestDefinitions.decodeStrict(
                                DailyContract.Data.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)),
                                error -> {
                                    NeroQuestsCommon.LOGGER.warn(
                                            "[NeroQuests] Bad contract definition {}: {}", id, error);
                                    collected.add(new QuestDefinitions.ValidationIssue(
                                            QuestDefinitions.ValidationIssue.Severity.DROPPED, id,
                                            "bad contract definition: " + error));
                                })
                        .ifPresent(data -> loaded.put(id.toString(), new DailyContract(id, data)));
            } catch (Exception e) {
                NeroQuestsCommon.LOGGER.warn("[NeroQuests] Could not read contract {} ({})", id,
                        e.getClass().getSimpleName());
                collected.add(new QuestDefinitions.ValidationIssue(QuestDefinitions.ValidationIssue.Severity.DROPPED,
                        id, "could not be read (" + e.getClass().getSimpleName() + ")"));
            }
        }
        return List.copyOf(loaded.values());
    }
}
