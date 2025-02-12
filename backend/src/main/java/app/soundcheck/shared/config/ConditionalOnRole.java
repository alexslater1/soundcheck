package app.soundcheck.shared.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Registers the annotated bean only when the process runs the given role. Roles are plain
 * configuration: {@code soundcheck.roles=api,scheduler} on the API tasks, {@code worker} on the
 * transcode workers, all three locally.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnRoleCondition.class)
public @interface ConditionalOnRole {

    /** Role name: {@code api}, {@code scheduler} or {@code worker}. */
    String value();
}
