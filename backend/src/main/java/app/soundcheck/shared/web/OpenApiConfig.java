package app.soundcheck.shared.web;

import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.constraints.Email;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI conventions. Response and request records are Java records whose components are
 * non-null unless annotated {@code @Nullable} / {@code @Schema(nullable = true)}; springdoc does not know
 * that, so every property that is not nullable is marked {@code required}. The generated TypeScript then
 * has {@code status: string} rather than {@code status?: string}, and optional fields are exactly the ones
 * the API may omit. OpenAPI 3.1 expresses nullability as {@code type: [..., "null"]}, 3.0 as
 * {@code nullable: true}; both are honoured, and springdoc's {@code nullable: true} is written the 3.1 way
 * (a 3.1 document has no {@code nullable}). {@code @Email} properties have {@code format: email}.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenApiCustomizer requiredUnlessNullable() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().values().forEach(schema -> {
                markRequired(schema);
                nullAsType(schema);
            });
        };
    }

    @Bean
    PropertyCustomizer emailFormat() {
        return (property, type) -> {
            if (type.getCtxAnnotations() != null
                    && Arrays.stream(type.getCtxAnnotations()).anyMatch(Email.class::isInstance)) {
                property.setFormat("email");
            }
            return property;
        };
    }

    @SuppressWarnings("rawtypes")
    static void markRequired(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        List<String> required = new ArrayList<>();
        properties.forEach((name, property) -> {
            if (!isNullable(property)) {
                required.add(name);
            }
        });
        schema.setRequired(required.isEmpty() ? null : required);
    }

    /** {@code type: [string, "null"]}, or {@code type: "null"} beside a {@code $ref}, for {@code nullable: true}. */
    @SuppressWarnings("rawtypes")
    static void nullAsType(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        properties.values().forEach(property -> {
            if (!Boolean.TRUE.equals(property.getNullable())) {
                return;
            }
            property.setNullable(null);
            Set<String> types = new LinkedHashSet<>();
            if (property.get$ref() == null) {
                if (property.getTypes() != null) {
                    types.addAll(property.getTypes());
                } else if (property.getType() != null) {
                    types.add(property.getType());
                }
            }
            types.add("null");
            property.setTypes(types);
        });
    }

    static boolean isNullable(Schema<?> property) {
        if (Boolean.TRUE.equals(property.getNullable())) {
            return true;
        }
        return property.getTypes() != null && property.getTypes().contains("null");
    }
}
