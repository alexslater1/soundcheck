# ops
Local-stack configuration and operational scripts.
- `caddy/Caddyfile` — local edge mirroring CloudFront: the same path routing on `:8088`, read-only `/media/*`, and the security headers CloudFront's response headers policy will add (nosniff, no framing, referrer policy).
- `localstack/init/ready.d/` — on start: `10-resources.sh` creates the media bucket and transcode queues; `20-restore-media.sh` copies back the clips `make down` / `make media-snapshot` saved to the `media-snapshot` volume (LocalStack's free edition keeps nothing across restarts).
- `grafana/` — dashboard provisioning for grafana/otel-lgtm; `dashboards/` holds no dashboard yet.
- `aws/` — deploying and operating on AWS from the laptop, one script per `make aws-*` target, sharing `lib.sh` (profile, region, stack outputs, confirmations, dry runs); `test/` holds the offline dry-run test with recorded outputs (README "Running on AWS").
- `scripts/` — see `scripts/README.md`.
