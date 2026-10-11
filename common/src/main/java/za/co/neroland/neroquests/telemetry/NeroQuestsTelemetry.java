package za.co.neroland.neroquests.telemetry;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;

import io.sentry.Hint;
import io.sentry.IScopes;
import io.sentry.Scope;
import io.sentry.Scopes;
import io.sentry.SentryClient;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;

import za.co.neroland.neroquests.NeroQuestsCommon;
import za.co.neroland.neroquests.config.NeroQuestsConfig;
import za.co.neroland.neroquests.platform.Services;

/**
 * Crash/error reporting for NeroQuests via Sentry (EU ingest), built to satisfy both the CurseForge
 * moderation rule (external analytics must be disclosed and opt-out-able) and POPIA/GDPR
 * data-minimisation:
 *
 * <ul>
 *   <li><b>Opt-out:</b> gated on {@code telemetryEnabled} in {@link NeroQuestsConfig} (default ON,
 *       disclosed). Set it false to stop reporting (takes effect on restart).</li>
 *   <li><b>NeroQuests errors only:</b> {@code beforeSend} drops any event whose stack trace does not
 *       touch {@code za.co.neroland.neroquests}.</li>
 *   <li><b>No personal data:</b> {@code sendDefaultPii=false} (no IP), no server/host name, no user
 *       identity, no player names/UUIDs, no world or quest-progress data. Exception and log
 *       <em>messages are never sent</em> (AUD-10): an exception is reduced to its type and stack
 *       frames, a FATAL log line to a fixed token, and file paths are dropped from frames. No
 *       breadcrumbs, no performance traces. Remaining payload: exception types + frames,
 *       mod/MC/loader/OS/Java versions, config tags and the loaded-mod list.</li>
 *   <li><b>Own client, not the global hub (AUD-10):</b> NeroQuests never calls {@code Sentry.init}.
 *       It builds its own {@link SentryClient} bound to its own {@link Scopes}, so it neither replaces
 *       nor is replaced by Neroland Core's (or any other mod's) global Sentry setup, and it installs no
 *       JVM-wide uncaught-exception handler.</li>
 *   <li><b>Bounded volume:</b> per-session de-duplication plus a hard cap of
 *       {@value #MAX_EVENTS_PER_SESSION} events per game session.</li>
 * </ul>
 *
 * <p>{@link #init()} is called once per loader at bootstrap (from {@code NeroQuestsCommon.init()},
 * right after the config schema is registered) and reads loader facts through
 * {@link Services#PLATFORM}. Reporting is gated only on {@code telemetryEnabled} (default ON);
 * dev/IDE runs report too, tagged {@code environment=development} so they stay out of release
 * metrics. Full disclosure text: {@code PRIVACY.md} / {@code wiki/Telemetry.md}.</p>
 */
public final class NeroQuestsTelemetry {

    // TODO: a give-only `sentry_test` block (the Nerospace-style end-to-end check that calls
    // sendTestEvent on use) arrives with the first NeroQuests content registry — this mod has no
    // block/item registry yet.

    /** Sentry DSN — a public client key (write-only ingest), safe to ship in the jar. */
    private static final String DSN =
            "https://85d794072bc3acd87a5e3195d968b370@o4511183823241216.ingest.de.sentry.io/4511838560387152";

    /** Stack traces must contain this package for an event to be sent. */
    private static final String PACKAGE_MARKER = "za.co.neroland.neroquests";

    /** Hard cap on events per game session (data minimisation + noise control). */
    private static final int MAX_EVENTS_PER_SESSION = 10;

    /** Cap on how many mod ids are attached as crash context (payload bound). */
    private static final int MAX_MODS_REPORTED = 300;

    /** Fixed text standing in for a FATAL log line, whose free text is never sent (AUD-10). */
    private static final String WITHHELD_LOG_LINE = "FATAL log line naming " + PACKAGE_MARKER + " (text withheld)";

    private static volatile boolean active;

    /** NeroQuests' own scopes (never the global hub); null until {@link #start()}. */
    private static volatile IScopes scopes;

    private static final AtomicInteger eventsSent = new AtomicInteger();
    private static final Set<String> seenFingerprints = ConcurrentHashMap.newKeySet();
    private static SentryLogAppender appender;

    private NeroQuestsTelemetry() {
    }

    /**
     * Called once per loader at bootstrap. Starts reporting iff the player has not opted out
     * ({@code telemetryEnabled=true}, the default). Dev (IDE) runs ALSO report — so the developer can
     * test error reporting end to end — but they are tagged {@code environment=development} /
     * {@code runtime=development} so they are trivially filtered out of production metrics. Set
     * {@code telemetryEnabled=false} to silence everything (incl. dev).
     */
    public static void init() {
        if (!NeroQuestsConfig.isTelemetryEnabled()) {
            return;
        }
        start();
    }

    /**
     * Fires a single synthetic Sentry event (a NeroQuests-originated {@link IllegalStateException}) to
     * confirm end-to-end reporting on a real (production) jar. The exception originates in NeroQuests
     * code so it passes the package-only {@code beforeSend} filter; the per-session de-dup means repeat
     * calls in one session collapse to one event (restart to test again).
     *
     * @return {@code true} if telemetry is active and the event was dispatched; {@code false} if it was
     *         skipped because telemetry is opted out.
     */
    public static boolean sendTestEvent(String origin) {
        if (!active) {
            return false;
        }
        capture(new IllegalStateException(
                "NeroQuests Sentry test (" + origin + ") — synthetic event, safe to ignore"));
        return true;
    }

    private static synchronized void start() {
        if (active) {
            return;
        }
        String modVersion = Services.PLATFORM.getModVersion();
        boolean dev = Services.PLATFORM.isDevelopmentEnvironment();
        // AUD-10: a client and scopes of our own. Sentry.init would install a process-wide hub that
        // Core (or any other mod) would overwrite — or that would overwrite theirs. Integrations (the
        // uncaught-exception handler, the shutdown hook) are only registered by Sentry.init, so none
        // are installed here; external configuration (sentry.properties, SENTRY_* env) is not read.
        SentryOptions options = new SentryOptions();
        options.setDsn(DSN);
        options.setRelease("neroquests@" + modVersion);
        // Dev/IDE runs report under a dedicated environment so they never mix with real releases.
        options.setEnvironment(dev ? "development" : environmentOf(modVersion));
        // POPIA/GDPR: never store the sender's IP address or identity.
        options.setSendDefaultPii(false);
        // The machine's hostname is identifying; never attach it.
        options.setAttachServerName(false);
        options.setEnableUncaughtExceptionHandler(false);
        // Release health: the session lifecycle is managed manually (started below, ended on JVM
        // shutdown). The session id is random per launch and NOT linked across sessions; no user/IP.
        options.setEnableAutoSessionTracking(false);
        // No breadcrumbs (nothing records them, and none may ride along) and no performance tracing.
        options.setBeforeBreadcrumb((breadcrumb, hint) -> null);
        options.setBeforeSend((event, hint) -> filterAndScrub(event));
        SentryClient client = new SentryClient(options);
        Scope global = new Scope(options);
        global.bindClient(client);
        IScopes own = new Scopes(new Scope(options), new Scope(options), global, "neroquests");
        configure(global, dev);
        // Manual release-health session for this play session; closed on a clean JVM shutdown.
        own.startSession();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                own.endSession();
                own.flush(2000L);
            } catch (RuntimeException ignored) {
                // best-effort flush on shutdown
            }
        }, "neroquests-sentry-shutdown"));
        scopes = own;
        if (appender == null) {
            appender = new SentryLogAppender();
            appender.start();
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(appender);
        }
        active = true;
        NeroQuestsCommon.LOGGER.info(
                "[NeroQuests] Telemetry enabled (anonymous error reports, EU servers; opt out via "
                        + "telemetryEnabled=false in config/neroquests.properties).");
    }

    /** Tags and contexts on NeroQuests' own global scope (applies to every event of this client only). */
    private static void configure(Scope scope, boolean dev) {
        scope.setTag("loader", Services.PLATFORM.getPlatformName().toLowerCase(Locale.ROOT));
        scope.setTag("dist", Services.PLATFORM.isClient() ? "client" : "dedicated_server");
        scope.setTag("runtime", dev ? "development" : "production");
        scope.setTag("mc_version", minecraftVersion());
        // Config values — surfaces crashes that only reproduce at non-default settings. These are
        // server/pack settings, never player data.
        scope.setTag("cfg_gate_writes",
                Boolean.toString(NeroQuestsConfig.GATE_WRITES_ENABLED.get()));
        scope.setTag("cfg_missing_mod_policy",
                String.valueOf(NeroQuestsConfig.MISSING_MOD_OBJECTIVE_POLICY.get()));
        scope.setTag("cfg_retention_days",
                Integer.toString(NeroQuestsConfig.RETENTION_DAYS.get()));
        // Loaded-mod list (public manifest ids + versions only) for mod-conflict triage.
        try {
            List<String> mods = Services.PLATFORM.getLoadedModIds();
            if (mods != null && !mods.isEmpty()) {
                if (mods.size() > MAX_MODS_REPORTED) {
                    mods = mods.subList(0, MAX_MODS_REPORTED);
                }
                scope.setTag("mod_count", Integer.toString(mods.size()));
                java.util.Map<String, Object> modContext = new java.util.HashMap<>();
                modContext.put("count", mods.size());
                modContext.put("ids", mods);
                scope.setContexts("loaded_mods", modContext);
            }
        } catch (RuntimeException | LinkageError e) {
            // Mod list not available this early — skip it; the rest of the report is unaffected.
        }
    }

    /**
     * The running Minecraft version (e.g. "26.1.2"), read from the loader's mod list (where "minecraft"
     * is always present) so we stay off version-specific vanilla version APIs. "unknown" if unavailable.
     */
    private static String minecraftVersion() {
        try {
            for (String mod : Services.PLATFORM.getLoadedModIds()) {
                if (mod.startsWith("minecraft ")) {
                    return mod.substring("minecraft ".length());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // fall through to unknown
        }
        return "unknown";
    }

    /** Maps the mod version's release channel to a Sentry environment. */
    private static String environmentOf(String version) {
        String v = version.toLowerCase(Locale.ROOT);
        if (v.contains("-alpha")) {
            return "alpha";
        }
        if (v.contains("-beta")) {
            return "beta";
        }
        return "production";
    }

    static boolean isActive() {
        return active;
    }

    /** True if any frame of the throwable (or its causes/suppressed) is NeroQuests code. */
    static boolean touchesNeroQuests(Throwable t) {
        int depth = 0;
        while (t != null && depth++ < 16) {
            for (StackTraceElement el : t.getStackTrace()) {
                if (el.getClassName().startsWith(PACKAGE_MARKER)) {
                    return true;
                }
            }
            for (Throwable s : t.getSuppressed()) {
                if (touchesNeroQuests(s)) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /** Capture an exception already known to touch NeroQuests code. */
    static void capture(Throwable t) {
        IScopes own = scopes;
        if (!active || own == null || t == null) {
            return;
        }
        own.captureException(t, new Hint());
    }

    /** Capture a handled exception if it is still clearly from NeroQuests code. */
    public static void captureHandledException(Throwable t) {
        if (t != null && touchesNeroQuests(t)) {
            capture(t);
        }
    }

    /** Capture a handled exception with a non-identifying source label for triage. */
    public static void captureHandledException(Throwable t, String source, String operation) {
        IScopes own = scopes;
        if (!active || own == null || t == null || !touchesNeroQuests(t)) {
            return;
        }
        // Fixed, non-identifying labels chosen by the call site (e.g. "command" + "/neroquests list").
        own.captureException(t, new Hint(), scope -> {
            scope.setTag("handled", "true");
            scope.setTag("source", source);
            scope.setTag("operation", operation);
        });
    }

    /**
     * Report that a FATAL log line named NeroQuests without a throwable. Its text is never sent
     * (AUD-10): the event carries a fixed token only, so it de-duplicates to one per session.
     */
    static void captureWithheldLogLine() {
        IScopes own = scopes;
        if (!active || own == null) {
            return;
        }
        SentryEvent event = new SentryEvent();
        event.setLevel(SentryLevel.FATAL);
        Message msg = new Message();
        msg.setFormatted(WITHHELD_LOG_LINE);
        event.setMessage(msg);
        own.captureEvent(event, new Hint());
    }

    /**
     * The single privacy/noise gate every outgoing event passes through: keep only NeroQuests-related
     * events, de-duplicate, rate-limit, and scrub PII. Returning {@code null} drops the event.
     */
    private static SentryEvent filterAndScrub(SentryEvent event) {
        if (!isNeroQuestsRelated(event)) {
            return null;
        }
        String fingerprint = fingerprintOf(event);
        if (!seenFingerprints.add(fingerprint)) {
            return null; // already reported this session
        }
        if (eventsSent.incrementAndGet() > MAX_EVENTS_PER_SESSION) {
            return null;
        }
        // POPIA/GDPR scrubbing: no user identity, no hostname, no breadcrumbs, no free text.
        event.setUser(null);
        event.setServerName(null);
        event.setBreadcrumbs(null);
        event.setExtras(null);
        List<SentryException> scrubExceptions = event.getExceptions();
        if (scrubExceptions != null) {
            for (SentryException ex : scrubExceptions) {
                // AUD-10: an exception message can carry anything (a player name, a path, a quest
                // title); only its type and frames are sent.
                ex.setValue(null);
                SentryStackTrace st = ex.getStacktrace();
                List<SentryStackFrame> frames = st == null ? null : st.getFrames();
                if (frames != null) {
                    for (SentryStackFrame frame : frames) {
                        frame.setAbsPath(null);
                    }
                }
            }
        }
        if (event.getMessage() != null) {
            // Only our own fixed token may travel as a message; anything else is replaced by it.
            Message withheld = new Message();
            withheld.setFormatted(WITHHELD_LOG_LINE);
            event.setMessage(withheld);
        }
        event.setLogger(null);
        return event;
    }

    private static boolean isNeroQuestsRelated(SentryEvent event) {
        Throwable t = event.getThrowable();
        if (t != null && touchesNeroQuests(t)) {
            return true;
        }
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            for (SentryException ex : exceptions) {
                SentryStackTrace st = ex.getStacktrace();
                List<SentryStackFrame> frames = st == null ? null : st.getFrames();
                if (frames == null) {
                    continue;
                }
                for (SentryStackFrame frame : frames) {
                    String module = frame.getModule();
                    if (module != null && module.startsWith(PACKAGE_MARKER)) {
                        return true;
                    }
                }
            }
        }
        Message message = event.getMessage();
        String formatted = message == null ? null : message.getFormatted();
        return formatted != null && formatted.contains(PACKAGE_MARKER);
    }

    private static String fingerprintOf(SentryEvent event) {
        StringBuilder sb = new StringBuilder();
        List<SentryException> exceptions = event.getExceptions();
        Message message = event.getMessage();
        if (exceptions != null) {
            for (SentryException ex : exceptions) {
                sb.append(ex.getType()).append('|');
                SentryStackTrace st = ex.getStacktrace();
                List<SentryStackFrame> frames = st == null ? null : st.getFrames();
                if (frames != null) {
                    for (SentryStackFrame frame : frames) {
                        String module = frame.getModule();
                        if (module != null && module.startsWith(PACKAGE_MARKER)) {
                            sb.append(module).append(':').append(frame.getLineno()).append('|');
                        }
                    }
                }
            }
        } else if (message != null) {
            String formatted = message.getFormatted();
            if (formatted != null) {
                sb.append(formatted, 0, Math.min(200, formatted.length()));
            }
        }
        return sb.toString();
    }
}
