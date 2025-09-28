#!/usr/bin/env bash
# make aws-bootstrap: once per account and region, CDK's own stack (CDKToolkit: an S3 bucket and an
# ECR repository for assets, and the roles cdk deploy assumes). Kept by aws-destroy.
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

account=$(ask aws sts get-caller-identity --query Account --output text) || die "not signed in: aws sso login --profile soundcheck"
account=${account:-<account>}
confirm "Bootstrap CDK in aws://$account/$AWS_REGION: the CDKToolkit stack (a staging bucket and repository; cents a month)."
# imageTag: in case the CLI synthesises the app here; the Compute stack refuses to synth without one.
cd "$ROOT/infra" || exit
run npx cdk bootstrap "aws://$account/$AWS_REGION" --profile "$AWS_PROFILE" -c imageTag=bootstrap
