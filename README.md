# OpenDRS

Open data replication service (DRS). This repository currently ships the **v1 API skeleton**: persist migration tasks with MyBatis, validate table mappings, and run a **start-only** coordinator job that stubs dump/CDC phases. Full dump, Debezium Engine, and job stop are **not** implemented yet.

## Requirements

- Java 21
- Maven 3.8+
- MySQL 8 (service metadata store only)

## Stack

- Spring Boot 4.1.1 / Spring Web MVC / Validation
- **MyBatis** (`mybatis-spring-boot-starter` 4.1.0) — not JPA/Hibernate
- Flyway for DDL
- Jackson camelCase JSON

Package / Maven groupId: `io.opendrs`.

## Run locally

Start the metadata database:

```bash
docker compose up -d
```

Default MySQL credentials match the application defaults (`opendrs` / `opendrs`, database `opendrs` on port `3306`).

Then:

```bash
mvn spring-boot:run
```

The service listens on `http://localhost:8080`.

### Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `OPENDRS_PORT` | `8080` | HTTP port |
| `OPENDRS_DB_HOST` | `localhost` | Metadata MySQL host |
| `OPENDRS_DB_PORT` | `3306` | Metadata MySQL port |
| `OPENDRS_DB_NAME` | `opendrs` | Metadata database |
| `OPENDRS_DB_USER` | `opendrs` | Metadata user |
| `OPENDRS_DB_PASSWORD` | `opendrs` | Metadata password |

Flyway creates:

- `connection_info` — source/target credentials (passwords live only here)
- `migration_task` — task config + progress + FKs to connections
- `debezium_offset` — reserved for later Debezium `OffsetBackingStore` (v1 does not write)
- `debezium_schema_history` — reserved for later Debezium `DatabaseHistory` (v1 does not write)

Create still accepts nested `source` / `target` objects. The service inserts `connection_info` rows named `{taskName}-source` / `{taskName}-target` (including optional `extra`) and stores their ids on the task.

Independent connections can also be created, tested, and deleted under `/api/v1/migration/connections`. Create does **not** auto-test JDBC.

## API contract

Every HTTP body is:

```json
{
  "code": 1000,
  "message": "success",
  "data": {}
}
```

- Success: `code` is `1000`, `message` is `"success"`.
- Business failures (`1001`–`1006`): **HTTP 200**, `code` is the business code, `message` is the error text.
- System failures (`1500` DB, `1501` internal): **HTTP 500**, same envelope. SQL and stack traces are never returned.

| Code | Meaning | HTTP |
| --- | --- | --- |
| 1000 | SUCCESS | 200 |
| 1001 | PARAM_INVALID | 200 |
| 1002 | TASK_NOT_FOUND | 200 |
| 1003 | TASK_CONFLICT | 200 |
| 1004 | CONNECTION_NOT_FOUND | 200 |
| 1005 | CONNECTION_TEST_FAILED | 200 |
| 1006 | CONNECTION_IN_USE | 200 |
| 1500 | DB_ERROR | 500 |
| 1501 | INTERNAL_ERROR | 500 |

JSON is camelCase. Passwords are persisted on `connection_info` but always returned as `***`. Public ids are **BIGINT** (auto-increment). Task `name` and connection `name` are unique.

### Connection `extra`

Optional JSON object on request `ConnectionInfo`, persisted as `connection_info.extra_json`. Unknown keys are stored as-is. Do not put `database.server.id` here (that belongs in task options).

| Engine | Key | Notes |
| --- | --- | --- |
| Oracle | `pdb` | After connect: `ALTER SESSION SET CONTAINER`. Not part of the JDBC URL. |
| Oracle | `connectionType` | `SERVICE` (default) or `SID`. `database` is that value. |
| MySQL | `useSsl` | Mapped to JDBC `useSSL`. |
| MySQL | `serverTimezone` | JDBC `serverTimezone`. |

`JdbcUrlBuilder` URLs:

- MySQL: `jdbc:mysql://host:port/db` plus `useSSL` / `serverTimezone` query params from extra
- Oracle SERVICE: `jdbc:oracle:thin:@//host:port/service`
- Oracle SID: `jdbc:oracle:thin:@host:port:sid`

### Connection endpoints

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/v1/migration/connections` | Persist only. Duplicate `name` → `1001`. No JDBC ping. |
| `DELETE` | `/api/v1/migration/connections/{id}` | Missing → `1004`. Referenced by a task → `1006`. |
| `POST` | `/api/v1/migration/connections/test` | Body is request `ConnectionInfo`. Ping only, no persist. Fail → `1005`. |
| `POST` | `/api/v1/migration/connections/{id}/test` | Ping the stored row (real password). Fail → `1005`. |

Create request wraps `@NotBlank name` + `@Valid ConnectionInfo`:

```http
POST /api/v1/migration/connections
Content-Type: application/json
```

```json
{
  "name": "oracle-hr",
  "connection": {
    "type": "ORACLE",
    "host": "10.0.0.1",
    "port": 1521,
    "database": "ORCL",
    "username": "cdc",
    "password": "secret",
    "extra": {
      "pdb": "ORCLPDB1",
      "connectionType": "SERVICE"
    }
  }
}
```

Create response `data` includes `id`, `name`, `type`, `host`, `port`, `database`, `username`, `password` (`***`), `extra`, `createdAt`, `updatedAt`.

Test success `data`: `{ "ok": true, "latencyMs": 12 }`.

### Task endpoints

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/v1/migration/tasks` | Persist config + connections. State = `CREATED`. Does **not** auto-start. |
| `GET` | `/api/v1/migration/tasks` | Summaries: id, name, mode, state, source.type, target.type, createdAt |
| `GET` | `/api/v1/migration/tasks/{id}` | Detail VO |
| `GET` | `/api/v1/migration/tasks/{id}/status` | State + progress columns + placeholder offset |
| `POST` | `/api/v1/migration/tasks/{id}/start` | CAS → `STARTING`, launch coordinator job (not on the HTTP thread) |
| `POST` | `/api/v1/migration/tasks/{id}/stop` | Stub: `1003` (stop is not implemented) |
| `DELETE` | `/api/v1/migration/tasks/{id}` | Must not be running; clears `debezium_offset` / `debezium_schema_history` then the task |

### Create example

```http
POST /api/v1/migration/tasks
Content-Type: application/json
```

```json
{
  "name": "hr-oracle-to-mysql",
  "mode": "FULL_AND_INCREMENTAL",
  "source": {
    "type": "ORACLE",
    "host": "10.0.0.1",
    "port": 1521,
    "database": "ORCL",
    "username": "cdc",
    "password": "***",
    "extra": {
      "pdb": "ORCLPDB1",
      "connectionType": "SERVICE"
    }
  },
  "target": {
    "type": "MYSQL",
    "host": "10.0.0.2",
    "port": 3306,
    "database": "hr",
    "username": "drs",
    "password": "***",
    "extra": {
      "useSsl": false,
      "serverTimezone": "UTC"
    }
  },
  "tables": {
    "objects": [
      { "schema": "HR", "tables": ["EMPLOYEES", "DEPARTMENTS"] },
      { "schema": "SCOTT", "tables": ["EMP"] }
    ],
    "mappings": {
      "schema": [
        { "source": "SCOTT", "target": "scott" }
      ],
      "tables": [
        {
          "sourceSchema": "HR",
          "sourceTable": "EMPLOYEES",
          "targetSchema": "hr",
          "targetTable": "emp"
        }
      ]
    }
  },
  "options": {
    "fullDumpParallelism": 8,
    "batchSize": 1000
  }
}
```

`mode`: `FULL_ONLY` / `INCREMENTAL_ONLY` / `FULL_AND_INCREMENTAL`.  
`source.type` / `target.type` (v1): `ORACLE` / `MYSQL`.

Each `objects` item needs `schema` and either `allTables=true` or a non-empty `tables` list. `excludeTables` is stored as-is. Duplicate `schema` values in `objects` are rejected (`1001`). There is no `naming` / `LOWER` / `UPPER` field. If `mappings` is omitted, target names equal source names.

### Mapping resolution

1. Table mapping wins for that table.
2. Else schema mapping + original table name.
3. Else original `schema.table`.

Examples: `SCOTT.EMP` + schema `SCOTT→scott` → `scott.EMP`; `HR.EMPLOYEES` + table map → `hr.emp`; `HR.DEPARTMENTS` with no map → `HR.DEPARTMENTS`.

Create is rejected with `1001 PARAM_INVALID` when:

1. `mappings.schema`: duplicate source, or two sources map to the same target.
2. `mappings.tables`: duplicate `(sourceSchema, sourceTable)`, or two sources map to the same `(targetSchema, targetTable)`.
3. Cross-layer: schema map says `HR → hr` but a table in `HR` has `targetSchema != hr`. Same `targetSchema` with a renamed table is allowed.
4. Every mapped schema/table must be in `objects`. Explicit `tables` lists must include the mapped table; `allTables` only needs the schema. A mapped table listed in `excludeTables` is a conflict.

### State machine and start-only job

```
CREATED → STARTING → SCHEMA_SNAPSHOTTING → FULL → INCREMENTAL → STOPPING → STOPPED
                                      ↘ FAILED (any phase)
```

- **Start** allowed from `CREATED` / `STOPPED` / `FAILED` (else `1003`). CAS to `STARTING`, then `TaskJobRegistry` runs one `TaskJob` per task on a `ThreadPoolTaskExecutor` (not Tomcat).
- Job stubs (no real Debezium / dump):
  1. `SCHEMA_SNAPSHOTTING` — TODO first-round Engine (offset + schema history)
  2. `FULL` if `FULL_AND_INCREMENTAL` or `FULL_ONLY` — TODO parallel dump
  3. `INCREMENTAL` if `FULL_AND_INCREMENTAL` or `INCREMENTAL_ONLY` — **blocks** on a latch (stop ignored)
  4. `FULL_ONLY` after the FULL stub → `STOPPED` and the thread exits
- Job exceptions set `FAILED` + `error_message`.
- **Stop** is a stub (`1003`). `STOPPING` is not wired.
- **Delete** allowed in `STOPPED` / `CREATED` / `FAILED`. `STARTING` / `SCHEMA_SNAPSHOTTING` / `FULL` / `INCREMENTAL` (and `STOPPING`) return `1003`.

In-memory registry is runtime only; MySQL is the source of truth for state.

### Status shape

```json
{
  "id": 1,
  "state": "FULL",
  "progress": {
    "tablesTotal": 0,
    "tablesDone": 0,
    "rowsDone": 0,
    "lagMs": null
  },
  "offset": { "scn": null, "gtid": null },
  "error": null
}
```

## Tests

```bash
mvn -q test
```

Uses in-memory H2 (MySQL compatibility mode) with a Flyway schema that still names tables `debezium_offset` / `debezium_schema_history`. Connection test APIs are mocked in CI (`JdbcConnectionFactory`); no live Oracle/MySQL is required.

## Out of scope (v1)

- Embedded Debezium Engine / OffsetBackingStore / schema history writer
- Parallel SELECT/INSERT dump
- Job stop / cancel / STOPPING / graceful shutdown
- Connection list / update
- Embedded Oracle/MySQL source connectors (DriverManager helper only)
- Column mapping
- Kafka Connect
- Auth / Spring Security
- naming LOWER/UPPER
