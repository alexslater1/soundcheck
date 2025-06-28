package app.soundcheck.shared.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Connects the Logback {@code OTEL} appender ({@link OpenTelemetryLogAppender}) to the application's
 * OpenTelemetry, whose logger provider Spring Boot exports over OTLP. Until this runs
 * the appender buffers events, so start-up logs are sent as well. Nothing to connect in a test slice
 * without OpenTelemetry.
 */
@Component
public class OpenTelemetryLogging implements InitializingBean {

    private final ObjectProvider<OpenTelemetry> openTelemetry;

    OpenTelemetryLogging(ObjectProvider<OpenTelemetry> openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        openTelemetry.ifAvailable(OpenTelemetryAppender::install);
    }
}
