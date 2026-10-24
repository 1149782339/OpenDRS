#!/usr/bin/env bash
# Manage the migration data-source stack: binlog-enabled source MySQL (:3406,
# database e2e_source, user e2e/123456) and the PostgreSQL target (:5432,
# database e2e_target, user opendrs/123456).
#
# Start once and leave running across ./deploy/dev.sh cycles:
#
#   ./scripts/test/data-sources.sh          # up (default)
#   ./scripts/test/data-sources.sh down
#   ./scripts/test/data-sources.sh restart
#   ./scripts/test/data-sources.sh logs [service]
#   ./scripts/test/data-sources.sh ps
#
# Data lives in the named volumes mysql_source_data / pg_data, so `down`
# (without -v) keeps it and the next `up` continues where it left off.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE="${COMPOSE:-docker-compose}"
# Fixed project name: the root stack and CI both resolve the project from the repo
# root directory ("opendrs"). Running this file standalone would otherwise derive
# "test" from this directory and fight the existing containers over container_name.
PROJECT="${COMPOSE_PROJECT_NAME:-opendrs}"

cd "$DIR"
cmd="${1:-up}"
case "$cmd" in
  up)
    "$COMPOSE" -p "$PROJECT" -f docker-compose.yml up -d --wait
    printf '\nData sources ready:\n  source MySQL    127.0.0.1:3406  db=e2e_source  e2e/123456\n  target Postgres 127.0.0.1:5432  db=e2e_target  opendrs/123456\n'
    ;;
  down)
    "$COMPOSE" -p "$PROJECT" -f docker-compose.yml down
    ;;
  restart)
    "$COMPOSE" -p "$PROJECT" -f docker-compose.yml restart
    ;;
  logs)
    shift
    "$COMPOSE" -p "$PROJECT" -f docker-compose.yml logs -f --tail 100 "$@"
    ;;
  ps)
    "$COMPOSE" -p "$PROJECT" -f docker-compose.yml ps
    ;;
  *)
    echo "usage: $0 [up|down|restart|logs|ps]  (default: up)" >&2
    exit 2
    ;;
esac
