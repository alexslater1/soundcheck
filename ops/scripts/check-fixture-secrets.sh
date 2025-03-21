#!/usr/bin/env bash
# Fails if any value from .env (keys, tokens, passwords) appears in fixtures/ or in the given paths.
# Values are never printed: only the variable name and the files that contain it.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
env_file="$root/.env"
[ -f "$env_file" ] || { echo "no .env — nothing to check"; exit 0; }
if [ "$#" -gt 0 ]; then targets=("$@"); else targets=("$root/fixtures"); fi

status=0
checked=0
while IFS= read -r line; do
  case "$line" in ''|\#*) continue ;; esac
  line="${line#export }"
  name="${line%%=*}"
  value="${line#*=}"
  # Strip one pair of surrounding double or single quotes.
  value="${value%\"}"; value="${value#\"}"
  value="${value%\'}"; value="${value#\'}"
  case "$name" in
    *KEY*|*TOKEN*|*SECRET*|*PASSWORD*) ;;
    *) continue ;;
  esac
  # Short values (blank, "test", "false") would match everything and are not secrets.
  [ "${#value}" -ge 12 ] || continue
  case "$name" in *_PATH) continue ;; esac
  checked=$((checked + 1))
  if hits=$(grep -rlF -- "$value" "${targets[@]}" 2>/dev/null); then
    echo "SECRET FOUND: the value of $name appears in:"
    echo "$hits" | sed 's/^/  /'
    status=1
  fi
done < "$env_file"
[ "$status" -eq 0 ] && echo "fixture secret check: $checked secret values checked, none found in ${targets[*]#$root/}"
exit "$status"
