package app.soundcheck.shared.config;

import java.util.Map;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Reads {@code soundcheck.roles} and matches when it contains the role named on {@link ConditionalOnRole}. */
final class OnRoleCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnRole.class.getName());
        if (attributes == null) {
            return true;
        }
        String required = (String) attributes.get("value");
        return Roles.parse(context.getEnvironment().getProperty(Roles.PROPERTY)).contains(required);
    }
}
