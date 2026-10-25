import type { DbType, MigrationPhase, TaskState, MigrationMode } from '@/types/api'
import { i18n } from '@/i18n'

export const DASH = '—'

/** 引擎名是产品名，不翻译；仍走字典便于统一兜底。 */
export function dbLabel(type: DbType): string {
  return i18n.global.t(`status.db.${type}`)
}

export function modeLabel(mode: MigrationMode): string {
  return i18n.global.t(`status.mode.${mode}`)
}

/** 详情页把状态和阶段分开写，所以每个阶段都要有一个名字。 */
export function phaseLabelOf(phase: MigrationPhase): string {
  return i18n.global.t(`status.phase.${phase}`)
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
 * 标签经 i18n 翻译，在 computed 里调用可随语言切换重算（t 内部读取响应式 locale）。
 */
export function stateOf(row: TaskStatusInput): StatusView {
  const state = row.state
  if (state == null) return { tone: 'none', label: i18n.global.t('status.notStarted') }
  return { tone: stateTone(state), label: i18n.global.t(`status.state.${state}`) }
}

/** 阶段列：六个阶段恒有值（phase  NOT NULL），与状态列拆开各说各话。 */
export function phaseOf(row: TaskStatusInput): string {
  return phaseLabelOf(row.phase) ?? DASH
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

/** 删除可用：TERMINATED（用户宣告结束）或 COMPLETED（全量任务的自然终态）。 */
export function canDeleteTask(row: TaskStatusInput): boolean {
  return row.state === 'TERMINATED' || row.state === 'COMPLETED'
}

/**
 * @deprecated 兼容旧合并口径（状态列被阶段名顶替）。新代码用 stateOf/phaseOf 拆列。
 * 仅历史引用保留；运行中的标签按阶段名替换。
 */
export function statusOf(row: TaskStatusInput): StatusView {
  const state = row.state
  if (state === 'RUNNING') {
    const label = phaseLabelOf(row.phase)
    return label ? { tone: 'running', label } : { tone: 'none', label: DASH }
  }
  return stateOf(row)
}
