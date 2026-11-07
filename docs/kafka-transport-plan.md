# OpenDRS Kafka 传输通道引入计划

> 状态：**实现中（PR）**。①→⑥ 骨架已合入功能分支；DIRECT 默认语义保持 apply→mark。  
> 范围：MySQL → PostgreSQL 增量路径；引入 Kafka + Apicurio 作缓冲，保留 DIRECT | KAFKA 双通道。

---

## 1. 背景与目标

### 现状（DIRECT）

当前增量链路是：

```
Embedded Debezium → BatchApply → applier.apply(batch) → markProcessed → debezium_offsets
```

- `debezium_offsets` 存的是**源端续读位点内容**（binlog / GTID 等）。
- 提交时机绑在回放上：`apply` 成功后才 `committer.markProcessed()`，再经 `OffsetCommitPolicy.always()` 写入 `TaskOffsetBackingStore` / `debezium_offsets`。
- 因此今天：**位点前进 = 已落到目标库**，没有「只抓未回放」的第二套位点。
- `emitCaptured` 等指标可能在 `apply` 前打点，**只观测，不决定续跑**。

### 目标

- 引入 **Kafka + Apicurio** 作增量缓冲，形成 **DIRECT | KAFKA** 双通道。
- **先设计、后实现**；本 plan 定语义与步骤，代码改动等用户拍板。
- 参考仓库内 demo（`kafka-apicurio-cdc-demo`）时注意：demo 的 **flat Avro ≠ Debezium `SourceRecord` envelope**，**不能直接照搬**当生产序列化。

### 非目标（本阶段）

- ~~不在未授权前改引擎 / 服务代码。~~（已授权开干）
- 不把 demo 扁平 Avro 方案当生产序列化。
- 不让 Kafka 路径 silently 改变 DIRECT 的「apply 后才 mark」语义。

---

## 2. 位点语义（已拍板）

| 位点 | 谁记 | 含义 |
|------|------|------|
| `debezium_offsets`（今日 DIRECT） | OpenDRS 表 | 源端位点内容；**前进前提 = 已 apply 到目标并提交** |
| `debezium_offsets`（未来 KAFKA） | OpenDRS 表 | **已成功 produce 进 Kafka** 的那批 `SourceRecord` 对应的源端位点（≠ 已 apply） |
| Kafka consumer offset | Kafka consumer group（`__consumer_offsets` 等） | replay **从 topic 读到哪** |
| applied 位点（建议另加） | OpenDRS | 目标库 **已回放到哪** |

要点：

1. **DIRECT 继续**：`apply` 成功 → 才 `markProcessed` → 写 `debezium_offsets`。行为不回归。
2. **KAFKA capture**：`produce` 成功 → 才 mark `debezium_offsets`。语义变成「已入 Kafka」。
3. **Replay 读进度**：交给 Kafka consumer group，不塞进 `debezium_offsets`。
4. **必须另记 applied**：at-least-once 下可能「已 commit 读位点但 apply 失败」；也要区分「Kafka 里有了但还没 apply」和「两边都完了」。  
   **同表语义变化（produce 后 mark ≠ applied）正是拆第二套位点的原因。**

---

## 3. 隔离：topic / group / `public_id`

### 原则

- **topic** 与 **consumer group id** 必须按 **pipeline** 唯一，否则会窜（抢分区、位点互相覆盖）。
- 源端 `debezium_offsets` 本就按 `pipeline_id` 分键；Kafka 侧把 topic + group 绑到同一 pipeline 标识。

### `task_pipelines` 现状

- 只有自增 bigint `id`；offsets / history 挂这个内部 PK。
- `migration_tasks` 已有 `public_id`；**pipeline 还没有**。
- **不要用 task 的 `public_id`**：一个 task 可能双向两管，会窜。

### 设计

1. 加列：`task_pipelines.public_id VARCHAR(36) NOT NULL` + `UNIQUE uk_pipeline_public_id`。
2. 创建时：`UUID.randomUUID().toString()`（与 task 同一套路）。
3. **不改 PK / FK**：`debezium_offsets.pipeline_id` 等继续指 bigint `id`。
4. **topic / group 不落库**，用 `public_id` 派生，避免改名漂移：
   - topic：`opendrs.pipeline.{public_id}`
   - group：`opendrs.replay.{public_id}`
5. 存量行：先 backfill UUID，再加 `NOT NULL`。
6. 运维若要看见名字，可塞进 `capture_options` / `replay_options`，**绑定源仍是 `public_id`**。

---

## 4. 可行性要点（先前分析结论）

- **保真 `SourceRecord` envelope** 再进 Kafka；不要 demo 那种扁平 Avro。
- **`BatchApplyHandler` 抽象**回放入口；DIRECT 先切过去，行为不变。
- **`SnapshotCompletionConsumer` 门控**；单引擎下 serial snapshot ∥ stream 约束要保留。
- **双偏移**：capture/produce（`debezium_offsets`）vs applied（新位点）。
- **DIRECT 安全不回归**：未走 Kafka 时仍 apply 后才 mark。

---

## 5. 建议实施顺序（可勾选）

> 与先前「① 起干」一致。**当前仅文档**；用户说「开干」后再改代码。

- [x] **① `BatchApplyHandler` / 回放入口抽象**  
  DIRECT 先切到统一入口，行为与现网一致（apply → mark）。为 KAFKA replay 复用同一 apply 路径做准备。

- [x] **② `pipeline.public_id` + 迁移 backfill**  
  加列、唯一索引、创建时生成 UUID；存量 backfill 后再 `NOT NULL`。不改现有 PK/FK。

- [x] **③ Kafka produce 路径**  
  capture 侧：序列化 envelope → produce；**produce 成功后才** mark `debezium_offsets`。DIRECT 路径不动语义。

- [x] **④ Replay consumer**  
  consumer group = `opendrs.replay.{public_id}`；消费 → apply；**apply 成功后写 applied 位点**。读进度靠 Kafka group offset，应用进度靠 OpenDRS applied。

- [x] **⑤ Apicurio / Schema 与 envelope 序列化**  
  定生产用 schema；明确与 demo flat Avro 的边界；注册与兼容策略。

- [x] **⑥ 运维与验收**  
  topic 创建与命名（`opendrs.pipeline.{public_id}`）；按 pipeline 隔离验收（两管互不抢分区、位点不串）；DIRECT 回归（apply 后才 mark）。

---

## 6. 风险与注意

| 风险 | 说明 | 应对 |
|------|------|------|
| 位点语义变化 | KAFKA 下 `debezium_offsets` 不再等于「已 apply」 | 明确双位点；文档 / 运维面板区分 produce vs applied |
| 只靠 consumer offset | 已 commit 读位点但 apply 失败 | 必须另记 applied |
| topic/group 共用 | 多 pipeline 窜位点 | 一律按 `pipeline.public_id` 派生且唯一 |
| 照搬 demo | flat Avro 丢 envelope | 生产序列化保真 `SourceRecord` |
| 未授权改码 | 设计未拍板就动引擎 | 已授权；本 PR 交付 review |

---

## 7. 审阅清单（给用户）

请确认或拍板：

1. 位点三分法：`debezium_offsets`（produce）/ Kafka consumer offset（读）/ applied（OpenDRS）是否 OK？
2. `task_pipelines.public_id` + 派生命名（不落库 topic/group）是否 OK？
3. 实施顺序 ①→⑥ 是否从 ① 起干？还是要先做 ②？
4. Apicurio + envelope 序列化细节是否留到 ⑤ 再细开一页？
5. **是否授权开干**（改代码）？未授权则本 plan 仅文档。

---

## 8. 修订记录

| 日期（Asia/Shanghai） | 说明 |
|----------------------|------|
| 2026-11-07 | 初稿：汇总近期讨论（DIRECT 位点语义、Kafka 引入后双位点、pipeline `public_id` 隔离、实施顺序与非目标） |
| 2026-11-07 | 开干：BatchApplyHandler、public_id 迁移、KAFKA produce/consume、applied 表、Connect JSON envelope（非 demo flat Avro）；Apicurio URL 配置预留，schema 注册策略后续细化 |
