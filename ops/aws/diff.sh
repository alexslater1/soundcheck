#!/usr/bin/env bash
# make aws-diff (read-only): what aws-deploy would change, stack by stack, with the same context.
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

TAG=$(sha)
edge_url=""
if stack_exists SoundcheckEdge; then edge_url=$(out SoundcheckEdge PublicBaseUrl); fi
web_dist=../app/dist
stacks=("${STACKS[@]}")
if [[ ! -s $ROOT/app/dist/index.html ]]; then
  say "warning: no app/dist (make web EXPO_PUBLIC_API_BASE_URL=): the Web stack is left out of this diff,"
  say "         so Edge may show exports to remove that a real deploy keeps"
  web_dist=""
  stacks=("${STACKS[@]:0:5}")
fi
cdk_context "$TAG" "$edge_url" "$web_dist"
cd "$ROOT/infra" || exit
ask npx cdk diff "${stacks[@]}" --profile "$AWS_PROFILE" "${CDK_CONTEXT[@]}"
