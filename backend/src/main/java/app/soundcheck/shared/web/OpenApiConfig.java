package app.soundcheck.shared.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.io.UncheckedIOException;
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
 * {@code @NotBlank} / {@code @NotEmpty} properties without a {@code @Size} minimum have a minimum length
 * (items) of 1, and an untyped value ({@code Object}) is the empty schema.
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
                implicitObjects(schema);
            });
        };
    }

    @Bean
    PropertyCustomizer notBlankMinimum() {
        return (property, type) -> {
            if (type.getCtxAnnotations() == null) {
                return property;
            }
            boolean notBlank = Arrays.stream(type.getCtxAnnotations()).anyMatch(NotBlank.class::isInstance);
            boolean notEmpty = Arrays.stream(type.getCtxAnnotations()).anyMatch(NotEmpty.class::isInstance);
            if (isType(property, "string") && (notBlank || notEmpty) && property.getMinLength() == null) {
                property.setMinLength(1);
            } else if (isType(property, "array") && notEmpty && property.getMinItems() == null) {
                property.setMinItems(1);
            } else if (isType(property, "object") && notEmpty && property.getMinProperties() == null) {
                property.setMinProperties(1);
            }
            return property;
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

    /** A value schema that is only {@code type: object} (an {@code Object}) becomes the empty schema. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void implicitObjects(Schema<?> schema) {
        if (schema.getProperties() != null) {
            schema.getProperties().replaceAll((name, property) -> bareObject(property) ? new Schema<>() : property);
            schema.getProperties().values().forEach(OpenApiConfig::implicitObjects);
        }
        if (schema.getItems() != null) {
            if (bareObject(schema.getItems())) {
                ((Schema) schema).setItems(new Schema<>());
            } else {
                implicitObjects(schema.getItems());
            }
        }
        if (schema.getAdditionalProperties() instanceof Schema<?> values) {
            if (bareObject(values)) {
                schema.setAdditionalProperties(new Schema<>());
            } else {
                implicitObjects(values);
            }
        }
    }

    static boolean bareObject(Schema<?> schema) {
        try {
            return Json31.mapper().writeValueAsString(schema).equals("{\"type\":\"object\"}");
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean isType(Schema<?> property, String type) {
        return type.equals(property.getType())
                || (property.getTypes() != null && property.getTypes().contains(type));
    }

    static boolean isNullable(Schema<?> property) {
        if (Boolean.TRUE.equals(property.getNullable())) {
            return true;
        }
        return property.getTypes() != null && property.getTypes().contains("null");
    }
}
