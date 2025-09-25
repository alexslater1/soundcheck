#!/usr/bin/env bash
# Offline test of the deploy scripts. No credentials, no AWS calls: every script runs
# with DRY_RUN=1 YES=1 against recorded outputs (ops/aws/test/outputs.json), and aws, npx, docker, make,
# uv and curl on PATH are stubs that record any call, so the test fails if one is really run. It checks
# the printed command sequence (the stack order, the context flags, pause/resume/destroy/ingest), that
# no secret value appears anywhere, the confirmations, and how the app secret is built and written
# (with a stub AWS CLI that keeps what it is sent).
#   ops/aws/test/dry-run.sh        (make aws-test runs it after shellcheck)
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPTS=$(dirname "$HERE")
ROOT=$(cd "$SCRIPTS/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
passed=0

fail() {
  echo "FAIL: $*" >&2
  exit 1
}
pass() {
  passed=$((passed + 1))
  echo "ok   $*"
}
contains() { grep -qF -- "$2" "$1" || fail "$3: '$2' not in the output ($1)"; }
lacks() { ! grep -qF -- "$2" "$1" || fail "$3: '$2' must not be in the output"; }
# The lines matching each pattern appear in this order.
in_order() {
  local file=$1 what=$2 last=0 line pattern
  shift 2
  for pattern in "$@"; do
    line=$(grep -nF -- "$pattern" "$file" | awk -F: -v after="$last" '$1 > after {print $1; exit}')
    [[ -n $line ]] || fail "$what: '$pattern' missing after line $last of $file"
    last=$line
  done
}

# ── stubs: anything that would reach AWS, build or upload ────────────────────────────────────────
mkdir -p "$tmp/bin" "$tmp/secrets" "$tmp/local"
for tool in aws npx docker make uv curl; do
  printf '#!/bin/sh\necho "%s $*" >>"%s/called"\nexit 97\n' "$tool" "$tmp" >"$tmp/bin/$tool"
  chmod +x "$tmp/bin/$tool"
done
touch "$tmp/called"

# ── a full set of settings, every secret a recognisable sentinel ─────────────────────────────────
export PATH="$tmp/bin:$PATH"
export SECRETS_DIR="$tmp/secrets" AWS_LOCAL="$tmp/local" SOUNDCHECK_ENV=demo
export TICKETMASTER_API_KEY=tm-SENTINEL-1111 LASTFM_API_KEY=lfm-SENTINEL-2222 SETLISTFM_API_KEY=sfm-SENTINEL-3333
export SOUNDCHECK_ADMIN_EMAIL=admin@local.test SOUNDCHECK_ADMIN_PASSWORD=local-SENTINEL-4444
export GRAFANA_OTLP_ENDPOINT=https://otlp-gateway.example.test/otlp GRAFANA_OTLP_AUTHORIZATION='"Basic SENTINEL-5555"'
export AWS_ALARM_EMAIL=alerts@example.test MUSICBRAINZ_USER_AGENT='"Soundcheck/0.1 (alerts@example.test)"'
export PUSH_ENABLED=true PUSH_CREDENTIALS_FILE=""
export AWS_ACCESS_KEY_ID=key-SENTINEL-7777 AWS_SECRET_ACCESS_KEY=secret-SENTINEL-8888
unset AWS_ADMIN_EMAIL SEED_PASSWORD DB_HOST DELETE_MEDIA TARGET
echo '{"type": "service_account", "private_key": "fcm-SENTINEL-6666"}' >"$tmp/secrets/fcm-service-account.json"
TAG=$(git -C "$ROOT" rev-parse --short=12 HEAD)
URL=https://d1example2fixture.cloudfront.net

dry() { # <name> <script> [args]: a dry run against the fixture outputs; output in $tmp/<name>.out
  local name=$1
  shift
  DRY_RUN=1 YES=1 AWS_OUTPUTS_FILE="${OUTPUTS:-$HERE/outputs.json}" "$@" >"$tmp/$name.out" 2>&1 </dev/null ||
    fail "$name exited $? (output: $(tail -5 "$tmp/$name.out"))"
}

# ── aws-deploy, an environment that exists ──────────────────────────────────────────────────────
dry deploy "$SCRIPTS/deploy.sh"
out="$tmp/deploy.out"
[[ $(grep -c '^+ npx cdk deploy' "$out") == 2 ]] || fail "deploy: expected 2 cdk deploys, got $(grep -c '^+ npx cdk deploy' "$out")"
in_order "$out" "deploy order" \
  "== 1/8 aws-check" \
  "+ make -C $ROOT web EXPO_PUBLIC_API_BASE_URL=" \
  "+ npx cdk deploy SoundcheckNetwork SoundcheckData SoundcheckEdge --exclusively" \
  "+ aws secretsmanager create-secret --name soundcheck/demo/app" \
  "+ make -C $ROOT images" \
  "+ docker push --quiet 111122223333.dkr.ecr.eu-north-1.amazonaws.com/soundcheck-api:$TAG" \
  "+ docker push --quiet 111122223333.dkr.ecr.eu-north-1.amazonaws.com/soundcheck-worker:$TAG" \
  "+ npx cdk deploy SoundcheckCompute SoundcheckObservability SoundcheckWeb --exclusively" \
  "+ aws ecs wait services-stable --cluster SoundcheckCompute-Cluster-Fixture --services SoundcheckCompute-ApiService-Fixture" \
  "/ops/scripts/smoke.sh $URL --expect-signed-headers" \
  "/ops/scripts/check-media-delivery.sh" \
  "/ops/aws/edge-check.sh $URL" \
  "Timings"
pass "aws-deploy: steps in order (check, web, network/data/edge, secret, images, compute/observability/web, wait, smoke)"
while read -r line; do
  for flag in "--profile soundcheck" "--require-approval never" "-c env=demo" "-c imageTag=$TAG" \
    "-c webOrigin=$URL" "-c webDist=../app/dist" "-c alarmEmail=alerts@example.test" "-c pushEnabled=true" \
    "-c otlpEnabled=true" "-c otlpEndpoint=https://otlp-gateway.example.test/otlp" \
    "-c 'musicbrainzUserAgent=Soundcheck/0.1 (alerts@example.test)'"; do
    [[ $line == *"$flag"* ]] || fail "deploy: '$flag' missing from: $line"
  done
done < <(grep '^+ npx cdk deploy' "$out")
pass "aws-deploy: every cdk deploy has the same context (profile, approval, env, imageTag, webOrigin, webDist, flags from .env)"
lacks "$out" "soundcheck-lab" "deploy without PUSH_LAB"
pass "aws-deploy: the lab image is not pushed"

# ── aws-deploy, first time: Edge twice ───────────────────────────────────────────────────────────
echo '{}' >"$tmp/fresh.json"
OUTPUTS="$tmp/fresh.json" dry fresh "$SCRIPTS/deploy.sh"
out="$tmp/fresh.out"
[[ $(grep -c '^+ npx cdk deploy' "$out") == 3 ]] || fail "first deploy: expected 3 cdk deploys"
first=$(grep -m1 '^+ npx cdk deploy' "$out")
[[ $first == *"SoundcheckNetwork SoundcheckData SoundcheckEdge"* && $first != *webOrigin* ]] ||
  fail "first deploy: the first pass must not set webOrigin: $first"
in_order "$out" "first deploy" \
  "+ npx cdk deploy SoundcheckNetwork SoundcheckData SoundcheckEdge" \
  "first deploy: Edge once more" \
  "+ npx cdk deploy SoundcheckEdge --exclusively" \
  "+ npx cdk deploy SoundcheckCompute SoundcheckObservability SoundcheckWeb"
second=$(grep '^+ npx cdk deploy SoundcheckEdge ' "$out")
[[ $second == *"webOrigin=<SoundcheckEdge.PublicBaseUrl>"* ]] || fail "first deploy: second Edge pass without webOrigin"
pass "aws-deploy, first time: Edge deployed, then again with webOrigin from its own output"

# ── aws-pause / aws-resume ───────────────────────────────────────────────────────────────────────
api_target="service/SoundcheckCompute-Cluster-Fixture/SoundcheckCompute-ApiService-Fixture"
worker_target="service/SoundcheckCompute-Cluster-Fixture/SoundcheckCompute-WorkerService-Fixture"
dry pause "$SCRIPTS/pause.sh"
in_order "$tmp/pause.out" "pause" \
  "+ aws application-autoscaling register-scalable-target --service-namespace ecs --scalable-dimension ecs:service:DesiredCount --resource-id $api_target --min-capacity 0 --max-capacity 0" \
  "+ aws ecs update-service --cluster SoundcheckCompute-Cluster-Fixture --service SoundcheckCompute-ApiService-Fixture --desired-count 0" \
  "--resource-id $worker_target --min-capacity 0 --max-capacity 0" \
  "--service SoundcheckCompute-WorkerService-Fixture --desired-count 0" \
  "+ aws ecs wait services-stable" \
  "+ aws rds stop-db-instance --db-instance-identifier soundcheck-demo-db" \
  "about \$22 a month" \
  "after 7 days"
pass "aws-pause: scaling limits 0/0 and desired 0 for API and workers, wait, then the database stops"

dry resume "$SCRIPTS/resume.sh"
api_min=$(jq -r '.context.apiMinTasks // 1' "$ROOT/infra/cdk.json")
api_max=$(jq -r '.context.apiMaxTasks // 2' "$ROOT/infra/cdk.json")
worker_min=$(jq -r '.context.workerMinTasks // 0' "$ROOT/infra/cdk.json")
worker_max=$(jq -r '.context.workerMaxTasks // 2' "$ROOT/infra/cdk.json")
in_order "$tmp/resume.out" "resume" \
  "+ aws rds start-db-instance --db-instance-identifier soundcheck-demo-db" \
  "+ aws rds wait db-instance-available --db-instance-identifier soundcheck-demo-db" \
  "--resource-id $api_target --min-capacity $api_min --max-capacity $api_max" \
  "--resource-id $worker_target --min-capacity $worker_min --max-capacity $worker_max" \
  "--service SoundcheckCompute-ApiService-Fixture --desired-count $api_min" \
  "--service SoundcheckCompute-WorkerService-Fixture --desired-count $worker_min" \
  "+ aws ecs wait services-stable --cluster SoundcheckCompute-Cluster-Fixture --services SoundcheckCompute-ApiService-Fixture" \
  "/ops/scripts/smoke.sh $URL"
pass "aws-resume: database first and waited for, limits back to cdk.json's ($api_min-$api_max, $worker_min-$worker_max), then smoke"

# ── aws-ingest / aws-backfill-past-gigs ──────────────────────────────────────────────────────────
dry ingest "$SCRIPTS/ingest.sh"
contains "$tmp/ingest.out" "+ aws ecs run-task --cluster SoundcheckCompute-Cluster-Fixture --task-definition arn:aws:ecs:eu-north-1:111122223333:task-definition/SoundcheckComputeJob:3 --launch-type FARGATE" "ingest"
contains "$tmp/ingest.out" "awsvpcConfiguration={subnets=[subnet-0fixture0000000a,subnet-0fixture0000000b],securityGroups=[sg-0fixture0job0sg0],assignPublicIp=ENABLED}" "ingest network"
contains "$tmp/ingest.out" '{"containerOverrides":[{"name":"job","command":["--soundcheck.ingestion.cli=ingest"]}]}' "ingest command"
pass "aws-ingest: run-task on Fargate, public subnets with a public IP, the Job container's command overridden"
dry backfill "$SCRIPTS/ingest.sh" --backfill
contains "$tmp/backfill.out" '"command":["--soundcheck.ingestion.cli=ingest","--soundcheck.ingestion.cli-sources=SETLISTFM"]' "backfill command"
contains "$tmp/backfill.out" '"environment":[{"name":"INGESTION_MODE","value":"live"},{"name":"INGESTION_SETLISTFM_BACKFILL","value":"true"}]' "backfill env"
pass "aws-backfill-past-gigs: SETLISTFM only, with INGESTION_MODE=live and the backfill flag"

# ── aws-destroy ──────────────────────────────────────────────────────────────────────────────────
dry destroy "$SCRIPTS/destroy.sh"
contains "$tmp/destroy.out" "+ npx cdk destroy --all --force --profile soundcheck -c env=demo -c imageTag=$TAG -c webDist=" "destroy"
lacks "$tmp/destroy.out" "+ aws s3 rb" "destroy with YES=1 alone"
lacks "$tmp/destroy.out" "delete-secret" "destroy with YES=1 alone"
contains "$tmp/destroy.out" "kept" "destroy keeps the clips"
pass "aws-destroy with YES=1: every stack, the clips and the secret kept"
DELETE_MEDIA=1 dry destroy-media "$SCRIPTS/destroy.sh"
in_order "$tmp/destroy-media.out" "destroy with DELETE_MEDIA=1" \
  "+ npx cdk destroy --all --force" \
  "+ aws s3 rb s3://soundcheckedge-mediabucket-fixture --force" \
  "+ aws secretsmanager delete-secret --secret-id soundcheck/demo/app --recovery-window-in-days 7"
pass "aws-destroy with DELETE_MEDIA=1: then the media bucket, and the secret with a 7-day window"

# ── the other targets, dry ───────────────────────────────────────────────────────────────────────
dry status "$SCRIPTS/status.sh"
contains "$tmp/status.out" "? aws ecs describe-services --cluster SoundcheckCompute-Cluster-Fixture" "status"
dry logs "$SCRIPTS/logs.sh" worker
contains "$tmp/logs.out" "? aws logs tail /soundcheck/demo/worker --follow --since 15m" "logs"
dry seed "$SCRIPTS/seed.sh" --no-real
contains "$tmp/seed.out" "+ env SOUNDCHECK_ROOT=$tmp/local/seed-demo SOUNDCHECK_API_URL=$URL uv run soundcheck-lab seed-demo --no-real" "seed"
[[ -L $tmp/local/seed-demo/fixtures ]] || fail "seed: no fixtures link in the AWS seed's own root"
[[ $(stat -c %a "$tmp/secrets/aws/seed-password") == 600 ]] || fail "seed: seed-password not mode 600"
dry apk "$SCRIPTS/apk.sh"
contains "$tmp/apk.out" "APK for $URL" "apk"
contains "$tmp/apk.out" "+ make -C $ROOT apk TARGET= APK_API_URL=$URL APK_APP_ENV=production" "apk"
contains "$tmp/apk.out" "+ env -u APP_ENV npx expo prebuild --platform android --no-install" "apk restores the dev config"
dry bootstrap "$SCRIPTS/bootstrap.sh"
contains "$tmp/bootstrap.out" "+ npx cdk bootstrap 'aws://<account>/eu-north-1' --profile soundcheck" "bootstrap"
dry diff "$SCRIPTS/diff.sh"
contains "$tmp/diff.out" "? npx cdk diff SoundcheckNetwork SoundcheckData SoundcheckEdge SoundcheckCompute SoundcheckObservability" "diff"
dry check "$SCRIPTS/check.sh"
contains "$tmp/check.out" "? aws sts get-caller-identity" "check"
contains "$tmp/check.out" "TICKETMASTER_API_KEY: set" "check names keys"
pass "aws-status, aws-logs, aws-seed, apk TARGET=aws, aws-bootstrap, aws-diff, aws-check: dry runs print the right calls"

# ── nothing real ran, and no secret was printed ─────────────────────────────────────────────────
[[ ! -s $tmp/called ]] || fail "a dry run called a real tool: $(head -3 "$tmp/called")"
pass "no aws, npx, docker, make, uv or curl was run by any dry run"
admin_password=$(cat "$tmp/secrets/aws/admin-password")
for f in "$tmp"/*.out; do
  for secret in SENTINEL "$admin_password" "$(cat "$tmp/secrets/aws/seed-password")" "BEGIN PRIVATE KEY"; do
    lacks "$f" "$secret" "secrets in $(basename "$f")"
  done
done
pass "no secret value in any output (API keys, passwords, the JWT key, the FCM key, the OTLP token)"

# ── confirmations ────────────────────────────────────────────────────────────────────────────────
if printf 'yes\n' | DRY_RUN=1 AWS_OUTPUTS_FILE="$HERE/outputs.json" "$SCRIPTS/pause.sh" >"$tmp/no.out" 2>&1; then
  fail "pause went ahead without the env name typed"
fi
lacks "$tmp/no.out" "+ aws" "a refused confirm"
contains "$tmp/no.out" "stopped" "a refused confirm"
printf 'demo\n' | DRY_RUN=1 AWS_OUTPUTS_FILE="$HERE/outputs.json" "$SCRIPTS/pause.sh" >"$tmp/yes.out" 2>&1 ||
  fail "pause stopped although the env name was typed"
contains "$tmp/yes.out" "+ aws rds stop-db-instance" "a confirmed pause"
pass "confirm: goes ahead only when the env name is typed"
if printf 'demo\ndemo\n' | DRY_RUN=1 AWS_OUTPUTS_FILE="$HERE/outputs.json" "$SCRIPTS/destroy.sh" >"$tmp/d2.out" 2>&1; then
  fail "destroy went ahead on the second question without 'destroy demo'"
fi
lacks "$tmp/d2.out" "+ npx cdk destroy" "destroy's second question"
printf 'demo\ndestroy demo\nno\n' | DRY_RUN=1 AWS_OUTPUTS_FILE="$HERE/outputs.json" "$SCRIPTS/destroy.sh" >"$tmp/d3.out" 2>&1 ||
  fail "destroy failed with both answers given"
contains "$tmp/d3.out" "+ npx cdk destroy --all" "destroy after two answers"
lacks "$tmp/d3.out" "+ aws s3 rb" "destroy's clips question answered no"
pass "aws-destroy: a second question naming the data ('destroy demo'); the clips need a third ('delete clips')"

# ── guards ───────────────────────────────────────────────────────────────────────────────────────
if AWS_OUTPUTS_FILE="$HERE/outputs.json" "$SCRIPTS/status.sh" >"$tmp/guard.out" 2>&1; then
  fail "AWS_OUTPUTS_FILE without DRY_RUN must refuse"
fi
contains "$tmp/guard.out" "dry runs only" "fixture guard"
if DRY_RUN=1 AWS_OUTPUTS_FILE="$HERE/outputs.json" bash -x "$SCRIPTS/secrets.sh" >"$tmp/xtrace.out" 2>&1; then
  fail "a script must refuse to run under set -x"
fi
lacks "$tmp/xtrace.out" "SENTINEL" "xtrace refusal"
pass "guards: fixture outputs only with DRY_RUN=1; no script runs under set -x"

# A dirty tree stops a real push (the image tag is the commit).
repo="$tmp/repo"
mkdir -p "$repo/ops/aws" "$repo/backend"
cp "$SCRIPTS/lib.sh" "$repo/ops/aws/"
echo readme >"$repo/README"
git -C "$repo" init -q && git -C "$repo" add -A && git -C "$repo" -c user.email=t@t -c user.name=t commit -qm init
(source "$repo/ops/aws/lib.sh" && require_clean_tree) || fail "a clean tree was refused"
echo new >"$repo/backend/Untracked.java"
if (source "$repo/ops/aws/lib.sh" && require_clean_tree) 2>/dev/null; then fail "an untracked file in backend/ was accepted"; fi
rm "$repo/backend/Untracked.java" && echo changed >>"$repo/README"
if (source "$repo/ops/aws/lib.sh" && require_clean_tree) 2>/dev/null; then fail "a changed tracked file was accepted"; fi
pass "require_clean_tree: refuses changed tracked files and untracked files in backend/"

# ── the app secret, written through a stub AWS CLI that keeps what it is sent ────────────────────
stub="$tmp/stub-aws"
mkdir -p "$stub"
cat >"$stub/aws" <<STUB
#!/usr/bin/env bash
echo "\$*" >>"$tmp/aws-args"
case "\$1 \$2" in
  "secretsmanager describe-secret") [[ -s $tmp/stored.json ]] && { echo "arn:secret	None"; exit 0; }; exit 254 ;;
  "secretsmanager get-secret-value") cat "$tmp/stored.json" ;;
  "secretsmanager create-secret"|"secretsmanager put-secret-value") cat >"$tmp/stored.json"; echo ok ;;
  *) exit 97 ;;
esac
STUB
chmod +x "$stub/aws"
export AWS_ADMIN_EMAIL=admin@aws.test
YES=1 PATH="$stub:$PATH" "$SCRIPTS/secrets.sh" >"$tmp/secret1.out" 2>&1 || fail "secrets: $(cat "$tmp/secret1.out")"
json="$tmp/stored.json"
[[ $(jq -r 'keys | join(",")' "$json") == JWT_PRIVATE_KEY_PEM,LASTFM_API_KEY,OTLP_AUTHORIZATION,PUSH_CREDENTIALS_JSON,SETLISTFM_API_KEY,SOUNDCHECK_ADMIN_EMAIL,SOUNDCHECK_ADMIN_PASSWORD,TICKETMASTER_API_KEY ]] ||
  fail "secret keys: $(jq -r 'keys | join(",")' "$json")"
[[ $(jq -r .SOUNDCHECK_ADMIN_PASSWORD "$json") == "$admin_password" ]] || fail "the AWS admin password must come from .secrets/aws/admin-password"
[[ $(jq -r .SOUNDCHECK_ADMIN_EMAIL "$json") == admin@aws.test ]] || fail "AWS_ADMIN_EMAIL must win"
[[ $(jq -r .OTLP_AUTHORIZATION "$json") == "Basic SENTINEL-5555" ]] || fail "OTLP_AUTHORIZATION, quotes stripped"
[[ $(jq -r .PUSH_CREDENTIALS_JSON "$json" | jq -r .private_key) == fcm-SENTINEL-6666 ]] || fail "PUSH_CREDENTIALS_JSON"
jq -r .JWT_PRIVATE_KEY_PEM "$json" | grep -q 'BEGIN PUBLIC KEY' || fail "the JWT PEM needs both blocks"
[[ $(stat -c %a "$tmp/secrets/aws/jwt-es256.pem") == 600 ]] || fail "the AWS JWT key must be mode 600"
grep -q 'create-secret .*--secret-string file:///dev/stdin' "$tmp/aws-args" || fail "create-secret must read stdin"
for secret in SENTINEL "$admin_password" "PRIVATE KEY"; do
  lacks "$tmp/aws-args" "$secret" "secret value on the AWS CLI's command line"
  lacks "$tmp/secret1.out" "$secret" "secret value in the output"
done
pass "aws-secrets: the JSON holds the expected keys, the AWS-only admin password and email; values only through stdin"
YES=1 PATH="$stub:$PATH" "$SCRIPTS/secrets.sh" >"$tmp/secret2.out" 2>&1 || fail "secrets rerun"
contains "$tmp/secret2.out" "unchanged: nothing written" "secret unchanged"
! grep -q put-secret-value "$tmp/aws-args" || fail "an unchanged secret was written"
LASTFM_API_KEY=lfm-SENTINEL-changed YES=1 PATH="$stub:$PATH" "$SCRIPTS/secrets.sh" >"$tmp/secret3.out" 2>&1 || fail "secrets change"
grep -q 'put-secret-value .*--secret-string file:///dev/stdin' "$tmp/aws-args" || fail "a changed secret must be put"
[[ $(jq -r .LASTFM_API_KEY "$json") == lfm-SENTINEL-changed ]] || fail "the changed value"
pass "aws-secrets: unchanged content is not written again; a change is put as a new version"

echo "dry-run test: $passed checks passed"
