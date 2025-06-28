package app.soundcheck.shared.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Sends every log event to OpenTelemetry as well as to the console: Spring
 * Boot exports them over OTLP ({@code management.otlp.logging}), to Loki locally, each
 * with the trace and span it happened in.
 *
 * <p>Boot sets Logback up itself (ECS JSON on the console, or plain text in tests); this adds one
 * appender to the root logger straight after, instead of replacing Boot's set-up with a
 * {@code logback-spring.xml} that would have to repeat its choices. Registered in
 * {@code META-INF/spring.factories}, so it runs for the application and for every test context, before
 * any bean exists. The appender buffers events until {@link OpenTelemetryLogging} connects it to the
 * application's OpenTelemetry, so start-up logs are sent too.
 */
public class OpenTelemetryLogAppender implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    static final String NAME = "OTEL";

    @Override
    public int getOrder() {
        return LoggingApplicationListener.DEFAULT_ORDER + 1;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(NAME) != null) {
            return;
        }
        OpenTelemetryAppender appender = new OpenTelemetryAppender();
        appender.setName(NAME);
        appender.setContext(context);
        // Thread name and id are cheap; the code location (class, line) is left off: it walks the stack.
        appender.setCaptureExperimentalAttributes(true);
        appender.addFilter(new NotOpenTelemetryItself());
        appender.start();
        root.addAppender(appender);
    }

    /**
     * OpenTelemetry's own messages (an export that failed) stay on the console: sent back into OTLP they
     * would queue another failing export.
     */
    static final class NotOpenTelemetryItself extends Filter<ILoggingEvent> {

        NotOpenTelemetryItself() {
            start();
        }

        @Override
        public FilterReply decide(ILoggingEvent event) {
            return event.getLoggerName().startsWith("io.opentelemetry") ? FilterReply.DENY : FilterReply.NEUTRAL;
        }
    }
}
