#!/usr/bin/env bash
# make seed-demo TARGET=aws (make aws-seed): seed-demo against the AWS site, through its API only.
# The personas' password is AWS's own (.secrets/aws/seed-password, generated once), given only to the
# seed process. The seed keeps its progress apart from the local stack's (.localdata/aws/seed-<env>/),
# so neither run mistakes the other's accounts, gigs and posts for its own.
#   ops/aws/seed.sh [seed-demo options, e.g. --no-real --personas 3]
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

url=$(out SoundcheckEdge PublicBaseUrl)
seed_password_file=$(aws_secret_file seed-password)
admin_email=$(env_value AWS_ADMIN_EMAIL)
admin_email=${admin_email:-$(env_value SOUNDCHECK_ADMIN_EMAIL)}
admin_password_file="$SECRETS_DIR/aws/admin-password"

confirm "Seed $url: up to 30 personas and the demo fan, their gigs, posts, likes and engagement,
through the public API (options: ${*:-none}). Uploads go through S3 and the workers (cents)."

if offline; then
  say "? curl -sf $url/api/v1/health"
  say "? count upcoming gigs: sign in as the AWS admin, GET $url/api/v1/gigs?nationwide=true&limit=1"
else
  curl -sf --max-time 20 "$url/api/v1/health" >/dev/null || die "the API at $url doesn't answer: make aws-status"
  [[ -s $admin_password_file ]] || die "no $admin_password_file: run make aws-secrets first"
  # The password goes from its file into the request body through a pipe, never onto a command line.
  token=$(jq -n --arg email "$admin_email" --rawfile password "$admin_password_file" \
    '{email: $email, password: ($password | rtrimstr("\n"))}' |
    curl -sf --max-time 20 -H 'Content-Type: application/json' --data @- "$url/api/v1/auth/login" |
    jq -r '.accessToken // empty') || token=""
  if [[ -z $token ]]; then
    say "warning: could not sign in as the AWS admin to count upcoming gigs; seeding anyway"
  else
    upcoming=$(curl -sf --max-time 20 -H @<(printf 'Authorization: Bearer %s\n' "$token") \
      "$url/api/v1/gigs?nationwide=true&limit=1" | jq '.items | length') || upcoming=""
    [[ $upcoming != 0 ]] || die "No upcoming gigs on AWS: run make aws-ingest first"
  fi
fi

if [[ -z $(ls "$ROOT"/fixtures/clips/demo/demo-*.mp4 2>/dev/null) ]]; then
  run python3 "$ROOT/ops/scripts/make_clips.py" --demo
fi

# The seed's root for this target: its own .localdata/seed/ for progress, the real fixtures/ for clips.
seed_root="$AWS_LOCAL/seed-$SOUNDCHECK_ENV"
mkdir -p "$seed_root"
# The real clips (fixtures/clips/mine) are gitignored, so a worktree has none: use the main checkout's.
fixtures="$ROOT/fixtures"
main=$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) && main=${main%/.git}
if [[ ! -d $fixtures/clips/mine && -n $main && -d $main/fixtures/clips/mine ]]; then fixtures="$main/fixtures"; fi
[[ -e $seed_root/fixtures ]] || ln -s "$fixtures" "$seed_root/fixtures"
if [[ ! -d $seed_root/fixtures/clips/mine && " $* " != *" --no-real "* ]]; then
  say "warning: no fixtures/clips/mine behind $seed_root/fixtures: the real gigs and clips will be skipped"
fi

cd "$ROOT/lab" || exit
(
  # No DB_HOST: the report's database figures are local only, so they print as n/a (remote).
  unset DB_HOST
  SEED_PASSWORD=$(<"$seed_password_file")
  export SEED_PASSWORD
  run env SOUNDCHECK_ROOT="$seed_root" SOUNDCHECK_API_URL="$url" uv run soundcheck-lab seed-demo "$@"
)
