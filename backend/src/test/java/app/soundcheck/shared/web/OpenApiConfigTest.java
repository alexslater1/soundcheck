package app.soundcheck.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Properties are required unless nullable — in both the OpenAPI 3.0 and 3.1 spellings. */
class OpenApiConfigTest {

    @Test
    void marksNonNullablePropertiesRequiredInBothSpellings() {
        Schema<?> nullable30 = new StringSchema().nullable(true);
        Schema<?> nullable31 = new StringSchema();
        nullable31.setTypes(Set.of("string", "null"));
        Schema<?> plain = new StringSchema();

        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("a", plain);
        schema.addProperty("b", nullable30);
        schema.addProperty("c", nullable31);

        OpenApiConfig.markRequired(schema);

        assertThat(schema.getRequired()).containsExactly("a");
    }

    @Test
    void leavesSchemasWithoutPropertiesAlone() {
        ObjectSchema schema = new ObjectSchema();
        OpenApiConfig.markRequired(schema);
        assertThat(schema.getRequired()).isNull();
    }
}
