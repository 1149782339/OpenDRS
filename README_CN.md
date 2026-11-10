<p align="center">
  <img src="docs/images/logo.png" alt="OpenDRS — Open Data Replication Service" width="420">
</p>

[English](README.md) | 简体中文

**Open Data Replication Service** — 面向异构数据库的开源数据复制平台，用 Web 界面完成全量迁移与增量同步。

开源数据复制服务。在浏览器里配置数据源和任务，引擎负责捕获变更、在目标端按事务回放，并提供预检查和运维能力——不必自己拼一套 CDC 连接器、消费端和运维界面。增量捕获当前使用嵌入式 [Debezium](https://debezium.io)。

已支持和计划中的源 / 目标组合见[支持的同步链路](#支持的同步链路)。

[![CI](https://github.com/1149782339/OpenDRS/actions/workflows/ci.yml/badge.svg)](https://github.com/1149782339/OpenDRS/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![CDC](https://img.shields.io/badge/CDC-Debezium%203.7-brightgreen.svg)](https://debezium.io)

![任务列表](docs/images/task-list.png)

![任务监控](docs/images/task-monitor.png)

<!-- TODO（可选）: 用 10～20 秒的 GIF 替换上面两张静态图，演示「新建任务 → 预检查通过 → 跑起来 → 监控出数」 -->

## 目录

- [要解决的问题](#要解决的问题)
- [特性](#特性)
- [适用场景](#适用场景)
- [架构](#架构)
- [支持的同步链路](#支持的同步链路)
- [快速开始](#快速开始)
- [使用流程](#使用流程)
- [定位对比](#定位对比)
- [当前限制](#当前限制)
- [API 与本地开发](#api-与本地开发)
- [路线图](#路线图)
- [贡献](#贡献)
- [License](#license)

## 要解决的问题

在异构数据库之间做复制，或在同类实例之间互相同步，常见做法是自己拼链路：用 CDC 工具抓变更，再写一套 sink，再补预检查、断点、监控和界面。链路能跑，但组装和排障成本很高。

OpenDRS 把这条链路收成一个可部署的服务：在浏览器里配置数据源和任务，引擎负责快照、CDC、回放和运维动作。

## 特性

- **全量 + 增量一条任务做完** — 初始快照逐行复制，完成后自动接上增量 CDC，不必再走一轮导出/导入。
- **支持的链路上可做结构迁移** — 若该方向已有结构转换器，单向任务可在目标库自动建表（当前为 MySQL DDL → PostgreSQL）。其他链路要求表已经存在。
- **预检查** — 连接、版本、权限、该链路相关的 CDC 参数、主键、目标实例状态、表存在性等，逐项通过 / 告警 / 阻断，阻断项必须先解决。
- **支持的链路上可做双向同步** — 两个方向各自全量 + 增量，并防止回环。当前可用于 MySQL ↔ MySQL（GTID 打标 + 对端过滤）。
- **任务可运维** — 启动 / 暂停（仅增量阶段）/ 恢复 / 重启 / 结束 / 删除；非法操作在界面上置灰。
- **可观测** — 目标库回放 TPS、同步时延、事务执行 / 提交耗时（Prometheus 指标 + ECharts 展示）。

## 适用场景

- 在不同引擎之间做异构迁移，例如 MySQL → PostgreSQL：先搬存量，再持续跟随变更。
- 在已支持的双向链路上做双 region 互备（当前为 MySQL ↔ MySQL；本版本不做跨 region 冲突检测，见[当前限制](#当前限制)）。
- 只想搬一次存量、不需要持续同步（任务可选「仅全量」）。

## 架构

<p align="center">
  <img src="docs/images/architecture.png" alt="OpenDRS 架构图" width="860">
</p>

捕获和回放按数据库类型选择。目前捕获通过嵌入式 Debezium 对接 MySQL，回放提供 PostgreSQL 与 MySQL 的 JDBC 方言。已支持和计划中的组合见[支持的同步链路](#支持的同步链路)。

模块划分（Maven 多模块，坐标 `io.opendrs`）：

- **`opendrs-engine`**：无框架引擎，对外接口是 `MigrationEngine`（`supportsPrecheck` / `migrateSchema` / `migrateData` / `precheck`）。位点与 schema history 写在任务元数据库。子模块：
  - `engine-capture`：CDC 与快照（当前为嵌入式 Debezium）
  - `engine-schema`：DDL 转换（当前为 MySQL → PostgreSQL）
  - `engine-replay`：按目标方言做 JDBC 事务回放
  - `engine-precheck`：按源 → 目标类型匹配规则包
  - `engine-core`：编排与表映射
- **`opendrs-service`**：Spring Boot，负责任务状态机（阶段 × 状态）、异步预检、调度、Prometheus 指标、MyBatis + Flyway。
- **`opendrs-web`**：Vue 3 + Element Plus + ECharts。不是 Maven 模块，`npm run build` 的产物由 nginx 挂载。
- **`opendrs-jdbc` / `opendrs-common`**：连接与方言、共享元数据。

全量断点续跑的设计见 [`docs/design/full-migration-resume.md`](docs/design/full-migration-resume.md)。

## 支持的同步链路

OpenDRS 按多源、多目标平台来设计。**已支持**表示该链路可以在 `main` 上创建并跑起来。**计划中**是示例性的后续方向，尚未实现；如果路线图有变，可以直接删改这一块。

| 源 | 目标 | 状态 | 说明 |
|---|---|---|---|
| MySQL | PostgreSQL | 已支持 | 单向。结构迁移（MySQL DDL → PostgreSQL）、全量快照、增量 CDC。模式：全量 + 增量，或仅全量。 |
| MySQL | MySQL | 已支持 | 全量快照与增量 CDC。可单向，也可双向（必须全量 + 增量）。不做结构迁移，两端表需已存在。双向用 GTID 打标防止回环。 |
<!-- BEGIN PLANNED PATHS
     仅为后续方向示例，main 上尚未实现。
     路线图有变时，可整段删除或改写。 -->
| Oracle | PostgreSQL | 计划中 | |
| PostgreSQL | MySQL | 计划中 | |
| MySQL | Oracle | 计划中 | |
<!-- END PLANNED PATHS -->

### 当前已支持链路的前提

以下约束适用于上表中的**已支持**行（目前源端都是 MySQL）。预检查会覆盖其中大部分：

- 源库 binlog 须为 `ROW` + `FULL`，保留时长充足（预检查默认要求 ≥ 3 天）。表应为 InnoDB。增量回放按主键定位行。
- 源用户权限：`SELECT`、`RELOAD`、`REPLICATION SLAVE`、`REPLICATION CLIENT`、`SHOW DATABASES`。
- MySQL → MySQL 还要求源端开启 GTID（`gtid_mode=ON`）。双向时两端都要具备 GTID 和 CDC 权限，且 `server_id` / `server_uuid` 不同。
- 双向防回环：sink 回放时通过 `SET SESSION gtid_next` 把源端 GTID（增量）或任务级合成 GTID 命名空间（全量批次）写入目标 binlog，对端捕获按 GTID UUID 丢弃来自 peer 的事务。

## 快速开始

环境：Docker（或 Podman）+ Docker Compose、JDK 21、Maven 3.8+、Node.js 18+。

```bash
git clone https://github.com/1149782339/OpenDRS.git
cd OpenDRS
sh deploy/dev.sh
```

脚本会在本机编译后端 jar、打包前端，再重建应用 compose 栈，并探测 `http://127.0.0.1/api/v1/migration/connections` 直到就绪。然后打开 **http://localhost**（nginx 把 `/api` 反代到后端）。

`deploy/dev.sh` 拉起的应用栈：

| 服务 | 端口 | 说明 |
|---|---|---|
| frontend (nginx) | 80 | Web UI + API 反代 |
| backend | 8080 | Spring Boot（host 网络） |
| mysql | 3406 | 服务元数据库（`root` / `123456` 或 `opendrs` / `123456`，库 `opendrs`） |
| prometheus | 9090 | 回放指标采集 |

`dev.sh` **不会**启动迁移用的演示数据源。附带的演示走的是一条已支持链路（MySQL → PostgreSQL）。若要在本地试用，请再执行：

```bash
./scripts/test/data-sources.sh
```

| 服务 | 端口 | 说明 |
|---|---|---|
| e2e-mysql1 | 3306 | 演示 / e2e 源库（`root` / `123456` 或 `e2e_mysql` / `123456`，库 `e2e_db`） |
| e2e-postgres1 | 5432 | 演示 / e2e 目标库（`e2e_postgres` / `123456`，库 `e2e_db`） |

在界面的「数据源管理」里填这些地址即可。MySQL 源端用 `root` 已具备预检查要求的 CDC 权限；`e2e_mysql` 需要先授予相应权限，全量 + 增量任务才能通过预检查。双向 MySQL ↔ MySQL 还需要第二台 MySQL，当前演示栈没有附带。

## 使用流程

### 1. 准备数据源

**数据源管理 → 新增数据源**，填写主机、端口、数据库、用户名、密码。建议先点「测试连接」。

MySQL 8 非 SSL 连接需在「高级配置」中加上 `"allowPublicKeyRetrieval": true`（`caching_sha2_password` 公钥检索）。

![数据源管理](docs/images/connections.png)

### 2. 新建任务

**任务管理 → 新建任务**，五步向导：基本信息 → 设置同步 → 高级设置 → 预检查 → 确认。确认后任务进入「未启动」。请选择一条[已支持的同步链路](#支持的同步链路)。

![向导：设置同步](docs/images/task-create-sync.png)

![向导：预检查](docs/images/task-create-precheck.png)

可按整库或按表勾选对象；两端都是 MySQL 时可以选择双向同步。更多界面见 `docs/images/`（基本信息、表级 / 库级选择、双向、确认）。

### 3. 启动、暂停与观察

| 状态 | 可用操作 |
|---|---|
| 未启动 | 启动、结束 |
| 运行中 | 暂停（仅增量阶段）、结束 |
| 已暂停 | 恢复、结束 |
| 失败 | 重启、结束 |
| 已结束 | 删除 |

- **暂停**：停止 CDC 但保留断点，恢复后从原位置继续。
- **结束**：释放任务资源；结束后只能删除。

任务详情里可以看到源位点、抓取位点、回放位点、回放时延（双向任务分正向 / 反向）；「任务监控」展示回放 TPS、同步时延和事务耗时；「迁移日志」可按级别筛选。

![任务详情](docs/images/task-detail.png)

## 定位对比

下面只对比**产品定位**，不是性能评测，也没有基准数字。

| | OpenDRS | [Debezium](https://debezium.io) | [Canal](https://github.com/alibaba/canal) | [DataX](https://github.com/alibaba/DataX) | [Flink CDC](https://github.com/apache/flink-cdc) |
|---|---|---|---|---|---|
| 定位 | 带 Web 界面的端到端复制服务 | CDC 事件平台（你自己接消费端） | MySQL binlog 订阅与分发 | 离线批量同步框架 | 基于 Flink 的实时 / 批量集成 |
| 全量 + 增量 | 一条任务内完成并自动衔接 | 快照 + 流事件，回放需自建 | 以增量订阅为主 | 以批量为正业 | 支持，需自建 pipeline |
| 目标端写入 | 内置 JDBC 事务回放 | 需接 Kafka / sink | 需自己写消费端或投 MQ | 内置 Writer 插件 | 需配置 Pipeline / Sink |
| Web 运维界面 | 有（向导、预检查、监控） | 无（偏连接器 / 平台） | 有 canal-admin | 无（作业配置） | 无（YAML / SQL） |
| 源 / 目标 | 多源多目标平台；当前已交付的组合见[同步链路](#支持的同步链路) | 多源 CDC | MySQL | 大量异构插件 | 多源多目标 |
| 传输 | 进程内捕获并回放 | 常见于 Kafka Connect；也有 Embedded | Server / MQ | 进程内通道 | Flink 作业 |

## 当前限制

请以这些为前提评估是否适用，预检查也会拦一部分：

- 目前只有[支持的同步链路](#支持的同步链路)里标为**已支持**的行可以跑。其他引擎可能出现在数据源类型或「计划中」行里，但捕获、回放和任务向导尚未接通。
- 在当前以 MySQL 为源的链路上，增量回放按主键定位行，所选表应为 InnoDB。
- 双向同步假设各自 region 只写本 region 的数据，**不做跨 region 冲突检测**。
- 结构迁移只发生在已有转换器的链路上（当前为 MySQL → PostgreSQL）。MySQL → MySQL（含双向）不做结构迁移。
- 项目版本仍是 `0.1.0-SNAPSHOT`，建议先在测试环境验证后再用于重要数据。

## API 与本地开发

HTTP API 统一前缀 `/api/v1/migration`，响应包 `{code, message, data}`（`1000` 表示成功）。

```bash
# 列出数据源
curl http://localhost/api/v1/migration/connections

# 新建数据源（data-sources.sh 拉起的演示 MySQL）
curl -X POST http://localhost/api/v1/migration/connections \
  -H 'Content-Type: application/json' \
  -d '{"name":"demo-mysql","connection":{"type":"MYSQL","host":"127.0.0.1","port":3306,
       "database":"e2e_db","username":"root","password":"123456",
       "extra":{"allowPublicKeyRetrieval":true}}}'

# 任务生命周期
curl -X POST http://localhost/api/v1/migration/tasks/{id}/precheck
curl -X POST http://localhost/api/v1/migration/tasks/{id}/start
curl -X POST http://localhost/api/v1/migration/tasks/{id}/stop      # 暂停
curl -X POST http://localhost/api/v1/migration/tasks/{id}/start     # 已暂停时恢复
curl -X POST http://localhost/api/v1/migration/tasks/{id}/terminate # 结束
curl -X DELETE http://localhost/api/v1/migration/tasks/{id}         # 须已结束

# 状态与观测
curl http://localhost/api/v1/migration/tasks/{id}/status
curl 'http://localhost/api/v1/migration/tasks/{id}/logs?page=1&size=50'
```

本地开发：

```bash
# 后端
mvn compile
mvn test                      # 单测；端到端用例走 scripts/test（需要容器）
mvn -DskipTests package

# 前端热更新（把 /api 代理到 :8080）
cd opendrs-web && npm run dev     # http://localhost:5173

# 改完后重新部署（等价于 dev.sh 的构建部分）
mvn -q -DskipTests package && (cd opendrs-web && npm run build) \
  && docker compose -p opendrs -f deploy/docker-compose.yml up -d --build
```

## 路线图

- [ ] **数据校验**（全量 / 增量对账）— 开发中，尚未合入 `main`。
- [ ] **Kafka 传输通道**（增量 CDC）— 开发中，尚未合入 `main`。
- [ ] 更多源 / 目标引擎 — 见[支持的同步链路](#支持的同步链路)中的「计划中」行。
- [ ] 双向同步的冲突策略。
- [ ] 更完整的用户手册。

有想法或需求，欢迎开 [Issue](https://github.com/1149782339/OpenDRS/issues)。

## 贡献

Bug、文档和 Pull Request 都欢迎。目前还没有单独的贡献指南，请先开 Issue 说明意图，或直接提 PR 并写清动机与验证方式。

CI 会跑后端单测、前端类型检查与构建，以及一条已支持链路（MySQL → PostgreSQL）的全量、全量 + 增量端到端用例。

## License

[Apache License 2.0](LICENSE)。

CDC 嵌入 [Debezium](https://debezium.io)（Apache 2.0）。第三方归属见 [NOTICE](NOTICE)。
