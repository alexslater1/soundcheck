# backend

Spring Boot 3.5 / Java 21 modular monolith (Spring Modulith) run as process roles `api`, `scheduler`, `worker`.

```bash
./gradlew build            # jOOQ codegen (Testcontainers + Flyway) → compile → tests (needs Docker)
./gradlew spotlessApply    # format (palantir-java-format)
make api                   # from the repo root: bootRun with roles api,scheduler against `make up`
```

Layout: `app.soundcheck.<module>` with public API in the module root and implementation in `<module>.internal`;
`shared` is the open kernel (ids, clock, problem details, security, generated jOOQ classes under `shared.jooq`, generated into `build/generated-src/jooq`, never committed).
CORS: only the Expo dev origin `http://localhost:8081` is allowed; via the edge on :8088 no CORS is involved.
Migrations: `src/main/resources/db/migration/V{NNN}__{module}_{what}.sql` (Flyway, forward-only).
Module docs (diagrams, canvases) are written by `ModularityTests` to `build/spring-modulith-docs/`.
