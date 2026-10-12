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
                    + "On by default; set false to opt out of all of it (takes effect on restart). See PRIVACY.md");

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
                    + "(objective counts as done immediately). A quest with no objective present here is "
                    + "never completable under either policy.");

    public static final ConfigValue<Boolean> DEGRADED_COMPLETIONS_PAY = SCHEMA.bool(
            "degradedCompletionsPay", false, true,
            "Whether a quest completed with an objective auto-satisfied by "
                    + "missingModObjectivePolicy=autocomplete still pays its rewards. Off by default, so "
                    + "absent content can never be turned into free rewards.");

    // --- Reward delivery (server-authoritative) -----------------------------
    public static final ConfigValue<Boolean> OWED_ITEMS_DROP_WHEN_FULL = SCHEMA.bool(
            "owedItemsDropWhenFull", false, true,
            "When a player's inventory cannot hold an item reward: false (default) keeps the overflow for "
                    + "them and delivers it once they have room; true drops it at their feet (the old behaviour).");

    // --- Daily contracts (server-authoritative) ------------------------------
    public static final ConfigValue<Boolean> DAILY_CONTRACTS_ENABLED = SCHEMA.bool(
            "dailyContractsEnabled", true, true,
            "Offer daily hand-in contracts (/neroquests contracts). Their credit rewards need an economy "
                    + "with a budgeted payout API (NeroEconomy); without one no contract is offered.");

    public static final ConfigValue<Integer> DAILY_CONTRACTS_PER_DAY = SCHEMA.intRange(
            "dailyContractsPerDay", 3, 1, 10, true,
            "How many contracts are drawn from the contract pool each (UTC) day. A player is offered only "
                    + "those the economy's budget can still pay today.");

    // --- In-game quest editor (server-authoritative, D1-D3) -----------------
    public static final ConfigValue<Boolean> EDITOR_REQUIRES_CREATIVE = SCHEMA.bool(
            "editorRequiresCreative", true, true,
            "Whether being in Creative mode is enough to author custom quests (true, default). Set false so "
                    + "only operators at editorMinOpLevel and players on the editor allow-list may author. "
                    + "Spectators and dead players never may; /neroquests editor lock closes it for all but level 4.");

    public static final ConfigValue<Integer> EDITOR_MIN_OP_LEVEL = SCHEMA.intRange(
            "editorMinOpLevel", 2, 1, 4, true,
            "Operator permission level that may author custom quests in any game mode.");

    public static final ConfigValue<Integer> CUSTOM_QUEST_MAX_TOTAL = SCHEMA.intRange(
            "customQuestMaxTotal", 500, 0, 5000, true,
            "Most custom (editor-made) quests this world may hold.");

    public static final ConfigValue<Integer> CUSTOM_CHAPTER_MAX = SCHEMA.intRange(
            "customChapterMax", 20, 0, 200, true,
            "Most custom (editor-made) chapters this world may hold.");

    public static final ConfigValue<Integer> CUSTOM_REWARD_MAX_STACKS = SCHEMA.intRange(
            "customRewardMaxStacks", 9, 0, 9, true,
            "Most item-stack rewards one custom quest may pay (each at most one full stack).");

    public static final ConfigValue<Integer> CUSTOM_REWARD_MAX_XP = SCHEMA.intRange(
            "customRewardMaxXp", 500, 0, 100000, true,
            "Most experience points one custom quest may pay per completion.");

    public static final ConfigValue<Integer> CUSTOM_REWARD_MAX_CURRENCY = SCHEMA.intRange(
            "customRewardMaxCurrency", 1000, 0, 1000000, true,
            "Most currency one custom quest may pay per completion (paid through the economy's budgeted "
                    + "payouts when NeroEconomy is installed).");

    public static final ConfigValue<Boolean> CUSTOM_REWARDS_ESCROWED = SCHEMA.bool(
            "customRewardsEscrowed", false, true,
            "When true, item rewards of custom quests must be deposited by the author (taken from their "
                    + "inventory, refunded when changed or deleted) and are paid from that deposit; loot-table "
                    + "rewards are refused. For servers that let non-operators author quests.");

    public static final ConfigValue<Integer> CUSTOM_CURRENCY_DAILY_CAP = SCHEMA.intRange(
            "customCurrencyDailyCap", 2000, 0, 1000000000, true,
            "Most credits custom (editor-made) quests may pay out in this world per UTC day, all players "
                    + "together (0 = no world-wide cap). A payout past the cap is reduced or withheld and the "
                    + "player is told.");

    public static final ConfigValue<Integer> CUSTOM_REWARDS_PER_PLAYER_DAILY = SCHEMA.intRange(
            "customRewardsPerPlayerDaily", 20, 0, 10000, true,
            "Most paid completions of custom (editor-made) quests one player may receive per UTC day (0 = no "
                    + "limit). Further completions that day still complete but pay nothing; the player is told.");

    public static final ConfigValue<String> CUSTOM_LOOT_TABLE_PREFIXES = SCHEMA.string(
            "customLootTablePrefixes", "minecraft:chests/,minecraft:gameplay/fishing", true,
            "Comma-separated loot-table id prefixes a custom (editor-made) quest's loot_table reward may name. "
                    + "Empty = no loot-table rewards for custom quests. Each loot_table reward also counts as one "
                    + "slot toward customRewardMaxStacks.");

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
