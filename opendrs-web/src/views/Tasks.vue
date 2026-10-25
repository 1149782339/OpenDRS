<script setup lang="ts">
import { apiErrorMessage } from '@/api/http'
import { deleteTask, listTasks, startTask, stopTask, terminateTask } from '@/api/task'
import { type MigrationTaskSummary } from '@/types/api'
import { formatDateTime } from '@/utils/datetime'
import {
  canDeleteTask,
  canPauseTask,
  canStartTask,
  canTerminateTask,
  DASH,
  dbLabel,
  isLiveTask,
  modeLabel,
  phaseOf,
  stateOf,
  type StatusView,
} from '@/utils/taskStatus'
import { CircleCheck, CircleClose, Loading } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

const { t, locale } = useI18n()
const router = useRouter()
const loading = ref(false)
const rows = ref<MigrationTaskSummary[]>([])
const actionId = ref<number | null>(null)
const actionKind = ref<'start' | 'pause' | 'terminate' | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null

/** start() 一个入口三种文案：未启动=启动、已暂停=恢复、失败=重启。 */
function startLabel(row: MigrationTaskSummary): string {
  if (row.state === 'STOPPED') return t('task.resume')
  if (row.state === 'FAILED') return t('task.restart')
  return t('task.start')
}

interface TaskRow extends MigrationTaskSummary {
  status: StatusView
  phaseLabel: string
  startable: boolean
  pausable: boolean
  terminatable: boolean
  deletable: boolean
  sourceLabel: string
  targetLabel: string
  modeLabel: string
  createdLabel: string
}

const viewRows = computed<TaskRow[]>(() => {
  // 语言切换时重算所有派生标签（stateOf/phaseOf/dbLabel/modeLabel 内部经 i18n 翻译）
  void locale.value
  return rows.value.map((row) => ({
    ...row,
    status: stateOf(row),
    phaseLabel: phaseOf(row),
    startable: canStartTask(row),
    pausable: canPauseTask(row),
    terminatable: canTerminateTask(row),
    deletable: canDeleteTask(row),
    sourceLabel: row.source?.type ? dbLabel(row.source.type) : DASH,
    targetLabel: row.target?.type ? dbLabel(row.target.type) : DASH,
    modeLabel: modeLabel(row.mode),
    createdLabel: formatDateTime(row.createdAt),
  }))
})

/** 有任务在跑就 2s 静默刷新，全静止就停掉，避免列表上的转圈图标不动。 */
function syncPolling() {
  const live = rows.value.some(isLiveTask)
  if (live && pollTimer == null) {
    pollTimer = setInterval(() => {
      void load(true)
    }, 2000)
  } else if (!live && pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    rows.value = await listTasks()
  } catch (error) {
    if (!silent) ElMessage.error(apiErrorMessage(error))
  } finally {
    if (!silent) loading.value = false
    syncPolling()
  }
}

function openRow(row: TaskRow) {
  if (row.phase === 'PRECHECKING') {
    void router.push({ path: '/tasks/create', query: { taskId: String(row.id), step: '3' } })
    return
  }
  if (row.phase === 'PRECHECKED') {
    void router.push({ path: '/tasks/create', query: { taskId: String(row.id), step: '4' } })
    return
  }
  void router.push(`/tasks/${row.id}`)
}

async function onStart(row: TaskRow) {
  actionId.value = row.id
  actionKind.value = 'start'
  try {
    await startTask(row.id)
    ElMessage.success(t('task.started'))
    await load()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    actionId.value = null
    actionKind.value = null
  }
}

/** 暂停：stop → STOPPING(intent PAUSE) → 已暂停，可恢复。 */
async function onPause(row: TaskRow) {
  actionId.value = row.id
  actionKind.value = 'pause'
  try {
    await stopTask(row.id)
    ElMessage.success(t('task.paused'))
    await load()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    actionId.value = null
    actionKind.value = null
  }
}

/** 结束：terminate → 落地 TERMINATED（运行中先经过 结束中），终态、可删除。 */
async function onTerminate(row: TaskRow) {
  try {
    await ElMessageBox.confirm(
      t('task.terminateConfirm', { name: row.name }),
      t('task.terminateTitle'),
      { type: 'warning', confirmButtonText: t('task.terminate'), cancelButtonText: t('common.cancel') },
    )
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    throw error
  }
  actionId.value = row.id
  actionKind.value = 'terminate'
  try {
    await terminateTask(row.id)
    ElMessage.success(t('task.terminated'))
    await load()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    actionId.value = null
    actionKind.value = null
  }
}

async function onDelete(row: TaskRow) {
  try {
    await ElMessageBox.confirm(t('task.deleteConfirm', { name: row.name }), t('task.deleteTitle'), {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
    })
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    throw error
  }
  try {
    await deleteTask(row.id)
    ElMessage.success(t('task.deleted'))
    await load()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  }
}

onMounted(() => load())

onUnmounted(() => {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<template>
  <div>
    <div class="page-toolbar">
      <h2>{{ t('task.title') }}</h2>
      <div>
        <el-button @click="load()">{{ t('common.refresh') }}</el-button>
        <el-button type="primary" @click="router.push('/tasks/create')">{{ t('task.new') }}</el-button>
      </div>
    </div>
    <el-table
      v-loading="loading"
      :data="viewRows"
      border
      stripe
      row-key="id"
      class="task-table"
      @row-click="openRow"
    >
      <el-table-column :label="t('task.nameId')" min-width="200">
        <template #default="{ row }: { row: TaskRow }">
          <div class="task-name" :title="row.name">{{ row.name }}</div>
          <div class="task-uuid" :title="row.publicId">{{ row.publicId.slice(0, 19) }}…</div>
        </template>
      </el-table-column>
      <el-table-column :label="t('task.status')" width="110">
        <template #default="{ row }">
          <span class="status" :class="`status-${row.status.tone}`">
            <el-icon v-if="row.status.tone === 'running'" class="is-loading"><Loading /></el-icon>
            <el-icon v-else-if="row.status.tone === 'done'"><CircleCheck /></el-icon>
            <el-icon v-else-if="row.status.tone === 'failed'"><CircleClose /></el-icon>
            <span>{{ row.status.label }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="t('task.phase')" width="140">
        <template #default="{ row }">{{ row.phaseLabel }}</template>
      </el-table-column>
      <el-table-column :label="t('task.sourceEngine')" width="110">
        <template #default="{ row }">{{ row.sourceLabel }}</template>
      </el-table-column>
      <el-table-column :label="t('task.targetEngine')" width="110">
        <template #default="{ row }">{{ row.targetLabel }}</template>
      </el-table-column>
      <el-table-column :label="t('task.mode')" width="110">
        <template #default="{ row }">{{ row.modeLabel }}</template>
      </el-table-column>
      <el-table-column :label="t('task.createdAt')" width="180">
        <template #default="{ row }">{{ row.createdLabel }}</template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="240" fixed="right">
        <template #default="{ row }">
          <div class="row-actions" @click.stop>
            <el-button
              type="success"
              link
              :disabled="!row.startable"
              :loading="actionId === row.id && actionKind === 'start'"
              @click="onStart(row)"
            >
              {{ startLabel(row) }}
            </el-button>
            <el-button
              type="primary"
              link
              :disabled="!row.pausable"
              :loading="actionId === row.id && actionKind === 'pause'"
              @click="onPause(row)"
            >
              {{ t('task.pause') }}
            </el-button>
            <el-button
              type="warning"
              link
              :disabled="!row.terminatable"
              :loading="actionId === row.id && actionKind === 'terminate'"
              @click="onTerminate(row)"
            >
              {{ t('task.terminate') }}
            </el-button>
            <el-button type="danger" link :disabled="!row.deletable" @click="onDelete(row)">
              {{ t('common.delete') }}
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.task-table :deep(.el-table__row) {
  cursor: pointer;
}

.task-name {
  font-weight: 500;
  line-height: 20px;
}

.task-uuid {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 18px;
}

.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.status .el-icon {
  font-size: 15px;
}

.status-running {
  color: #409eff;
}

.status-done {
  color: #67c23a;
}

.status-failed {
  color: #f56c6c;
}

.status-none {
  color: var(--el-text-color-placeholder);
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.row-actions .el-button + .el-button {
  margin-left: 0;
}

.disabled-wrap {
  display: inline-flex;
}
</style>
