#!/usr/bin/env bash
# make aws-destroy: removes every Soundcheck stack. Asks twice; the second question names
# the data that goes. The database leaves a final snapshot (its removal policy). The media bucket is
# retained by CloudFormation so clips survive a mistake: deleting it, and scheduling the app secret's
# deletion (7 days to undo), is a third, separate question, which YES=1 alone never answers yes
# (DELETE_MEDIA=1 does). CDKToolkit stays for the next deploy.
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

# Read before the stacks go: their outputs go with them.
media_bucket=$(out SoundcheckEdge MediaBucketName)
db=$(out SoundcheckData DbInstanceId)
account=$(ask aws sts get-caller-identity --query Account --output text) || die "not signed in: aws sso login --profile soundcheck"

confirm "Destroy every Soundcheck stack of '$SOUNDCHECK_ENV' in account ${account:-<account>} ($AWS_REGION)."
confirm "This deletes, with no undo:
  - the database $db (a final snapshot is kept; restoring it is a manual step),
  - the transcode queue and dead-letter queue with any messages in them,
  - the container images in ECR, the web bucket, the log groups, the alarms and the dashboard,
  - CloudFront's address: a new deploy gets a different https://<id>.cloudfront.net.
Kept: the media bucket $media_bucket (clips), the app secret, CDKToolkit." "destroy $SOUNDCHECK_ENV"

# The Web stack exists in the app only when webDist is set, and cdk destroy --all only removes stacks in
# the app; an empty placeholder folder puts it there (nothing is uploaded on destroy).
placeholder=$(mktemp -d)
trap 'rm -rf "$placeholder"' EXIT
echo '<!doctype html><title>placeholder</title>' >"$placeholder/index.html"
cdk_context "$(sha)" "" "$placeholder"
(cd "$ROOT/infra" && run npx cdk destroy --all --force --profile "$AWS_PROFILE" "${CDK_CONTEXT[@]}")
say "stacks destroyed; the database's final snapshot: aws rds describe-db-snapshots --db-instance-identifier $db"

say ""
say "Also delete the clips (every object in $media_bucket, then the bucket) and schedule the app secret"
say "$APP_SECRET_NAME for deletion (recoverable for 7 days)?"
if [[ ${YES:-} == 1 && ${DELETE_MEDIA:-} != 1 ]]; then
  say "(YES=1 without DELETE_MEDIA=1: kept)"
  exit 0
fi
if [[ ${DELETE_MEDIA:-} != 1 ]]; then
  printf "Type 'delete clips' to delete them, anything else keeps them: " >&2
  read -r answer || true
  if [[ $answer != "delete clips" ]]; then
    say "kept $media_bucket and $APP_SECRET_NAME"
    exit 0
  fi
fi
run aws s3 rb "s3://$media_bucket" --force
run aws secretsmanager delete-secret --secret-id "$APP_SECRET_NAME" --recovery-window-in-days 7 --query DeletionDate --output text
say "media bucket deleted; app secret scheduled for deletion (aws secretsmanager restore-secret undoes it within 7 days)"
