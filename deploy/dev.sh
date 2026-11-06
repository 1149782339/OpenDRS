#!/usr/bin/env bash
# One-command local verification loop.
#
#   ./deploy/dev.sh
#
# Compiles on the host (fast: warm ~/.m2 and node_modules), then refreshes the backend
# image and restarts the stack. Nothing is compiled inside the container.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="${COMPOSE:-docker-compose}"
# Application stack only. The migration data sources (scripts/test/docker-compose.yml)
# are started once separately via scripts/test/data-sources.sh and are NOT touched here,
# so re-running this script never disturbs a running migration demo.
# -p opendrs pins the project name: with this file under deploy/ the default would
#   become "deploy", breaking container/volume sharing with data-sources.sh and CI.
COMPOSE_FILES=(-p opendrs -f deploy/docker-compose.yml)

cd "$ROOT"

log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

log "1/4  building backend jar (tests skipped)"
# The MSYS variables Git Bash injects here break the maven shell entry point.
# Unset them in this shell instead of wrapping mvn in env(1): mvn is an extensionless
# shell script, and env.exe would bypass bash and fail with "No such file or directory".
unset MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL || true
mvn -q -B -DskipTests package

log "2/4  building frontend bundle"
(cd opendrs-web && npm run build)

log "3/4  refreshing images and restarting the stack"
# The jar changed in step 1, so the backend image must rebuild from scratch. Podman's
# classic builder (no buildx on this host) does not always invalidate the COPY layer on
# Windows — a plain `--build` can reuse a stale app.jar and run yesterday's code.
"$COMPOSE" "${COMPOSE_FILES[@]}" build --no-cache backend
"$COMPOSE" "${COMPOSE_FILES[@]}" up -d --wait

log "4/4  waiting for http://localhost"
for _ in $(seq 1 90); do
  # Probe through nginx, not the backend port directly.
  #  - A real endpoint, not "/": "/" answers 500 (unmapped path) and would lie.
  #  - nginx listens on 0.0.0.0:80, which WSL forwards on IPv4. The backend binds a
  #    dual-stack socket that only surfaces as [::1]:8080 on Windows, so probing
  #    127.0.0.1:8080 is refused even when the backend is perfectly healthy.
  # Going through nginx also proves the reverse proxy hop works.
  #  - --noproxy: a shell that exports http_proxy sends this probe to the proxy, which cannot
  #    reach 127.0.0.1, so the loop would never see 200 even though the stack is healthy.
  #  - `|| true`: curl under Git Bash exits 23 (write error) because it cannot write its body to
  #    /dev/null; the status is still printed, but `set -e` would abort the script on the way out.
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 --noproxy '*' \
    http://127.0.0.1/api/v1/migration/connections || true)
  if [ "$code" = "200" ]; then
    printf '\nReady:  http://localhost\n\n'
    exit 0
  fi
  sleep 1
done

printf '\nAPI did not answer within 90s. Last backend logs:\n\n'
"$COMPOSE" "${COMPOSE_FILES[@]}" logs --tail 60 backend
exit 1
