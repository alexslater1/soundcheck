#!/usr/bin/env bash
# make aws-logs SERVICE=api|worker|job (read-only): follows a log group from 15 minutes ago.
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

case ${1:-} in
  api) key=ApiLogGroup ;;
  worker) key=WorkerLogGroup ;;
  job) key=JobLogGroup ;;
  *) die "usage: make aws-logs SERVICE=api|worker|job" ;;
esac
group=$(out SoundcheckCompute "$key")
ask aws logs tail "$group" --follow --since 15m --format short
