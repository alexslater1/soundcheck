#!/usr/bin/env bash
# make aws-push: pushes soundcheck-{api,worker}:<commit> to ECR (and soundcheck-lab with PUSH_LAB=1;
# the lab runs locally). A tag already in ECR is skipped, so a redeploy of the same
# commit pushes nothing. Images are built by `make images` from a clean tree.
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

require_clean_tree
TAG=$(sha)
images=(api worker)
[[ ${PUSH_LAB:-} == 1 ]] && images+=(lab)

declare -A REPO
todo=()
for image in "${images[@]}"; do
  key="${image^}RepositoryUri" # ApiRepositoryUri, WorkerRepositoryUri, LabRepositoryUri
  REPO[$image]=$(out SoundcheckData "$key")
  digest=$(ask aws ecr describe-images --repository-name "${REPO[$image]##*/}" --image-ids imageTag="$TAG" \
    --query 'imageDetails[0].imageDigest' --output text 2>/dev/null) || digest=""
  if [[ -n $digest && $digest != None ]]; then
    say "soundcheck-$image:$TAG is already in ECR: skipped"
  else
    todo+=("$image")
  fi
done
((${#todo[@]})) || exit 0

# Build only when a needed image isn't on this machine yet.
for image in "${todo[@]}"; do
  if dry || ! docker image inspect "soundcheck-$image:$TAG" >/dev/null 2>&1; then
    run make -C "$ROOT" images
    break
  fi
done

registry=${REPO[${todo[0]}]%%/*}
say "+ aws ecr get-login-password | docker login --username AWS --password-stdin $registry"
if ! dry; then
  aws ecr get-login-password | docker login --username AWS --password-stdin "$registry" >/dev/null
fi

for image in "${todo[@]}"; do
  run docker tag "soundcheck-$image:$TAG" "${REPO[$image]}:$TAG"
  run docker push --quiet "${REPO[$image]}:$TAG"
done
