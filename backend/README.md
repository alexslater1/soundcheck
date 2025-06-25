# backend

Spring Boot 3.5 / Java 21 modular monolith (Spring Modulith) run as process roles `api`, `scheduler`, `worker`. The one-shot tools (`make ingest`, `venue-preview`, `venue-coverage`, `record-fixtures`) start it with `SOUNDCHECK_ROLES=cli` (none of the three) and `--soundcheck.ingestion.cli=<command>` (`IngestionCli`), which runs the command and exits.

```bash
./gradlew build            # jOOQ codegen (Testcontainers + Flyway) → compile → tests (needs Docker)
./gradlew spotlessApply    # format (palantir-java-format)
make api                   # from the repo root: bootRun with roles api,scheduler against `make up`
make worker                # role worker on :8090 (transcodes; needs local FFmpeg)
make ingest                # on :8099: one ingestion run, then exit
```

Layout: `app.soundcheck.<module>` with public API in the module root and implementation in `<module>.internal`.
Eleven modules: `identity`, `catalog`, `ingestion`, `media`, `content`, `social`, `notifications`, `signals`, `feed`, `analytics`, and
`shared`, the open kernel (ids, clock, Problem Details with a `traceId`, security, rate limits, generated jOOQ classes under `shared.jooq`, generated into `build/generated-src/jooq`, never committed). Each module's `package-info.java` lists the modules it may depend on; `ModularityTests` fails on any other dependency and writes the module docs (diagrams, canvases) to `build/spring-modulith-docs/`.
CORS: `soundcheck.cors.allowed-origins` lists the Expo dev server (`http://localhost:8081`) and the local edge (`http://localhost:8088`); it is empty under the `aws` profile, where everything is same-origin behind CloudFront.
Migrations: `src/main/resources/db/migration/V{NNN}__{module}_{what}.sql` (Flyway, forward-only, no out-of-order: a lower version merged after a higher one was applied stops start-up).
Venue websites: the registry `src/main/resources/venues/london.json` says how each venue's page is read (adapter, selectors, catalog venue, sibling rooms); `soundcheck.ingestion.venues.enabled`/`sites` switch them on (off by default, every registry venue on in the `local` profile).
