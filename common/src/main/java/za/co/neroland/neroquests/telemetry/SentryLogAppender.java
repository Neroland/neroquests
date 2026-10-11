package za.co.neroland.neroquests.telemetry;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Log4j2 appender that feeds {@link NeroQuestsTelemetry}. Minecraft routes essentially every failure
 * through log4j — handled errors, event-bus listener exceptions, and the crash report itself — so
 * listening on the root logger catches NeroQuests failures without mixins. Filtering (NeroQuests-only),
 * de-dup, rate-limiting and PII scrubbing all happen in {@link NeroQuestsTelemetry}; this only selects
 * candidate log events. Log text never leaves the game: a throwable is sent as type + frames, a FATAL
 * line without one as a fixed token.
 */
final class SentryLogAppender extends AbstractAppender {

    SentryLogAppender() {
        super("NeroQuestsSentry", null, null, false, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        if (!NeroQuestsTelemetry.isActive()) {
            return;
        }
        Level level = event.getLevel();
        if (!level.isMoreSpecificThan(Level.ERROR)) {
            return;
        }
        Throwable thrown = event.getThrown();
        if (thrown != null) {
            if (NeroQuestsTelemetry.touchesNeroQuests(thrown)) {
                NeroQuestsTelemetry.capture(thrown);
            }
        } else if (level == Level.FATAL) {
            String message = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
            if (message != null && message.contains("za.co.neroland.neroquests")) {
                // AUD-10: the line's text is never sent — only that such a line occurred.
                NeroQuestsTelemetry.captureWithheldLogLine();
            }
        }
    }
}
