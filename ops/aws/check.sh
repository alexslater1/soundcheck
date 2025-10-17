#!/usr/bin/env bash
# make aws-check: read-only checks before a deploy. Changes nothing; exits 1 when
# something would make the deploy fail. Prints names of settings, never their values.
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

problems=0
problem() {
  say "  PROBLEM: $*"
  problems=$((problems + 1))
}
warning() { say "  warning: $*"; }

say "Soundcheck AWS check: env $SOUNDCHECK_ENV, profile $AWS_PROFILE, region $AWS_REGION$(offline && echo ' (offline: AWS calls are only printed)')"

say ""
say "Credentials"
signed_in=1
if identity=$(try_aws aws sts get-caller-identity --query '[Account, Arn]' --output text); then
  say "  account and identity: $identity"
  # Only the Expiration field is read; the credentials themselves stay inside the pipe.
  if expiry=$(aws configure export-credentials --profile "$AWS_PROFILE" 2>/dev/null | jq -r '.Expiration // empty') && [[ -n $expiry ]]; then
    left=$((($(date -d "$expiry" +%s) - $(date +%s)) / 60))
    # These are the role credentials of the SSO profile; when they run out, aws sso login signs in again.
    say "  current credentials expire at $expiry ($left min); then: aws sso login --profile soundcheck"
  else
    say "  credentials' expiry: not shown by this CLI"
  fi
else
  [[ $? == 2 ]] || {
    problem "not signed in (aws sso login --profile soundcheck)"
    signed_in=0
  }
fi

if ((signed_in)); then
  say ""
  say "CDK bootstrap"
  if status=$(try_aws aws cloudformation describe-stacks --stack-name CDKToolkit --query 'Stacks[0].StackStatus' --output text); then
    say "  CDKToolkit: $status"
  else
    [[ $? == 2 ]] || problem "CDKToolkit missing in $AWS_REGION: run make aws-bootstrap once"
  fi

  say ""
  api_cpu=$(cdk_default apiCpu 512)
  api_max=$(cdk_default apiMaxTasks 2)
  worker_max=$(cdk_default workerMaxTasks 2)
  # The worst case puts every task on on-demand capacity.
  worker_cpu=$(cdk_default workerCpu 4096)
  need=$(((api_cpu * api_max + worker_cpu * worker_max + 1023) / 1024))
  say "Fargate vCPU quotas (the maxima use up to $need vCPU: API $api_max x $api_cpu units, workers $worker_max x $worker_cpu)"
  for quota in L-3032A538:On-Demand L-36FBB829:Spot; do
    code=${quota%%:*}
    if value=$(try_aws aws service-quotas get-service-quota --service-code fargate --quota-code "$code" --query 'Quota.Value' --output text); then
      say "  ${quota#*:} ($code): $value vCPU"
      if [[ $code == L-3032A538 ]] && ((${value%.*} < need)); then
        warning "the on-demand quota is under the $need vCPU the maxima could use"
      fi
    fi
  done

  say ""
  say "RDS PostgreSQL 17 on db.t4g.micro"
  # Ask for exactly the pinned version on db.t4g.micro. The broad listing (every postgres option for the
  # class, several pages) came back empty for minutes at a time on 2025-09-28 while this narrow query kept
  # answering; an empty answer is still asked twice more before it counts as a problem.
  configured=$(infra_postgres_version)
  if [[ -z $configured ]]; then
    problem "can't find the PostgreSQL version infra pins (infra/lib/config.ts)"
  else
    for attempt in 1 2 3; do
      if ! count=$(try_aws aws rds describe-orderable-db-instance-options --engine postgres --engine-version "$configured" \
        --db-instance-class db.t4g.micro --query 'length(OrderableDBInstanceOptions)' --output text); then
        count=""
        break
      fi
      [[ $count == 0 && $attempt -lt 3 ]] || break
      sleep 5
    done
    if [[ -n $count && $count != 0 ]]; then
      say "  infra uses $configured: orderable ($count storage options)"
    elif [[ $count == 0 ]]; then
      problem "infra uses PostgreSQL $configured, not orderable on db.t4g.micro here (asked 3 times)"
    fi
  fi

  say ""
  say "CloudFront origin-facing prefix list (the ALB accepts only CloudFront)"
  if actual=$(try_aws aws ec2 describe-managed-prefix-lists --filters Name=prefix-list-name,Values=com.amazonaws.global.cloudfront.origin-facing \
    --query 'PrefixLists[0].PrefixListId' --output text); then
    configured=$(cdk_default cloudFrontPrefixListId "")
    say "  in $AWS_REGION: $actual; infra/cdk.json: ${configured:-unset}"
    [[ $actual == "$configured" ]] || problem "set cloudFrontPrefixListId to $actual in infra/cdk.json"
  fi

  say ""
  say "Stacks"
  for stack in "${STACKS[@]}"; do
    if offline; then
      say "? aws cloudformation describe-stacks --stack-name $stack"
      continue
    fi
    status=$(aws cloudformation describe-stacks --stack-name "$stack" --query 'Stacks[0].StackStatus' --output text 2>/dev/null) || status="not deployed"
    say "  $stack: $status"
    if [[ $status == *ROLLBACK_COMPLETE || $status == *_FAILED ]]; then
      warning "$stack is in $status: see its events in the CloudFormation console"
    fi
  done
  if ! offline && stack_exists SoundcheckData; then
    db=$(aws rds describe-db-instances --db-instance-identifier "$(out SoundcheckData DbInstanceId)" \
      --query 'DBInstances[0].DBInstanceStatus' --output text 2>/dev/null) || db="unknown"
    say "  database: $db"
    if [[ $db == stopped || $db == stopping ]]; then
      warning "paused: run make aws-resume before make aws-deploy"
    fi
  fi
fi

say ""
say "Settings for the app secret and the deploy (names only)"
have() { [[ -n $(env_value "$1") ]]; }
for key in TICKETMASTER_API_KEY LASTFM_API_KEY SETLISTFM_API_KEY; do
  if have "$key"; then say "  $key: set"; else problem "$key missing in .env (the app secret needs it)"; fi
done
if have AWS_ADMIN_EMAIL; then
  say "  AWS_ADMIN_EMAIL: set (the admin on AWS)"
elif have SOUNDCHECK_ADMIN_EMAIL; then
  say "  AWS_ADMIN_EMAIL: unset, so SOUNDCHECK_ADMIN_EMAIL is the admin on AWS"
else
  problem "AWS_ADMIN_EMAIL or SOUNDCHECK_ADMIN_EMAIL needed in .env"
fi
for file in aws/jwt-es256.pem aws/admin-password aws/seed-password; do
  if [[ -s $SECRETS_DIR/$file ]]; then say "  .secrets/$file: present"; else say "  .secrets/$file: missing (aws-secrets or aws-seed generates it)"; fi
done
if have AWS_ALARM_EMAIL; then say "  AWS_ALARM_EMAIL: set"; else warning "AWS_ALARM_EMAIL unset: alarms will go nowhere"; fi
if have GRAFANA_OTLP_ENDPOINT && have GRAFANA_OTLP_AUTHORIZATION; then
  say "  GRAFANA_OTLP_ENDPOINT, GRAFANA_OTLP_AUTHORIZATION: set (telemetry to Grafana Cloud on)"
else
  warning "GRAFANA_OTLP_ENDPOINT/GRAFANA_OTLP_AUTHORIZATION not both set: OTLP export off on AWS (CloudWatch logs and alarms still work)"
fi
if [[ $(env_value PUSH_ENABLED) == true ]]; then
  push_file=$(env_value PUSH_CREDENTIALS_FILE)
  push_file=${push_file:+$ROOT/backend/$push_file}
  push_file=${push_file:-$SECRETS_DIR/fcm-service-account.json}
  if [[ -s $push_file ]]; then say "  PUSH_ENABLED=true and the FCM key file: present"; else problem "PUSH_ENABLED=true but no FCM key file (PUSH_CREDENTIALS_FILE)"; fi
else
  say "  PUSH_ENABLED: not true (push off on AWS)"
fi
ua=$(env_value MUSICBRAINZ_USER_AGENT)
if [[ -z $ua || $ua == *you@example.com* ]]; then warning "MUSICBRAINZ_USER_AGENT needs a real contact address"; else say "  MUSICBRAINZ_USER_AGENT: set"; fi

say ""
if ((problems)); then
  say "aws-check: $problems problem(s) above"
  exit 1
fi
say "aws-check: ok"
