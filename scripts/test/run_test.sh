#!/usr/bin/env bash
# Run the test framework against a profile.
#
#   ./run_test.sh                          # default: profiles/ci.yml
#   ./run_test.sh --profile profiles/dev.yml
#   ./run_test.sh --profile profiles/ci.yml -v     # extra args pass through
#
# Run from anywhere; the script cd's to its own directory first.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

PROFILE="profiles/ci.yml"
ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --profile)
      PROFILE="$2"
      shift 2
      ;;
    --profile=*)
      PROFILE="${1#--profile=}"
      shift
      ;;
    *)
      ARGS+=("$1")
      shift
      ;;
  esac
done

exec uv run python -m framework.runner --profile "$PROFILE" "${ARGS[@]}"
