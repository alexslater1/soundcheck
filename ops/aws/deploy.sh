#!/usr/bin/env bash
# make aws-deploy: the whole deploy from the laptop, in order:
#   1 aws-check · 2 web build · 3 Network, Data, Edge (Edge twice on a first deploy) · 4 app secret ·
#   5 images · 6 Compute, Observability, Web · 7 wait for the API · 8 aws-smoke
# Safe to rerun: CloudFormation applies only what changed, an image tag already in ECR is skipped and
# an unchanged secret isn't written. DRY_RUN=1 prints the plan without changing anything.
# ONLY="<Stack> …" (e.g. ONLY=SoundcheckObservability): a configuration-only deploy of those stacks that
# already exist, with the same context and the image tag that is running now; no web build, secret,
# images or smoke. For an infra change that needs no new image (an alarm, a policy, a task setting).
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

require_clean_tree
TAG=$(sha)
APPROVAL=broadening # CDK asks before anything that widens access (IAM, security groups)
if [[ ${YES:-} == 1 ]]; then APPROVAL=never; fi
WEB_DIST=../app/dist # relative to infra/, where cdk runs
mkdir -p "$AWS_LOCAL"

account=$(ask aws sts get-caller-identity --query Account --output text) || die "not signed in: aws sso login --profile soundcheck"

# A paused environment has its database stopped: a deploy would start tasks that can't reach it, the
# circuit breaker would roll them back, and CloudFormation would put its own task counts back.
if stack_exists SoundcheckData && ! offline; then
  db=$(aws rds describe-db-instances --db-instance-identifier "$(out SoundcheckData DbInstanceId)" \
    --query 'DBInstances[0].DBInstanceStatus' --output text 2>/dev/null) || db=""
  if [[ $db == stopped || $db == stopping ]]; then
    die "the environment is paused (database $db): run make aws-resume first, then make aws-deploy"
  fi
fi

# The tag in the API's task definition now: what an ONLY deploy keeps (offline: the commit).
running_image_tag() {
  if offline; then sha; return; fi
  local td
  td=$(aws ecs describe-services --cluster "$(out SoundcheckCompute ClusterName)" \
    --services "$(out SoundcheckCompute ApiServiceName)" --query 'services[0].taskDefinition' --output text)
  aws ecs describe-task-definition --task-definition "$td" \
    --query 'taskDefinition.containerDefinitions[0].image' --output text | sed 's/.*://'
}

only=()
if [[ -n ${ONLY:-} ]]; then
  read -ra only <<<"${ONLY//,/ }"
  for stack in "${only[@]}"; do
    [[ " ${STACKS[*]} " == *" $stack "* ]] || die "ONLY: no stack '$stack' (one of: ${STACKS[*]})"
    stack_exists "$stack" || die "ONLY: $stack isn't deployed yet; the first deploy is a full make aws-deploy"
  done
  TAG=$(running_image_tag)
  [[ -n $TAG ]] || die "ONLY: could not read the running image tag"
  confirm "Deploy only ${only[*]} of Soundcheck '$SOUNDCHECK_ENV' (infra at commit $(sha), images stay $TAG)
to account ${account:-<account>} ($AWS_REGION). No new image, web build or secret."
else
  confirm "Deploy Soundcheck '$SOUNDCHECK_ENV' at commit $TAG to account ${account:-<account>} ($AWS_REGION).
Billable: about \$60 a month while it runs (list prices, README \"Running on AWS\"); make aws-pause stops most of it."
fi
# One question per command: the steps below (the secret's first creation) don't ask again. CDK still
# asks before widening access unless YES=1 was given on the command line (APPROVAL, above).
export YES=1

# cdk deploy of exactly these stacks, with the context of this deploy; their outputs are merged into
# .localdata/aws/outputs.json (the same format as ops/aws/test/outputs.json).
cdk_deploy() {
  local last="$AWS_LOCAL/outputs-last.json"
  (cd "$ROOT/infra" && run npx cdk deploy "$@" --exclusively --profile "$AWS_PROFILE" \
    --require-approval "$APPROVAL" --outputs-file "$last" "${CDK_CONTEXT[@]}")
  if ! dry && [[ -s $last ]]; then
    if [[ -s $AWS_LOCAL/outputs.json ]]; then
      jq -s '.[0] * .[1]' "$AWS_LOCAL/outputs.json" "$last" >"$AWS_LOCAL/outputs.tmp"
      mv "$AWS_LOCAL/outputs.tmp" "$AWS_LOCAL/outputs.json"
    else
      mv "$last" "$AWS_LOCAL/outputs.json"
    fi
  fi
}

if ((${#only[@]})); then
  web_dist=""
  if [[ -s $ROOT/app/dist/index.html ]]; then web_dist=$WEB_DIST; fi
  if [[ -z $web_dist && " ${only[*]} " == *" SoundcheckWeb "* ]]; then
    die "ONLY: SoundcheckWeb needs app/dist (make web EXPO_PUBLIC_API_BASE_URL=)"
  fi
  step_start "only ${only[*]} (images $TAG)"
  cdk_context "$TAG" "$(out SoundcheckEdge PublicBaseUrl)" "$web_dist"
  cdk_deploy "${only[@]}"
  step_end
  timings
  exit 0
fi

step_start "1/8 aws-check"
"$AWS_DIR/check.sh"
step_end

# The web build first: it needs no AWS address (same origin), and with webDist in every cdk
# command the Web stack is always part of the app, so Edge keeps the exports Web imports.
step_start "2/8 web build (same origin)"
run make -C "$ROOT" web EXPO_PUBLIC_API_BASE_URL=
if ! dry && [[ ! -s $ROOT/app/dist/index.html ]]; then die "the web build left no app/dist/index.html"; fi
step_end

step_start "3/8 Network, Data, Edge"
edge_url=""
if stack_exists SoundcheckEdge; then edge_url=$(out SoundcheckEdge PublicBaseUrl); fi
cdk_context "$TAG" "$edge_url" "$WEB_DIST"
cdk_deploy SoundcheckNetwork SoundcheckData SoundcheckEdge
if [[ -z $edge_url ]]; then
  # First deploy: CloudFront's address exists only now. Edge again, so the media bucket's CORS allows
  # exactly this origin instead of any *.cloudfront.net.
  edge_url=$(out SoundcheckEdge PublicBaseUrl)
  say "first deploy: Edge once more with webOrigin=$edge_url"
  cdk_context "$TAG" "$edge_url" "$WEB_DIST"
  cdk_deploy SoundcheckEdge
fi
step_end

step_start "4/8 app secret"
"$AWS_DIR/secrets.sh"
step_end

step_start "5/8 images ($TAG)"
"$AWS_DIR/push.sh"
step_end

step_start "6/8 Compute, Observability, Web"
cdk_deploy SoundcheckCompute SoundcheckObservability SoundcheckWeb
step_end

step_start "7/8 API service stable"
cluster=$(out SoundcheckCompute ClusterName)
run aws ecs wait services-stable --cluster "$cluster" --services "$(out SoundcheckCompute ApiServiceName)"
step_end

step_start "8/8 aws-smoke"
"$AWS_DIR/smoke.sh"
step_end

timings
say ""
say "Soundcheck is at $edge_url"
