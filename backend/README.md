# backend

Spring Boot 3.4 / Java 21 modular monolith (Spring Modulith) run as process roles `api`, `scheduler`, `worker`.

```bash
./gradlew build            # jOOQ codegen (Testcontainers + Flyway) → compile → tests (needs Docker)
./gradlew spotlessApply    # format (palantir-java-format)
make api                   # from the repo root: bootRun with roles api,scheduler against `make up`
```

Layout: `app.soundcheck.<module>` with public API in the module root and implementation in `<module>.internal`;
`shared` is the open kernel (ids, clock, problem details, security, generated jOOQ classes under `shared.jooq`).
Migrations: `src/main/resources/db/migration/V{NNN}__{module}_{what}.sql` (Flyway, forward-only).
Module docs (diagrams, canvases) are written by `ModularityTests` to `build/spring-modulith-docs/`.
