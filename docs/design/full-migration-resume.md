# 全量迁移可重入（断点续传）方案

> 状态：设计定稿，待实现
> 背景调研：2026-10-24 基于 debezium 3.7.0.Final 源码（`D:\Code\debezium`）逐项核实

## 1. 问题定义

全量（快照）阶段任务崩溃：4 张表导完 3 张，重启后怎么办？

**Debezium 经典快照（initial / initial_only）不支持按表续传。**
`RelationalSnapshotChangeEventSource.getSnapshottingTask` 里，offset 中只记录
「快照是否完成」这一个布尔（`isInitialSnapshotRunning()`），没有任何已完成的表
清单或分片进度。崩溃重启后 `InitialSnapshotter.shouldSnapshotData` 判定快照未完成
→ **整批快照从头再来，全部表重导**。粒度是整个快照级别的 all-or-nothing。

**我们当前的回放链路让这个问题更致命。** 全量回放是裸 `INSERT`（主键取源库值）：
崩溃时 3 张表已写入 PG → 重启重导 → 已完成的表重复 INSERT → 主键冲突 → 任务再次
失败。即：全量任务目前不具备安全的重启重试能力。

## 2. 源码事实链（已核实）

### 2.1 位点时序：先定点，再导数

`RelationalSnapshotChangeEventSource.doExecute` 步骤顺序：

1. **Snapshot step 4 — Determining snapshot offset**：在任何表数据导出**之前**，
   `determineSnapshotOffset` 把当前 binlog/GTID 位点 **P0** 记入 offset；
2. **Snapshot step 7 — Snapshotting data**：逐表导出。

P0 之前已提交的数据由快照拷贝，P0 之后的变更由 binlog 补齐——正常运行**没有空洞**。

### 2.2 「续传空洞」的精确来源：一行代码

续传重启时能否沿用 P0，取决于 `BinlogSnapshotChangeEventSource` 的分支：

```java
if (previousOffset != null
        && !snapshotterService.getSnapshotter().shouldStreamEventsStartingFromSnapshot()) {
    ctx.offset = previousOffset;   // 复用 P0
}
```

`shouldStreamEventsStartingFromSnapshot()` 在 SPI 默认返回 `true`，
`InitialSnapshotter` 没有覆写 → 重启必然走 else 分支、**重新记录新位点 P2，P0 被丢弃**。

空洞出现在：已完成表在 `[P0, P2)` 期间的变更永久丢失（重导只拷 P2 之前的数据，
增量又从 P2 起读）。

### 2.3 表级完成状态：官方通知通道拿得到

`InitialSnapshotNotificationService`（notification.enabled.channels=sink，通知以普通
SourceRecord 走 `notification.sink.topic.name`，我们的 consumer 已能收到）：

| 通知 | 时机 | 关键字段 |
|-|-|-|
| `DATA_COLLECTIONS_RESOLVED` | 快照开始 | `data_collections`：本次快照全部表清单 |
| `TABLE_SCAN_COMPLETED` | **每张表导出结束** | `scanned_collection`、`status=SUCCEEDED`、`total_rows_scanned` |

开局拿全量清单 + 每收一条 TABLE_SCAN_COMPLETED 标记一张表 done = 完整的
「已完成表集合」。注意 `scanned_collection` 在 `additional_data` 的嵌套 map 里，
不在 record 顶层。另：`SnapshotProgressListener` 虽也有
`dataCollectionSnapshotCompleted`，但嵌入式场景不可插拔，不走。

### 2.4 控制导出范围：两个现成开关（CommonConnectorConfig）

| 开关 | 作用域 | 适用性 |
|-|-|-|
| `snapshot.include.collection.list` | 只限快照阶段 | **推荐**。增量阶段仍全表捕获 |
| `table.include.list` | 快照+增量都收窄 | FULL_ONLY 可用；FULL+增量会漏已完成表的后续变更，**禁用** |

## 3. 方案

### 3.1 核心：自定义 Snapshotter（官方扩展点，不改 Debezium 源码）

`SnapshotMode.CUSTOM` 是官方枚举（注释原话 "Inject a custom snapshotter"），
我们已有 `SchemaOnlySnapshotter` 同款机制（ServiceLoader 注册）。

```java
public class ResumableInitialSnapshotter extends InitialSnapshotter {
    @Override
    public boolean shouldStreamEventsStartingFromSnapshot() { return false; }
}
```

这一个布尔把 2.2 的分支扳向「复用 P0」。

### 3.2 续传流程

```
首次启动
  └─ 通知 DATA_COLLECTIONS_RESOLVED → 全量表清单记入 checkpoint（全部 pending）
  └─ 每张表 TABLE_SCAN_COMPLETED → 标记 done
  └─ 崩溃（假设 T1/T2 done，T3 半成品，T4 未开始）

重启（协调器续传分支）
  1. 读 checkpoint：剩余表 = 清单 − done = {T3, T4}
  2. 半成品 T3：FULL_ONLY 拥有目标表所有权 → TRUNCATE 目标端 T3（或对该表
     ON CONFLICT DO NOTHING 幂等回放，二选一）
  3. 不清 offset（保留 P0），snapshot.include.collection.list = {T3, T4}，
     以 ResumableInitialSnapshotter 重启引擎
  4. Debezium 命中复用分支 ctx.offset = P0，只重拍剩余表
  5. 增量从 P0 起回放：
     - 已完成表：[P0, 崩溃) 的变更由 binlog 补齐 → 无空洞
     - 剩余表：快照读到重启时刻数据，[P0, 重启) 的 binlog 重复回放 →
       靠按主键 upsert 幂等消化
```

**空洞消除 + 按表续传兼得。** 方案成立的最后一环：全量回放从裸 INSERT 改造为
按主键 upsert（INSERT ... ON CONFLICT），这正是「重复回放可消化」的前提。

### 3.3 checkpoint 表（自建，元数据库）

```sql
CREATE TABLE task_full_checkpoint (
  task_id     BIGINT       NOT NULL,
  table_name  VARCHAR(256) NOT NULL,   -- schema.table
  status      VARCHAR(16)  NOT NULL,   -- pending / loading / done
  row_count   BIGINT,
  updated_at  TIMESTAMP,
  PRIMARY KEY (task_id, table_name)
);
```

维护点：`SnapshotNotifications` 增解析 `TABLE_SCAN_COMPLETED`，写/更新对应行。

## 4. 前置条件与限制（必须写进用户文档）

1. **binlog 保留期必须覆盖整个全量迁移窗口**。P0 被 purge → 只剩
   「清空目标、全量重来」一条退路。
2. **无主键表不参与按表续传**：无主键做不到幂等回放，崩溃即整表重导
   （无主键表本来就没有任何工具能保证 exactly-once，业界一致）。
3. 半成品表清理依赖「目标表归任务所有」的假设（FULL_ONLY/FULL+增量的
   回放阶段均成立）。

## 5. 已否掉的替代路线

| 路线 | 否掉原因 |
|-|
| 增量快照（signal 触发的 currentChunkId/chunk 窗口那套）做全量主体 | 机制挪用：它的设计意图是**增量运行期间加表**。要引 signal 表、扛窗口去重语义、还得不到 initial 快照的点位一致性，为一个全量场景背这些不值得 |
| 自研 SELECT 导出 + 按表 checkpoint（DRS/DMS 的做法） | 正确但改动面最大：导出、分片、位点管理全自建。作为后续演进方向保留，第一期不选 |
| 只换 `table.include.list` 收窄范围 | FULL+增量场景会漏已完成表的后续 binlog（2.4） |
| 自定义 Snapshotter 之外的嵌入式 hack（子类化 task/source 替换快照循环） | 绑死 Debezium 内部类实现细节，升级要跟着适配；Snapshotter SPI 能解决的问题不走这层 |

## 6. 改动点清单

1. **引擎**：`ResumableInitialSnapshotter`（继承 `InitialSnapshotter`，覆写
   一个布尔）+ ServiceLoader 注册；snapshot.mode 切到 custom。
2. **回放**：全量回放链路由裸 INSERT 改为按主键 upsert
   （PG：`ON CONFLICT (pk) DO UPDATE`）。
3. **checkpoint**：`task_full_checkpoint` 表 DDL + `SnapshotNotifications`
   解析 `TABLE_SCAN_COMPLETED`/`DATA_COLLECTIONS_RESOLVED`。
4. **装配**：`DebeziumCdcEngineFactory.build()` 先查 checkpoint，有 done 集合则
   追加 `snapshot.include.collection.list`（剩余表）。
5. **协调器**：任务重启路径识别「全量崩溃续传」→ 半成品表 TRUNCATE → 带
   P0 重启；退化路径（checkpoint 丢失 / binlog 已 purge）= 清空目标全量重来。
6. **测试**：framework 新增用例——全量任务中途杀引擎/重启，验证剩余表续传、
   行数与 hash 对账一致。

## 7. 一句话总结

> 先定点 P0 再导数是 Debezium 的原生保证；续传空洞只在「重启换新起点」时出现，
> 而换不换起点是 Snapshotter SPI 一个布尔说了算。把这个布尔扳过来，配上自建
> checkpoint 表（通知通道维护）+ `snapshot.include.collection.list` 收窄重拍范围
> + 回放 upsert 幂等，全量迁移即可按表断点续传，且不依赖任何 Debezium 内部实现。
