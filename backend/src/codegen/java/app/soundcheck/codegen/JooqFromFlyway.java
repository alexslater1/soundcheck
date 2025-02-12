package app.soundcheck.codegen;

import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.SchemaMappingType;
import org.jooq.meta.jaxb.Target;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Build-time tool: start a throwaway PostGIS container, apply the Flyway migrations and
 * generate jOOQ classes from the resulting schema. Run by the Gradle task {@code generateJooq}.
 *
 * <p>Arguments: {@code <migrationsDir> <outputDir> <package>}.
 */
public final class JooqFromFlyway {

    /** Same image as compose.yaml so generated code matches what runs locally and in tests. */
    static final String POSTGIS_IMAGE = "postgis/postgis:17-3.5";

    /** One schema per module. */
    static final List<String> SCHEMAS = List.of(
            "shared",
            "identity",
            "social",
            "catalog",
            "ingestion",
            "media",
            "content",
            "signals",
            "feed",
            "notify",
            "analytics");

    private JooqFromFlyway() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: JooqFromFlyway <migrationsDir> <outputDir> <package>");
        }
        Path migrations = Path.of(args[0]);
        Path output = Path.of(args[1]);
        String packageName = args[2];

        try (PostgreSQLContainer<?> postgres =
                new PostgreSQLContainer<>(DockerImageName.parse(POSTGIS_IMAGE).asCompatibleSubstituteFor("postgres"))) {
            postgres.start();

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("filesystem:" + migrations.toAbsolutePath())
                    .load()
                    .migrate();

            GenerationTool.generate(new Configuration()
                    .withJdbc(new Jdbc()
                            .withDriver("org.postgresql.Driver")
                            .withUrl(postgres.getJdbcUrl())
                            .withUser(postgres.getUsername())
                            .withPassword(postgres.getPassword()))
                    .withGenerator(new Generator()
                            .withDatabase(new Database()
                                    .withName("org.jooq.meta.postgres.PostgresDatabase")
                                    .withSchemata(SCHEMAS.stream()
                                            .map(s -> new SchemaMappingType().withInputSchema(s))
                                            .toList())
                                    .withExcludes("flyway_schema_history|spatial_ref_sys|event_publication_archive")
                                    .withIncludeRoutines(false)
                                    .withIncludeSequences(true))
                            .withGenerate(new Generate()
                                    .withRecords(true)
                                    .withPojos(false)
                                    .withFluentSetters(true)
                                    .withJavaTimeTypes(true)
                                    .withDeprecated(false)
                                    .withGeneratedAnnotation(false))
                            .withTarget(new Target()
                                    .withPackageName(packageName)
                                    .withDirectory(output.toAbsolutePath().toString())
                                    .withClean(true))));
        }
    }
}
