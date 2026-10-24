import type { DbType, MigrationPhase, TaskState, MigrationMode } from '@/types/api'

export const DASH = '—'

export const DB_LABELS: Record<DbType, string> = {
  MYSQL: 'MySQL',
  POSTGRESQL: 'PostgreSQL',
  ORACLE: 'Oracle',
}

export const MODE_LABELS: Record<MigrationMode, string> = {
  FULL_ONLY: '全量迁移',
  INCREMENTAL_ONLY: '增量迁移',
  FULL_AND_INCREMENTAL: '全量+增量',
}

/** 只有 state=运行中 时才把 phase 当作对外状态，其余阶段不单独成词。 */
export const RUNNING_PHASES: Partial<Record<MigrationPhase, string>> = {
  SCHEMA_MIGRATION: '结构迁移',
  FULL: '全量迁移',
  INCREMENTAL: '增量迁移',
}

/** 详情页把状态和阶段分开写，所以每个阶段都要有一个名字。 */
export const PHASE_LABELS: Record<MigrationPhase, string> = {
  CREATED: '待预检查',
  PRECHECKING: '预检查中',
  PRECHECKED: '预检查通过',
  SCHEMA_MIGRATION: '结构迁移',
  FULL: '全量迁移',
  INCREMENTAL: '增量迁移',
}

/** 状态列文案：状态与阶段拆列后，状态只由 state（+stopIntent）决定。 */
export const STATE_LABELS: Record<TaskState, string> = {
  STARTING: '启动中',
  RUNNING: '运行中',
  STOPPING: '暂停中',
  TERMINATING: '结束中',
  STOPPED: '已暂停',
  COMPLETED: '已结束',
  FAILED: '失败',
  TERMINATED: '已结束',
}

export type StatusTone = 'running' | 'done' | 'failed' | 'none'

export interface StatusView {
  tone: StatusTone
  label: string
}

/** 状态列输入：state + phase（判断启动条件）。 */
export interface TaskStatusInput {
  phase: MigrationPhase
  state?: TaskState | null
}

export function stateTone(state: TaskState | null | undefined): StatusTone {
  if (
    state === 'STARTING' ||
    state === 'RUNNING' ||
    state === 'STOPPING' ||
    state === 'TERMINATING'
  )
    return 'running'
  if (state === 'STOPPED' || state === 'COMPLETED' || state === 'TERMINATED') return 'done'
  if (state === 'FAILED') return 'failed'
  return 'none'
}

/** 还在动：需要轮询。预检查没有 state，靠阶段判断。 */
export function isLiveTask(row: TaskStatusInput): boolean {
  return (
    row.state === 'STARTING' ||
    row.state === 'RUNNING' ||
    row.state === 'STOPPING' ||
    row.state === 'TERMINATING' ||
    (row.phase === 'PRECHECKING' && row.state == null)
  )
}

/**
 * 状态列：未启动 / 启动中 / 运行中 / 暂停中 / 结束中 / 已暂停 / 失败 / 已结束。
 * 暂停中=STOPPING、结束中=TERMINATING，是独立枚举值；state 为空一律「未启动」。
 */
export function stateOf(row: TaskStatusInput): StatusView {
  const state = row.state
  if (state == null) return { tone: 'none', label: '未启动' }
  return { tone: stateTone(state), label: STATE_LABELS[state] }
}

/** 阶段列：六个阶段恒有值（phase  NOT NULL），与状态列拆开各说各话。 */
export function phaseOf(row: TaskStatusInput): string {
  return PHASE_LABELS[row.phase] ?? DASH
}

/** 启动可用：预检查通过且未启动（列表直启）；STOPPED=恢复、FAILED=重启也走 start()。 */
export function canStartTask(row: TaskStatusInput): boolean {
  if (row.state === 'STOPPED' || row.state === 'FAILED') return true
  return row.phase === 'PRECHECKED' && row.state == null
}

/** 暂停可用：仅增量迁移的运行中任务（stop → STOPPING 暂停中 → STOPPED 已暂停）。 */
export function canPauseTask(row: TaskStatusInput): boolean {
  return row.state === 'RUNNING' && row.phase === 'INCREMENTAL'
}

/** 结束可用：除终态（TERMINATED/COMPLETED）与纯结束中外的所有状态。 */
export function canTerminateTask(row: TaskStatusInput): boolean {
  const state = row.state
  if (state == null) return true
  if (state === 'TERMINATED' || state === 'COMPLETED') return false
  return true // STARTING/RUNNING/STOPPING(含暂停中)/STOPPED/FAILED 都可结束
}

/** 删除可用：只有 TERMINATED（COMPLETED 目前后端不可达，到了再放开）。 */
export function canDeleteTask(row: TaskStatusInput): boolean {
  return row.state === 'TERMINATED'
}

/**
 * @deprecated 兼容旧合并口径（状态列被阶段名顶替）。新代码用 stateOf/phaseOf 拆列。
 * 运行中的标签仍按阶段替换，仅 TaskDetail 旧逻辑引用。
 */
export function statusOf(row: TaskStatusInput): StatusView {
  const state = row.state
  if (state === 'RUNNING') {
    const label = RUNNING_PHASES[row.phase]
    return label ? { tone: 'running', label } : { tone: 'none', label: DASH }
  }
  return stateOf(row)
}
