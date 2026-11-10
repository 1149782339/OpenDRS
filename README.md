<p align="center">
  <img src="docs/images/logo.png" alt="OpenDRS — Open Data Replication Service" width="420">
</p>

English | [简体中文](README_CN.md)

**Open Data Replication Service** — an open-source data replication platform for heterogeneous databases, driven from a bilingual (English / 中文) web UI.

OpenDRS is a service for full-load migration and change-data sync across databases. You configure sources and tasks in the browser; the engine captures changes, applies them on the target as transactions, and gives you precheck and operations in one place — no need to assemble a CDC connector, a sink, and a UI yourself. Incremental capture today uses embedded [Debezium](https://debezium.io).

Shipped and planned source / target combinations are listed in [Supported replication paths](#supported-replication-paths).

[![CI](https://github.com/1149782339/OpenDRS/actions/workflows/ci.yml/badge.svg)](https://github.com/1149782339/OpenDRS/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![CDC](https://img.shields.io/badge/CDC-Debezium%203.7-brightgreen.svg)](https://debezium.io)

![Task list](docs/images/task-list.png)

![Task monitoring](docs/images/task-monitor.png)

<!-- TODO (optional): replace the two stills above with a 10–20s GIF of "create task → precheck passes → run → metrics appear" -->

## Contents

- [Why OpenDRS](#why-opendrs)
- [Features](#features)
- [Use cases](#use-cases)
- [Architecture](#architecture)
- [Supported replication paths](#supported-replication-paths)
- [Quick start](#quick-start)
- [Walkthrough](#walkthrough)
- [How it compares](#how-it-compares)
- [Current limitations](#current-limitations)
- [API and local development](#api-and-local-development)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

## Why OpenDRS

Replicating data across databases — between different engines, or between two instances of the same one — usually means stitching a pipeline together yourself: a CDC tool to capture changes, a custom sink, then prechecks, checkpoints, metrics, and a UI on top. The pipeline can work, but wiring it and debugging it is expensive.

OpenDRS turns that pipeline into a service you can deploy. You configure data sources and tasks in the browser; the engine runs the snapshot, CDC, replay, and day-to-day operations.

## Features

- **Full load and incremental CDC in one task** — The initial snapshot copies rows, then streaming CDC starts automatically. No separate export/import pass.
- **Schema migration on supported paths** — Unidirectional tasks can create tables on the target when a schema converter exists (currently MySQL DDL → PostgreSQL). Other paths require the tables to already exist.
- **Precheck** — Connectivity, versions, privileges, path-specific CDC settings, primary keys, target instance health, table presence, and more are scored as pass, warn, or block. Blocking findings must be fixed before you start.
- **Bidirectional sync on supported paths** — Each direction runs its own full load and incremental stream, with loop prevention. Currently available for MySQL ↔ MySQL (GTID tagging and peer-side filtering).
- **Operable tasks** — Start, pause (incremental phase only), resume, restart, terminate, and delete. Actions that aren't valid for the current state are disabled in the UI.
- **Bilingual web UI** — Every page is available in English and Simplified Chinese; switch languages with one click in the top bar.
- **Observability** — Target-side apply TPS, sync lag, and transaction execute / commit time, collected with Prometheus and shown with ECharts.

## Use cases

- Heterogeneous migration between engines — for example MySQL → PostgreSQL: move the existing data, then keep following changes.
- Two-region active-active setups on a supported bidirectional path (currently MySQL ↔ MySQL; this release does not detect cross-region conflicts; see [Current limitations](#current-limitations)).
- A one-time full copy with no ongoing sync (choose "full load only").

## Architecture

<p align="center">
  <img src="docs/images/architecture.png" alt="OpenDRS architecture" width="860">
</p>

Capture and replay are selected by database type. Today, capture uses embedded Debezium against MySQL, and replay has JDBC dialects for PostgreSQL and MySQL. See [Supported replication paths](#supported-replication-paths) for what is shipped versus planned.

Modules (Maven multi-module project, group `io.opendrs`):

- **`opendrs-engine`**: the framework-free engine. Its public API is `MigrationEngine` (`supportsPrecheck` / `migrateSchema` / `migrateData` / `precheck`). Offsets and schema history are stored in the task metadata database. Submodules:
  - `engine-capture`: CDC and snapshot (embedded Debezium today)
  - `engine-schema`: DDL conversion (currently MySQL → PostgreSQL)
  - `engine-replay`: transactional JDBC apply with target dialects
  - `engine-precheck`: rule packs matched by source → target profile
  - `engine-core`: orchestration and table mapping
- **`opendrs-service`**: Spring Boot app with the task state machine (phase × state), async precheck, scheduling, Prometheus metrics, MyBatis + Flyway.
- **`opendrs-web`**: Vue 3 + Element Plus + ECharts. Not a Maven module; the `npm run build` output is served by nginx.
- **`opendrs-jdbc` / `opendrs-common`**: connections, dialects, and shared metadata types.

The design for resuming a full load from a checkpoint is in [`docs/design/full-migration-resume.md`](docs/design/full-migration-resume.md).

## Supported replication paths

OpenDRS is built as a multi-source, multi-target platform. **Supported** means the path can be created and run on `main`. **Planned** rows are example future directions — they are not available yet and can be removed or rewritten if the roadmap changes.

| Source | Target | Status | Notes |
|---|---|---|---|
| MySQL | PostgreSQL | Supported | Unidirectional. Schema migration (MySQL DDL → PostgreSQL), full snapshot, and incremental CDC. Modes: full + incremental, or full load only. |
| MySQL | MySQL | Supported | Full snapshot and incremental CDC. Unidirectional, or bidirectional (full + incremental only). No schema migration — tables must already exist. Bidirectional uses GTID tagging to prevent loops. |
| Oracle | PostgreSQL | Planned | |
| PostgreSQL | MySQL | Planned | |
| MySQL | Oracle | Planned | |

### Requirements on currently supported paths

These constraints apply to the **Supported** rows above (both use MySQL as the source). Precheck enforces most of them:

- Source binlog must be `ROW` + `FULL`, with enough retention (precheck defaults to ≥ 3 days). Tables should be InnoDB. Incremental apply locates rows by primary key.
- Source user privileges: `SELECT`, `RELOAD`, `REPLICATION SLAVE`, `REPLICATION CLIENT`, `SHOW DATABASES`.
- MySQL → MySQL also requires GTID (`gtid_mode=ON`) on the source. Bidirectional additionally requires GTID and CDC privileges on both sides, and distinct `server_id` / `server_uuid`.
- Bidirectional loop prevention: when the sink applies a transaction it uses `SET SESSION gtid_next` to write either the source GTID (incremental) or a task-scoped synthetic GTID namespace (full-load batches) into the target binlog. The peer capture drops transactions whose GTID UUID belongs to that peer.

## Quick start

Requirements: Docker (or Podman) and Docker Compose, JDK 21, Maven 3.8+, and Node.js 18+.

```bash
git clone https://github.com/1149782339/OpenDRS.git
cd OpenDRS
sh deploy/dev.sh
```

The script builds the backend jar and the frontend bundle on the host, recreates the application Compose stack, and probes `http://127.0.0.1/api/v1/migration/connections` until it is ready. Then open **http://localhost** (nginx reverse-proxies `/api` to the backend).

Application stack started by `deploy/dev.sh`:

| Service | Port | Description |
|---|---|---|
| frontend (nginx) | 80 | Web UI and API reverse proxy |
| backend | 8080 | Spring Boot app (host networking) |
| mysql | 3406 | Service metadata store (`root` / `123456` or `opendrs` / `123456`, database `opendrs`) |
| prometheus | 9090 | Replay metrics |

`dev.sh` does not start migration data sources. The bundled demo is one supported path (MySQL → PostgreSQL). To try it locally, also run:

```bash
./scripts/test/data-sources.sh
```

| Service | Port | Description |
|---|---|---|
| e2e-mysql1 | 3306 | Demo / e2e source (`root` / `123456` or `e2e_mysql` / `123456`, database `e2e_db`) |
| e2e-postgres1 | 5432 | Demo / e2e target (`e2e_postgres` / `123456`, database `e2e_db`) |

These are the endpoints you add under **Data sources** in the UI. On the MySQL source, `root` already has the CDC privileges precheck expects; `e2e_mysql` needs those grants before a full + incremental task will pass. A bidirectional MySQL ↔ MySQL task needs a second MySQL instance — this stack does not bundle one.

## Walkthrough

### 1. Add a data source

Go to **Data sources → Add data source** and fill in host, port, database, username, and password. Use **Test connection** before you save.

For MySQL 8 without SSL, add `"allowPublicKeyRetrieval": true` under Advanced settings (needed for `caching_sha2_password` public-key retrieval).

![Data sources](docs/images/connections.png)

### 2. Create a task

Go to **Tasks → New task**. The five-step wizard is: basic info → sync setup → advanced settings → precheck → confirm. After you confirm, the task is in **Not started**. Pick a source and target from a [supported path](#supported-replication-paths).

![Wizard: sync setup](docs/images/task-create-sync.png)

![Wizard: precheck](docs/images/task-create-precheck.png)

You can select a whole schema or individual tables. When both ends are MySQL, bidirectional sync is available. Additional screenshots (basic info, table / schema selection, bidirectional setup, confirm) live in `docs/images/`.

### 3. Start, pause, and watch

| State | Available actions |
|---|---|
| Not started | Start, terminate |
| Running | Pause (incremental phase only), terminate |
| Paused | Resume, terminate |
| Failed | Restart, terminate |
| Terminated | Delete |

- **Pause** stops CDC but keeps the checkpoint; resume continues from that position.
- **Terminate** releases task resources; a terminated task can only be deleted.

The task detail page shows source, capture, and apply positions and apply lag (forward and reverse for bidirectional tasks). **Task monitoring** plots apply TPS, sync lag, and transaction time. **Migration logs** can be filtered by level.

![Task detail](docs/images/task-detail.png)

## How it compares

The table below is about **product positioning**. It is not a benchmark and includes no performance numbers.

| | OpenDRS | [Debezium](https://debezium.io) | [Canal](https://github.com/alibaba/canal) | [DataX](https://github.com/alibaba/DataX) | [Flink CDC](https://github.com/apache/flink-cdc) |
|---|---|---|---|---|---|
| Positioning | End-to-end replication service with a web UI | CDC event platform (you bring the sink) | MySQL binlog subscription and dispatch | Offline batch sync framework | Flink-based real-time / batch integration |
| Full + incremental | One task, automatically chained | Snapshot + streaming events; you build replay | Primarily incremental subscription | Batch is the main job | Supported; you build the pipeline |
| Target writes | Built-in JDBC transactional apply | Hook up Kafka / a sink | Write a consumer or publish to a message queue | Built-in writer plugins | Configure a pipeline / sink |
| Web operations UI | Yes, English / 中文 (wizard, precheck, monitoring) | No (connector / platform oriented) | canal-admin | No (job configuration) | No (YAML / SQL) |
| Sources / targets | Multi-source / multi-target platform; see the [path matrix](#supported-replication-paths) for what is shipped today | Many CDC sources | MySQL | Large plugin ecosystem | Many sources and targets |
| Transport | In-process capture and apply | Commonly Kafka Connect; also embedded | Server / message queue | In-process channels | Flink job |

## Current limitations

Evaluate the project against these constraints; precheck will also catch some of them:

- Only the **Supported** rows in [Supported replication paths](#supported-replication-paths) can be run today. Other engines appear as connection types or planned rows, but are not wired through capture, replay, or the task wizard yet.
- On current MySQL-source paths, incremental apply locates rows by primary key, and selected tables should be InnoDB.
- Bidirectional sync assumes each region writes only its own data. **Cross-region conflict detection is not implemented.**
- Schema migration runs only on paths that have a converter (currently MySQL → PostgreSQL). MySQL → MySQL, including bidirectional, does not migrate schema.
- The project version is still `0.1.0-SNAPSHOT`. Try it in a test environment before trusting it with important data.

## API and local development

The HTTP API is under `/api/v1/migration`. Responses are `{code, message, data}` (`1000` means success).

```bash
# List data sources
curl http://localhost/api/v1/migration/connections

# Create a data source (demo MySQL from data-sources.sh)
curl -X POST http://localhost/api/v1/migration/connections \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo-mysql","connection":{"type":"MYSQL","host":"127.0.0.1","port":3306,
       "database":"e2e_db","username":"root","password":"123456",
       "extra":{"allowPublicKeyRetrieval":true}}}'

# Task lifecycle
curl -X POST http://localhost/api/v1/migration/tasks/{id}/precheck
curl -X POST http://localhost/api/v1/migration/tasks/{id}/start
curl -X POST http://localhost/api/v1/migration/tasks/{id}/stop      # pause
curl -X POST http://localhost/api/v1/migration/tasks/{id}/start     # resume when paused
curl -X POST http://localhost/api/v1/migration/tasks/{id}/terminate
curl -X DELETE http://localhost/api/v1/migration/tasks/{id}         # must already be terminated

# Status and observability
curl http://localhost/api/v1/migration/tasks/{id}/status
curl 'http://localhost/api/v1/migration/tasks/{id}/logs?page=1&size=50'
```

Local development:

```bash
# Backend
mvn compile
mvn test                      # unit tests; end-to-end cases live in scripts/test (containers required)
mvn -DskipTests package

# Frontend hot reload (proxies /api to :8080)
cd opendrs-web && npm run dev     # http://localhost:5173

# Rebuild and redeploy (same build steps as dev.sh)
mvn -q -DskipTests package && (cd opendrs-web && npm run build) \
  && docker compose -p opendrs -f deploy/docker-compose.yml up -d --build
```

## Roadmap

- [ ] **Data check** (full and incremental reconciliation) — in progress, not yet merged to `main`.
- [ ] **Kafka transport** for incremental CDC — in progress, not yet merged to `main`.
- [ ] Additional source and target engines — see the Planned rows in [Supported replication paths](#supported-replication-paths).
- [ ] Conflict handling for bidirectional sync.
- [ ] A more complete user guide.

Ideas and requests are welcome in [Issues](https://github.com/1149782339/OpenDRS/issues).

## Contributing

Bug reports, docs, and pull requests are welcome. There is no separate contributing guide yet — please open an issue first to describe what you want to do, or send a PR with the motivation and how you verified it.

CI runs backend unit tests, a frontend type-check and build, and end-to-end cases for a supported path (MySQL → PostgreSQL, full load and full load plus incremental).

## License

[Apache License 2.0](LICENSE).

CDC uses embedded [Debezium](https://debezium.io) (Apache 2.0). Third-party attributions are in [NOTICE](NOTICE).
