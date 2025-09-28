#!/usr/bin/env bash
# make aws-secrets: writes the app secret soundcheck/<env>/app (one JSON object; keys in
# secret_json below). The JSON is built in memory from .env and .secrets/ and piped
# straight to the AWS CLI: it is never printed, never written to disk and never passed as an argument.
# It is written only when its content changed (compared by SHA-256, neither side printed). CDK only
# refers to the secret by name, so values never pass through CloudFormation.
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

# The site is public: the admin password is AWS's own (generated once), never the local one; the
# admin's email is AWS_ADMIN_EMAIL, or the local admin's email.
JWT_FILE=$(jwt_key_file)
ADMIN_PASSWORD_FILE=$(aws_secret_file admin-password)
ADMIN_EMAIL=$(env_value AWS_ADMIN_EMAIL)
ADMIN_EMAIL=${ADMIN_EMAIL:-$(env_value SOUNDCHECK_ADMIN_EMAIL)}
export ADMIN_EMAIL

missing=()
for key in TICKETMASTER_API_KEY LASTFM_API_KEY SETLISTFM_API_KEY; do
  [[ -n $(env_value "$key") ]] || missing+=("$key")
done
[[ -n $ADMIN_EMAIL ]] || missing+=("AWS_ADMIN_EMAIL (or SOUNDCHECK_ADMIN_EMAIL)")
((${#missing[@]} == 0)) || die "missing in .env: ${missing[*]}"

PUSH_FILE=""
if [[ $(env_value PUSH_ENABLED) == true ]]; then
  # As the backend reads it: PUSH_CREDENTIALS_FILE is relative to backend/.
  PUSH_FILE=$(env_value PUSH_CREDENTIALS_FILE)
  PUSH_FILE=${PUSH_FILE:+$ROOT/backend/$PUSH_FILE}
  PUSH_FILE=${PUSH_FILE:-$SECRETS_DIR/fcm-service-account.json}
  [[ -s $PUSH_FILE ]] || die "PUSH_ENABLED=true but the FCM key file is missing (PUSH_CREDENTIALS_FILE)"
  jq -e . "$PUSH_FILE" >/dev/null 2>&1 || die "the FCM key file is not JSON: $PUSH_FILE"
fi

# The secret, canonical (sorted keys, one line), so the same settings always hash the same.
# Values come from the environment ($ENV) and files (--rawfile), never from the command line.
secret_json() {
  local push_args=(--argjson push null)
  [[ -n $PUSH_FILE ]] && push_args=(--slurpfile push "$PUSH_FILE")
  jq -cnS --rawfile jwt "$JWT_FILE" --rawfile admin "$ADMIN_PASSWORD_FILE" "${push_args[@]}" '
    def strip: if test("^\".*\"$") then .[1:-1] else . end;
    {
      JWT_PRIVATE_KEY_PEM: $jwt,
      TICKETMASTER_API_KEY: ($ENV.TICKETMASTER_API_KEY | strip),
      LASTFM_API_KEY: ($ENV.LASTFM_API_KEY | strip),
      SETLISTFM_API_KEY: ($ENV.SETLISTFM_API_KEY | strip),
      SOUNDCHECK_ADMIN_EMAIL: $ENV.ADMIN_EMAIL,
      SOUNDCHECK_ADMIN_PASSWORD: ($admin | rtrimstr("\n"))
    }
    + (if $push == null then {} else {PUSH_CREDENTIALS_JSON: ($push[0] | tojson)} end)
    + (($ENV.GRAFANA_OTLP_AUTHORIZATION // "" | strip) as $otlp
       | if $otlp == "" then {} else {OTLP_AUTHORIZATION: $otlp} end)'
}

keys=$(secret_json | jq -r 'keys | join(", ")')
say "app secret $APP_SECRET_NAME: keys $keys"

state=""
if ! offline; then
  # Exists, exists but scheduled for deletion (after aws-destroy), or not at all.
  state=$(aws secretsmanager describe-secret --secret-id "$APP_SECRET_NAME" \
    --query '[ARN, DeletedDate]' --output text 2>/dev/null) || state=""
else
  say "? aws secretsmanager describe-secret --secret-id $APP_SECRET_NAME"
fi

if [[ -z $state ]]; then
  confirm "Create the secret $APP_SECRET_NAME in $AWS_REGION (\$0.40 a month)."
  secret_json | run_stdin aws secretsmanager create-secret --name "$APP_SECRET_NAME" \
    --description "Soundcheck $SOUNDCHECK_ENV app settings (make aws-secrets)" \
    --tags Key=Project,Value=soundcheck Key=Environment,Value="$SOUNDCHECK_ENV" \
    --secret-string file:///dev/stdin --query Name --output text
  exit 0
fi

if [[ $(awk '{print $2}' <<<"$state") != None ]]; then
  run aws secretsmanager restore-secret --secret-id "$APP_SECRET_NAME" --query Name --output text
fi

want=$(secret_json | sha256sum | cut -d' ' -f1)
have=$(aws secretsmanager get-secret-value --secret-id "$APP_SECRET_NAME" --query SecretString --output text 2>/dev/null |
  jq -cS . 2>/dev/null | sha256sum | cut -d' ' -f1) || have=""
if [[ $want == "$have" ]]; then
  say "app secret unchanged: nothing written"
  exit 0
fi
secret_json | run_stdin aws secretsmanager put-secret-value --secret-id "$APP_SECRET_NAME" \
  --secret-string file:///dev/stdin --query VersionId --output text
say "app secret updated. Tasks read it when they start: ones already running keep the old values until"
say "they are replaced (a deploy with a new image does it; README \"Running on AWS\" has the one-line restart)"
