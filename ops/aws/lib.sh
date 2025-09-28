# shellcheck shell=bash
# Shared by every ops/aws script. Source it; don't run it.
#
#   out <Stack> <OutputKey>   a stack output (describe-stacks, or $AWS_OUTPUTS_FILE in tests)
#   confirm "<what and cost>" asks to type the environment's name, unless YES=1
#   run <command…>            prints the command, then runs it; DRY_RUN=1 only prints it
#   ask <command…>            a read-only AWS call whose answer the script uses
#   require_clean_tree, sha   the image tag is the commit (12 characters)
#
# DRY_RUN=1: nothing that changes AWS (or builds images) runs; read-only calls still do, so a dry run
# with real credentials shows the real plan. AWS_OUTPUTS_FILE=<json> (needs DRY_RUN=1): fully offline,
# for tests and CI. Outputs come from the file ({"Stack": {"OutputKey": "value"}}, the format
# `cdk deploy --outputs-file` writes) and read-only calls are printed, not run.
#
# Never `set -x` in these scripts: aws-secrets handles the app secret.

# shellcheck disable=SC2034 # ROOT, AWS_DIR, STACKS… are used by the scripts that source this file
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
AWS_DIR="$ROOT/ops/aws"

# Soundcheck runs only in eu-north-1 (a choice, pinned in infra/lib/config.ts too), so the region is
# fixed here, not taken from .env.
export AWS_PROFILE=soundcheck
export AWS_REGION=eu-north-1
export AWS_DEFAULT_REGION=eu-north-1
export SOUNDCHECK_ENV=${SOUNDCHECK_ENV:-demo}
# Credentials in the environment would win over the profile (.env.example warns about LocalStack's
# test/test), and an endpoint override would send the CLI to LocalStack.
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_ENDPOINT_URL AWS_ENDPOINT_URL_S3 AWS_ENDPOINT_URL_SQS

export AWS_PAGER=""
SECRETS_DIR=${SECRETS_DIR:-$ROOT/.secrets}
AWS_LOCAL=${AWS_LOCAL:-$ROOT/.localdata/aws}
APP_SECRET_NAME="soundcheck/$SOUNDCHECK_ENV/app"
STACKS=(SoundcheckNetwork SoundcheckData SoundcheckEdge SoundcheckCompute SoundcheckObservability SoundcheckWeb)

if [[ $- == *x* ]]; then
  echo "refusing to run with xtrace (set -x) on: it would print secrets" >&2
  exit 1
fi

die() {
  echo "error: $*" >&2
  exit 1
}

say() { echo "$*" >&2; }

dry() { [[ ${DRY_RUN:-} == 1 ]]; }
offline() { [[ -n ${AWS_OUTPUTS_FILE:-} ]]; }

if offline; then
  dry || die "AWS_OUTPUTS_FILE is for dry runs only: set DRY_RUN=1 too"
  AWS_OUTPUTS_FILE=$(realpath "$AWS_OUTPUTS_FILE") # scripts may cd (infra/, lab/)
  export AWS_OUTPUTS_FILE
fi

# The command as one readable line: plain words as they are, anything else in single quotes.
show() {
  local word words=()
  for word in "$@"; do
    if [[ $word =~ ^[A-Za-z0-9_./:=,@%+-]+$ ]]; then
      words+=("$word")
    else
      words+=("'${word//\'/\'\\\'\'}'")
    fi
  done
  printf '%s' "${words[*]}"
}

# A command that changes something (AWS, images, the web build): printed, then run unless DRY_RUN=1.
run() {
  say "+ $(show "$@")"
  dry && return 0
  "$@"
}

# As run, for a command that reads its input from a pipe (the app secret): under DRY_RUN the input
# is drained and dropped, so the writer doesn't fail on a closed pipe.
run_stdin() {
  say "+ $(show "$@") < (stdin)"
  if dry; then
    cat >/dev/null
    return 0
  fi
  "$@"
}

# A read-only AWS call whose output the script uses. Offline it is printed and answers nothing.
ask() {
  if offline; then
    say "? $(show "$@")"
    return 0
  fi
  "$@"
}

# Whether a stack exists (any state but deleted).
stack_exists() {
  local stack=$1 status
  if offline; then
    jq -e --arg s "$stack" 'has($s)' "$AWS_OUTPUTS_FILE" >/dev/null
    return
  fi
  status=$(aws cloudformation describe-stacks --stack-name "$stack" \
    --query 'Stacks[0].StackStatus' --output text 2>/dev/null) || return 1
  [[ -n $status && $status != DELETE_COMPLETE ]]
}

# A stack output by its contract id. Under DRY_RUN a missing one becomes a
# placeholder such as <SoundcheckEdge.PublicBaseUrl>, so a dry run of a first deploy still reads.
out() {
  local stack=$1 key=$2 value=""
  if offline; then
    value=$(jq -r --arg s "$stack" --arg k "$key" '.[$s][$k] // empty' "$AWS_OUTPUTS_FILE")
  else
    value=$(aws cloudformation describe-stacks --stack-name "$stack" \
      --query "Stacks[0].Outputs[?OutputKey=='$key'].OutputValue | [0]" --output text 2>/dev/null) || value=""
    [[ $value == None ]] && value=""
  fi
  if [[ -z $value ]]; then
    dry || die "no output $key on $stack: is it deployed? (make aws-status)"
    value="<$stack.$key>"
  fi
  printf '%s\n' "$value"
}

# Asks before anything billable or destructive. The answer is typed (the environment's name unless
# another phrase is given), so an Enter pressed half-asleep can't say yes. YES=1 skips the question.
#   confirm "<what will happen, with the cost>" [phrase]
confirm() {
  local phrase=${2:-$SOUNDCHECK_ENV} answer=""
  say ""
  say "$1"
  if [[ ${YES:-} == 1 ]]; then
    say "(YES=1: going ahead)"
    return 0
  fi
  printf "Type '%s' to go ahead, anything else stops: " "$phrase" >&2
  read -r answer || true
  [[ $answer == "$phrase" ]] || die "stopped: nothing was changed by this step"
}

# The image tag is the commit, so the images must be built from exactly that commit: no changes to
# tracked files, and no untracked files where the images are built from (backend/, contracts/).
require_clean_tree() {
  local changes
  # Any change or untracked (not ignored) file, anywhere: `make images` tags such a tree <commit>-dirty
  # (Makefile IMAGE_TAG), and the push would then find no <commit> image.
  changes=$(git -C "$ROOT" status --porcelain --untracked-files=all)
  [[ -z $changes ]] && return 0
  if dry; then
    say "(dry run) the working tree has changes; a real run stops here:"
    say "$changes"
    return 0
  fi
  say "$changes"
  die "commit or stash these first: images are tagged with the commit, so it must be what they contain"
}

sha() { git -C "$ROOT" rev-parse --short=12 HEAD; }

# A value from .env as make exported it: make keeps quotes literally (MUSICBRAINZ_USER_AGENT="…").
env_value() {
  local value=${!1:-}
  value=${value#\"}
  value=${value%\"}
  printf '%s' "$value"
}

# A context value from infra/cdk.json, or the given default when the key isn't there.
cdk_default() {
  jq -r --arg k "$1" --arg d "$2" '.context[$k] // $d | tostring' "$ROOT/infra/cdk.json"
}

# The ECS service's scalable target id for application-autoscaling.
scalable_id() { printf 'service/%s/%s' "$1" "$2"; }

# Timing lines: step_start "<name>", then step_end prints how long it took.
STEP_NAME=""
STEP_T0=0
TIMINGS=()
step_start() {
  STEP_NAME=$1
  STEP_T0=$(date +%s)
  say ""
  say "== $STEP_NAME ($(date -u +%H:%M:%SZ))"
}
step_end() {
  local took=$(($(date +%s) - STEP_T0))
  TIMINGS+=("$STEP_NAME: ${took} s")
  say "== $STEP_NAME: done in ${took} s"
}
timings() {
  local line
  say ""
  say "Timings ($(date -u +%FT%TZ), commit $(sha)$(dry && echo ', dry run')):"
  for line in "${TIMINGS[@]}"; do say "  $line"; done
}

# One line on why an AWS call failed, for the two causes that are easy to misread.
explain_aws_error() {
  local text=$1
  if grep -qi 'explicit deny' <<<"$text"; then
    echo "an explicit deny in an IAM policy: wrong region, profile or permission set (Soundcheck runs only in eu-north-1, profile soundcheck)"
  elif grep -qiE 'expired|ExpiredToken|login|could not be found|Unable to locate credentials' <<<"$text"; then
    echo "no valid credentials: aws sso login --profile soundcheck"
  else
    grep -m1 . <<<"$text" || true # the first non-empty line: the CLI's message starts with a blank one
  fi
}

# The PostgreSQL version the Data stack pins: PostgresEngineVersion.of('17.5', …) in infra/lib/config.ts,
# or a named VER_17_n. Empty when neither is found.
infra_postgres_version() {
  {
    grep -rhoE "PostgresEngineVersion\.of\('17\.[0-9]+'" "$ROOT/infra/lib" | grep -oE "17\.[0-9]+"
    grep -rhoE 'VER_17_[0-9]+' "$ROOT/infra/lib" | sed 's/VER_//; s/_/./'
  } 2>/dev/null | head -1 || true
}

# A read-only call for aws-check: prints its answer, or explains the failure and returns 1.
# Offline it is printed and returns 2.
try_aws() {
  local errfile output
  if offline; then
    say "? $(show "$@")"
    return 2
  fi
  errfile=$(mktemp)
  if output=$("$@" 2>"$errfile"); then
    rm -f "$errfile"
    printf '%s\n' "$output"
    return 0
  fi
  say "  failed: $(show "$@")"
  say "  why: $(explain_aws_error "$(cat "$errfile")")"
  rm -f "$errfile"
  return 1
}

# Secrets made for AWS only, in .secrets/aws/ (gitignored): the site is public, so it never reuses a
# local key or password. Each is generated once, mode 0600; only its path is ever printed.
aws_secret_file() { # <name>: admin-password | seed-password; prints the path
  local file="$SECRETS_DIR/aws/$1"
  if [[ ! -s $file ]]; then
    mkdir -p "$SECRETS_DIR/aws"
    (umask 077 && openssl rand -base64 24 >"$file")
    chmod 600 "$file"
    say "generated $file (read it with: cat $file)"
  fi
  printf '%s\n' "$file"
}

# The JWT signing key for AWS (not the local one), as `make keys` makes it: a PKCS#8 private key
# followed by its public key, both PEM.
jwt_key_file() {
  local file="$SECRETS_DIR/aws/jwt-es256.pem"
  if [[ ! -s $file ]]; then
    mkdir -p "$SECRETS_DIR/aws"
    (
      umask 077
      openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out "$file"
      public=$(openssl ec -in "$file" -pubout 2>/dev/null)
      printf '%s\n' "$public" >>"$file"
    )
    chmod 600 "$file"
    say "generated $file (ES256, P-256)"
  fi
  printf '%s\n' "$file"
}

# The context every cdk command gets (deploy, diff, destroy): CDK_CONTEXT=(-c key=value …), built from
# the commit and .env. Flags follow .env (PUSH_ENABLED; OTLP when both Grafana settings are set); a
# one-off override goes on the make command line: make aws-deploy PUSH_ENABLED=false.
# Usage: cdk_context <imageTag> <webOrigin or ""> <webDist or "">
CDK_CONTEXT=()
cdk_context() {
  local email ua otlp_endpoint
  CDK_CONTEXT=(-c "env=$SOUNDCHECK_ENV" -c "imageTag=$1")
  if [[ -n $2 ]]; then CDK_CONTEXT+=(-c "webOrigin=$2"); fi
  if [[ -n $3 ]]; then CDK_CONTEXT+=(-c "webDist=$3"); fi
  email=$(env_value AWS_ALARM_EMAIL)
  if [[ -n $email ]]; then CDK_CONTEXT+=(-c "alarmEmail=$email"); fi
  if [[ $(env_value PUSH_ENABLED) == true ]]; then
    CDK_CONTEXT+=(-c pushEnabled=true)
  else
    CDK_CONTEXT+=(-c pushEnabled=false)
  fi
  otlp_endpoint=$(env_value GRAFANA_OTLP_ENDPOINT)
  if [[ -n $otlp_endpoint && -n $(env_value GRAFANA_OTLP_AUTHORIZATION) ]]; then
    CDK_CONTEXT+=(-c otlpEnabled=true -c "otlpEndpoint=$otlp_endpoint")
  else
    CDK_CONTEXT+=(-c otlpEnabled=false)
  fi
  ua=$(env_value MUSICBRAINZ_USER_AGENT)
  if [[ -n $ua ]]; then CDK_CONTEXT+=(-c "musicbrainzUserAgent=$ua"); fi
}
