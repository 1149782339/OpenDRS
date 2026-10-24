/** Mirrors opendrs-service JSON (camelCase). Do not invent fields. */

export type DbType = 'MYSQL' | 'POSTGRESQL' | 'ORACLE'

export type MigrationMode = 'FULL_ONLY' | 'INCREMENTAL_ONLY' | 'FULL_AND_INCREMENTAL'

export type MigrationPhase =
  | 'CREATED'
  | 'PRECHECKING'
  | 'PRECHECKED'
  | 'SCHEMA_MIGRATION'
  | 'FULL'
  | 'INCREMENTAL'

export type TaskState =
  | 'STARTING'
  | 'RUNNING'
  | 'STOPPING'
  | 'TERMINATING'
  | 'STOPPED'
  | 'COMPLETED'
  | 'FAILED'
  | 'TERMINATED'

export interface ConnectionInfo {
  type: DbType
  host: string
  port: number
  database: string
  username: string
  password: string
  extra?: Record<string, unknown> | null
}

export interface CreateConnectionRequest {
  name: string
  connection: ConnectionInfo
}

export interface ConnectionResponse {
  id: number
  name: string
  type: DbType
  host: string
  port: number
  database: string
  username: string
  password: string
  extra?: Record<string, unknown> | null
  createdAt: string
  updatedAt: string
}

export interface ConnectionTestResponse {
  ok: boolean
  latencyMs: number | null
}

/** 一段 schema.table。schema 在 MySQL 里是 database，在 PostgreSQL 里是 schema。 */
export interface SelectedTable {
  schema: string
  table: string
}

export interface SchemaMapping {
  source: string
  target: string
}

export interface TableMapping {
  sourceSchema: string
  sourceTable: string
  targetSchema: string
  targetTable: string
}

export interface TableMappings {
  schema?: SchemaMapping[] | null
  tables?: TableMapping[] | null
}

/**
 * 迁移对象选择（options.selection 内的扁平结构）。
 * 四个列表全空 = 全部迁移（源实例整个照搬，之后新建的库表也会跟上）；
 * 否则要么只填 schemas（整库），要么只填 tables（指定表），不能混用。
 */
export interface TableSelection {
  schemas: string[]
  tables: SelectedTable[]
  excludeSchemas: string[]
  excludeTables: SelectedTable[]
  mappings?: TableMappings | null
}

export type SyncTopology = 'ONE_WAY' | 'BIDIRECTIONAL'
export type SelectionMode = 'ALL' | 'SCHEMA' | 'TABLE'
export type FullObjectType = 'SCHEMA' | 'DATA' | 'INDEX'
export type ImportMode = 'COPY' | 'INSERT'
export type CharsetAdapt = 'AUTO' | 'KEEP'
export type ConflictPolicy = 'OVERWRITE' | 'ERROR' | 'IGNORE'

export interface OptionsSelection extends TableSelection {
  /** 显式的选择模式，替代「哪个列表非空」的隐式约定。 */
  mode: SelectionMode
}

/** 全量设置（高级设置页）。 */
export interface FullSettings {
  objectTypes: FullObjectType[]
  exportConcurrency: number
  importConcurrency: number
  importMode: ImportMode
  chunkSize: number
  charsetAdapt: CharsetAdapt
}

/** 增量回放设置（高级设置页）。 */
export interface IncrementalSettings {
  replayConcurrency: number
  conflictPolicy: ConflictPolicy
}

/** 统一任务配置，整份持久化在 migration_task.options_json。 */
export interface MigrationOptions {
  topology: SyncTopology
  selection: OptionsSelection
  full: FullSettings
  incremental: IncrementalSettings
}

export type CreateMigrationTaskRequest = {
  name: string
  mode: MigrationMode
  sourceConnectionId: number
  targetConnectionId: number
  options?: MigrationOptions | null
}

export interface SourceTargetType {
  type: DbType
}

export interface MigrationTaskSummary {
  id: number
  /** 对外业务 UUID（migration_task.public_id）；id 是内部自增主键。 */
  publicId: string
  name: string
  mode: MigrationMode
  phase: MigrationPhase
  state: TaskState | null
  source: SourceTargetType
  target: SourceTargetType
  createdAt: string
  /** 目标库落后源库多少毫秒；只有 RUNNING + INCREMENTAL 才有值。 */
  lagMs: number | null
}

export interface MigrationTaskResponse {
  id: number
  publicId: string
  name: string
  mode: MigrationMode
  source: ConnectionInfo
  target: ConnectionInfo
  options: MigrationOptions
  phase: MigrationPhase
  state: TaskState | null
  createdAt: string
  updatedAt: string
}

/** 单个方向的六个指标；null 表示该时刻无采样（如任务未运行），图表上画成断点。 */
export interface ReplayDirectionMetrics {
  /** 每秒从源库抓取的事件数 */
  captured: number | null
  /** 每秒回放的事务数 */
  applied: number | null
  /** 每秒回放的行数（一个 batch 内 N 行 addBatch 计 N，不按 executeBatch 次数计） */
  appliedRows: number | null
  /** 单事务平均执行时间（毫秒） */
  execMs: number | null
  /** 单事务平均提交时间（毫秒） */
  commitMs: number | null
  /** 回放延迟（秒） */
  lag: number | null
}

/** 每秒一个点；null 表示该时刻无采样（如任务未运行），图表上画成断点。 */
export interface ReplayMetricsPoint {
  /** Unix 秒 */
  t: number
  /** 正向（写目标库）pipeline 的指标，始终存在 */
  forward: ReplayDirectionMetrics
  /** 反向（写源库）pipeline 的指标，仅双向任务存在 */
  reverse: ReplayDirectionMetrics | null
}

export interface TaskReplayMetrics {
  points: ReplayMetricsPoint[]
}

export interface MigrationProgress {
  lagMs: number | null
}

export interface MigrationOffset {
  /** 源位点（实时查询，file:pos），不可达为 null */
  source: string | null
  /** 抓取位点（Debezium 已读到） */
  captured: string | null
  /** 回放位点（目标库已提交） */
  applied: string | null
  /** 位点最近更新时间 yyyy-MM-dd HH:mm:ss */
  updatedAt: string | null
}

export interface MigrationStatusResponse {
  id: number
  phase: MigrationPhase
  state: TaskState | null
  progress: MigrationProgress
  offset: MigrationOffset
  /** 反向同步位点（双向同步任务才有），单向任务为 null */
  reverse: MigrationOffset | null
  error: string | null
}

/** 日志级别。筛选在服务端做，所以它同时是查询参数。 */
export type MigrationLogLevel = 'INFO' | 'WARNING' | 'ERROR'

export interface MigrationLogItem {
  id: number
  loggedAt: string
  level: MigrationLogLevel
  message: string
}

/** total 是「符合级别筛选」的条数，不是任务的全部日志条数。 */
export interface MigrationLogPage {
  items: MigrationLogItem[]
  total: number
  page: number
  size: number
}

export interface TableRef {
  schema: string
  table: string
}/** 检查项分组，与引擎 CheckGroup 的枚举名一一对应；顺序由后端 order 决定。 */
export type CheckGroup = 'PARAMETER' | 'INSTANCE' | 'PERMISSION' | 'VERSION' | 'NETWORK' | 'OBJECT'

/** 检查项涉及哪一端：字符集、时区这类检查同时读源库和目标库。 */
export type CheckSide = 'SOURCE' | 'TARGET' | 'BOTH'

export type CheckExecutionState = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'ERROR' | 'BLOCKED'

/** 判定结果与执行状态是两根独立的轴：NOT_APPLICABLE 不是通过，UNKNOWN 更不是。 */
export type CheckVerdict = 'PASS' | 'FAIL' | 'NOT_APPLICABLE' | 'UNKNOWN'

export type CheckSeverity = 'BLOCKER' | 'WARNING' | 'INFO'

export interface PrecheckItem {
  id: string
  group: CheckGroup
  title: string
  side: CheckSide
  order: number
  state: CheckExecutionState
  verdict: CheckVerdict
  severity: CheckSeverity
  message: string | null
  actual: string | null
  expected: string | null
  advice: string | null
  details: string[]
}

export interface MigrationPrecheckResponse {
  ok: boolean
  phase: MigrationPhase
  state: TaskState | null
  profileId: string | null
  /** 从第一次发布起就是完整清单，逐项原地翻状态。 */
  items: PrecheckItem[]
  runningId: string | null
}

export const DEFAULT_PORTS: Record<DbType, number> = {
  MYSQL: 3306,
  POSTGRESQL: 5432,
  ORACLE: 1521,
}

export const ACTIVE_STATES: TaskState[] = ['STARTING', 'RUNNING', 'STOPPING']

export function isActiveState(state: TaskState | null | undefined): boolean {
  return state != null && ACTIVE_STATES.includes(state)
}

export function isPrecheckFinished(result: MigrationPrecheckResponse): boolean {
  if (result.state === 'FAILED') {
    return true
  }
  return result.phase !== 'CREATED' && result.phase !== 'PRECHECKING'
}

export function isPrecheckRunning(result: MigrationPrecheckResponse | null | undefined): boolean {
  return result != null && result.phase === 'PRECHECKING' && result.state == null
}

export function emptyConnection(type: DbType = 'MYSQL'): ConnectionInfo {
  return {
    type,
    host: '',
    port: DEFAULT_PORTS[type],
    database: '',
    username: '',
    password: '',
    extra: undefined,
  }
}

export function copySavedConnection(saved: ConnectionResponse): ConnectionInfo {
  return {
    type: saved.type,
    host: saved.host,
    port: saved.port,
    database: saved.database,
    username: saved.username,
    password: '',
    extra: saved.extra ?? undefined,
  }
}
