# infra

AWS CDK v2 (TypeScript) for Soundcheck on AWS. Everything synthesises offline: no
lookups, no credentials. cdk-nag `AwsSolutionsChecks` runs on every synth as a validation plugin, so an
unacknowledged finding fails it; every acknowledgement has a reason in the code (`lib/nag.ts`).

**Region: eu-north-1, fixed** by choice (`lib/config.ts`), so the region is not taken from the CLI profile
(`soundcheck`, an IAM Identity Center sign-in).

## Stacks, in deploy order

| Stack | What | File |
|---|---|---|
| `SoundcheckNetwork` | VPC over eu-north-1a/b: public subnets (Fargate tasks with public IPs, no NAT) and isolated subnets (ALB, RDS); S3 gateway endpoint; security groups (ALB ← CloudFront prefix list :80, API ← ALB :8080, DB ← tasks :5432) | `lib/network-stack.ts` |
| `SoundcheckData` | RDS PostgreSQL 17.5 (t4g.micro, 20 GB gp3, single-AZ, encrypted, private), the transcode queue and its DLQ, ECR `soundcheck-api`/`-worker`/`-lab` | `lib/data-stack.ts` |
| `SoundcheckEdge` | CloudFront (web bucket by default, `/api/*` through a VPC origin to the internal ALB, `/media/*` from the media bucket), the ALB and its listener, both buckets | `lib/edge-stack.ts`, `lib/functions/spa-rewrite.js` |
| `SoundcheckCompute` | ECS cluster; the API service (`api,scheduler`) behind the ALB; workers on Spot scaled on the queue; the one-off job task (`cli`) | `lib/compute-stack.ts`, `lib/task-env.ts` |
| `SoundcheckObservability` | SNS topic, eight alarms on AWS's own ALB, RDS and SQS metrics (Container Insights is off for cost), dashboard | `lib/observability-stack.ts` |
| `SoundcheckWeb` (only with `-c webDist=…`) | Copies the web export into the web bucket and invalidates `index.html` | `lib/web-stack.ts` |

`lib/app.ts` builds them (the CLI through `bin/soundcheck.ts`, and the tests), `lib/config.ts` reads the
context, `lib/base.ts` is the shared stack class (tags, fixed AZs).

## Context keys

Defaults are in `cdk.json`; override with `-c key=value`.

| Key | Default | Notes |
|---|---|---|
| `env` | `demo` | names, tags, the app secret `soundcheck/<env>/app` |
| `imageTag` | none | required by Compute (`git rev-parse --short=12 HEAD`); tests and CI pass `ci` |
| `cloudFrontPrefixListId` | `pl-fab65393` | `com.amazonaws.global.cloudfront.origin-facing` in eu-north-1 |
| `webOrigin` | unset → `https://*.cloudfront.net` | media bucket CORS |
| `apiCpu` / `apiMemoryMiB` / `apiMinTasks` / `apiMaxTasks` | 512 / 1024 / 1 / 2 | |
| `workerMinTasks` / `workerMaxTasks` | 0 / 2 | raise after `make aws-check` reads the Fargate quotas |
| `alarmEmail` | unset | never committed; `aws-deploy` passes it |
| `webDist` | unset → no Web stack | a directory holding `index.html` |
| `pushEnabled` / `otlpEnabled` | `false` / `false` | map `PUSH_CREDENTIALS_JSON` / `OTLP_AUTHORIZATION` from the app secret |
| `otlpEndpoint` | unset | required when `otlpEnabled` |
| `ingestionMode` / `setlistBackfill` | `live` / `true` | |
| `musicbrainzUserAgent` | unset | |

A bad or missing value fails the synth with a message naming the key.

## Offline

```bash
npm install
npm run build      # tsc
npm test           # Jest: every security property is one assertion (test/*.test.ts)
npm run synth      # cdk synth with AWS credentials hidden and a placeholder account
```

`npm run synth` hides any AWS credentials from the CLI on purpose: with credentials around, `cdk synth`
calls STS to find the account even for an app that needs no lookups. On this machine, run the heavy
commands under the shared lock (`flock .localdata/locks/heavy.lock …`).

Never run `cdk deploy`/`destroy`/`bootstrap` directly; `make aws-*`
wraps them.
