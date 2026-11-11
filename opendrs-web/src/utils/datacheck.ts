import { i18n } from '@/i18n'
import type {
  DataCheckMode,
  DataCheckTableResult,
  DataCheckTaskResponse,
} from '@/types/datacheck'
import { DASH, stateTone, type StatusView } from '@/utils/taskStatus'

/** 校验任务状态：未启动时 state 为空，其余与迁移任务共用一套状态词。 */
export function dataCheckStateOf(task: DataCheckTaskResponse): StatusView {
  if (task.state == null) {
    return { tone: 'none', label: i18n.global.t('status.notStarted') }
  }
  return { tone: stateTone(task.state), label: i18n.global.t(`status.state.${task.state}`) }
}

/** 还在动：需要 3 秒轮询任务与表状态。 */
export function isLiveDataCheck(task: DataCheckTaskResponse): boolean {
  return (
    task.state === 'STARTING' ||
    task.state === 'RUNNING' ||
    task.state === 'STOPPING' ||
    task.state === 'TERMINATING'
  )
}

export function canStartDataCheck(task: DataCheckTaskResponse): boolean {
  return task.state == null || task.state === 'STOPPED' || task.state === 'FAILED'
}

export function canStopDataCheck(task: DataCheckTaskResponse): boolean {
  return task.state === 'RUNNING' || task.state === 'STARTING'
}

export function dataCheckModeLabel(mode: DataCheckMode): string {
  return i18n.global.t(`datacheck.mode.${mode}`)
}

/**
 * 表的阶段列：失败和“全量已完成”优先于 phase 本身，否则全量任务完成后
 * phase 还停在 FULL，看不出已经收尾。等待回放也是独立的一格。
 */
export function dataCheckPhaseLabel(row: DataCheckTableResult): string {
  if (row.status === 'FAILED') return i18n.global.t('datacheck.phase.FAILED')
  if (row.status === 'COMPLETED' && row.phase !== 'INCREMENTAL') {
    return i18n.global.t('datacheck.phase.COMPLETED')
  }
  if (row.waitingForReplay) return i18n.global.t('datacheck.phase.WAITING_REPLAY')
  return i18n.global.t(`datacheck.phase.${row.phase}`)
}

export function dataCheckLagText(row: DataCheckTableResult): string {
  if (row.waitingForReplay) return i18n.global.t('datacheck.phase.WAITING_REPLAY')
  if (row.phase !== 'INCREMENTAL' || row.lagMs == null) return DASH
  return i18n.global.t('datacheck.lagMs', { n: row.lagMs })
}

export function dataCheckFlagLabel(flag: number): string {
  if (flag === 0) return i18n.global.t('datacheck.detail.flags.different')
  if (flag === 1) return i18n.global.t('datacheck.detail.flags.sourceOnly')
  if (flag === 2) return i18n.global.t('datacheck.detail.flags.targetOnly')
  return String(flag)
}

/** 表状态只借三种色调，文案仍由 tableStatus.* 提供。 */
export function dataCheckTableTone(
  status: DataCheckTableResult['status'],
): 'running' | 'done' | 'failed' | 'none' {
  if (status === 'RUNNING') return 'running'
  if (status === 'COMPLETED') return 'done'
  if (status === 'FAILED') return 'failed'
  return 'none'
}
