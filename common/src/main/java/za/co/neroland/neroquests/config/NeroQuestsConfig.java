package za.co.neroland.neroquests.config;

import za.co.neroland.nerolandcore.config.ConfigManager;
import za.co.neroland.nerolandcore.config.ConfigSchema;
import za.co.neroland.nerolandcore.config.ConfigValue;
import za.co.neroland.neroquests.NeroQuestsCommon;

/**
 * NeroQuests config schema, built on Neroland Core's config framework
 * (file {@code neroquests.properties}, hot-reloadable via {@code /neroland config reload}).
 * Registered once from {@link NeroQuestsCommon#init()}.
 *
 * <p>Gameplay/progression-affecting values are {@code serverAuthoritative} so the
 * server dictates them to every client; data-protection tunables mirror Core's
 * conventions (see PRIVACY.md).
 *
 * <p><b>POPIA/GDPR:</b> {@code telemetryEnabled} is deliberately <b>not</b>
 * server-authoritative — anonymous crash reporting is a per-client opt-out that a
 * server must never force on or off. The server-sync snapshot carries only config
 * keys/values, never player data.
 */
public final class NeroQuestsConfig {

    public static final ConfigSchema SCHEMA =
            ConfigSchema.create(NeroQuestsCommon.MOD_ID, "NeroQuests configuration.");

    // --- Crash telemetry (client-local opt-out) -----------------------------
    private static final ConfigValue<Boolean> TELEMETRY = SCHEMA.bool(
            "telemetryEnabled", true, false,
            "send anonymous, NeroQuests-only crash reports (Sentry, EU servers) - stack trace, "
                    + "mod/MC/loader/OS/Java versions, your other installed mods, this mod's config, "
                    + "recent in-game actions, anonymous stability/timing; no IP, username, UUID, world "
                    + "data, quest progress or chat; file paths scrubbed of your account name. "
                    + "false = opt out of all of it. See PRIVACY.md");

    // --- Progression gating (server-authoritative) --------------------------
    public static final ConfigValue<Boolean> GATE_WRITES_ENABLED = SCHEMA.bool(
            "gateWritesEnabled", true, true,
            "Whether quest rewards write Neroland Core progression-gate flags. Disable to run "
                    + "NeroQuests as pure content without enforcing ecosystem progression.");

    // --- Cross-mod degradation (server-authoritative) -----------------------
    public static final ConfigValue<String> MISSING_MOD_OBJECTIVE_POLICY = SCHEMA.string(
            "missingModObjectivePolicy", "skip", true,
            "How objectives referencing an absent mod's content (unknown id / empty tag) behave: "
                    + "'skip' (objective ignored, quest completable without it) or 'autocomplete' "
                    + "(objective counts as done immediately).");

    // --- Data retention (POPIA/GDPR) ----------------------------------------
    public static final ConfigValue<Integer> RETENTION_DAYS = SCHEMA.intRange(
            "questDataRetentionDays", 0, 0, 3650, false,
            "Days of inactivity after which a player's quest progress is purged when Core's "
                    + "purge-inactive runs (0 = follow Core's dataRetentionDays only).");

    private NeroQuestsConfig() {
    }

    /**
     * Whether anonymous NeroQuests-only crash reporting is on (default true, opt-out).
     * Read once at bootstrap by {@code NeroQuestsTelemetry.init()}; changes take effect on restart.
     */
    public static boolean isTelemetryEnabled() {
        return TELEMETRY.get();
    }

    /** Registers the schema with Core's ConfigManager. Called once from common init. */
    public static void init() {
        ConfigManager.register(SCHEMA);
    }
}
