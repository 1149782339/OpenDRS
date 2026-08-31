/** Mirrors opendrs-service JSON (camelCase). Do not invent fields. */

export type DbType = 'MYSQL' | 'POSTGRESQL' | 'ORACLE'

export type MigrationMode = 'FULL_ONLY' | 'INCREMENTAL_ONLY' | 'FULL_AND_INCREMENTAL'

export type JobPhase =
  | 'CREATED'
  | 'PRECHECKING'
  | 'PRECHECKED'
  | 'SCHEMA_SNAPSHOT'
  | 'FULL'
  | 'INCREMENTAL'

export type JobState = 'STARTING' | 'RUNNING' | 'STOPPING' | 'STOPPED' | 'FAILED'

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

export interface SchemaObject {
  schema: string
  tables?: string[] | null
  allTables?: boolean | null
  excludeTables?: string[] | null
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

export interface TableSelection {
  objects: SchemaObject[]
  mappings?: TableMappings | null
}

export interface MigrationOptions {
  fullDumpParallelism?: number | null
  batchSize?: number | null
  databaseServerId?: number | null
}

export interface CreateMigrationTaskRequest {
  name: string
  mode: MigrationMode
  source: ConnectionInfo
  target: ConnectionInfo
  tables: TableSelection
  options?: MigrationOptions | null
}

export interface SourceTargetType {
  type: DbType
}

export interface MigrationTaskSummary {
  id: number
  name: string
  mode: MigrationMode
  jobPhase: JobPhase
  jobState: JobState | null
  source: SourceTargetType
  target: SourceTargetType
  createdAt: string
}

export interface MigrationTaskResponse {
  id: number
  name: string
  mode: MigrationMode
  source: ConnectionInfo
  target: ConnectionInfo
  tables: TableSelection
  options: MigrationOptions
  jobPhase: JobPhase
  jobState: JobState | null
  createdAt: string
  updatedAt: string
}

export interface MigrationProgress {
  tablesTotal: number
  tablesDone: number
  rowsDone: number
  lagMs: number | null
}

export interface MigrationOffset {
  scn: string | null
  gtid: string | null
}

export interface MigrationStatusResponse {
  id: number
  jobPhase: JobPhase
  jobState: JobState | null
  progress: MigrationProgress
  offset: MigrationOffset
  error: string | null
}

export interface TableRef {
  schema: string
  table: string
}

export interface CheckResult {
  ok: boolean
  name: string
  message: string
  table: TableRef | null
}

export interface MigrationPrecheckResponse {
  ok: boolean
  jobPhase: JobPhase
  jobState: JobState | null
  results: CheckResult[]
  sourceResults?: CheckResult[]
  targetResults?: CheckResult[]
}

export const DEFAULT_PORTS: Record<DbType, number> = {
  MYSQL: 3306,
  POSTGRESQL: 5432,
  ORACLE: 1521,
}

export const ACTIVE_JOB_STATES: JobState[] = ['STARTING', 'RUNNING', 'STOPPING']

export function isActiveJobState(state: JobState | null | undefined): boolean {
  return state != null && ACTIVE_JOB_STATES.includes(state)
}

export function isPrecheckFinished(result: MigrationPrecheckResponse): boolean {
  if (result.jobState === 'FAILED') {
    return true
  }
  return result.jobPhase !== 'CREATED' && result.jobPhase !== 'PRECHECKING'
}

export function isPrecheckRunning(result: MigrationPrecheckResponse | null | undefined): boolean {
  return result != null && result.jobPhase === 'PRECHECKING' && result.jobState == null
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
