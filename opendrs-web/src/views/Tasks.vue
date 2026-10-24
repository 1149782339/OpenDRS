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
  DB_LABELS,
  isLiveTask,
  MODE_LABELS,
  phaseOf,
  stateOf,
  type StatusView,
} from '@/utils/taskStatus'
import { CircleCheck, CircleClose, Loading } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const loading = ref(false)
const rows = ref<MigrationTaskSummary[]>([])
const actionId = ref<number | null>(null)
const actionKind = ref<'start' | 'pause' | 'terminate' | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null

/** start() 一个入口三种文案：未启动=启动、已暂停=恢复、失败=重启。 */
function startLabel(row: MigrationTaskSummary): string {
  if (row.state === 'STOPPED') return '恢复'
  if (row.state === 'FAILED') return '重启'
  return '启动'
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

const viewRows = computed<TaskRow[]>(() =>
  rows.value.map((row) => ({
    ...row,
    status: stateOf(row),
    phaseLabel: phaseOf(row),
    startable: canStartTask(row),
    pausable: canPauseTask(row),
    terminatable: canTerminateTask(row),
    deletable: canDeleteTask(row),
    sourceLabel: DB_LABELS[row.source?.type] ?? DASH,
    targetLabel: DB_LABELS[row.target?.type] ?? DASH,
    modeLabel: MODE_LABELS[row.mode] ?? row.mode,
    createdLabel: formatDateTime(row.createdAt),
  })),
)

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
    ElMessage.success('已启动')
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
    ElMessage.success('已暂停')
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
      `确认结束任务「${row.name}」？结束后任务不可再启动，仅可删除记录。`,
      '结束任务',
      { type: 'warning', confirmButtonText: '结束', cancelButtonText: '取消' },
    )
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    throw error
  }
  actionId.value = row.id
  actionKind.value = 'terminate'
  try {
    await terminateTask(row.id)
    ElMessage.success('已结束')
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
    await ElMessageBox.confirm(`确认删除任务「${row.name}」的记录？该操作不可恢复。`, '删除任务', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    throw error
  }
  try {
    await deleteTask(row.id)
    ElMessage.success('已删除')
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
      <h2>任务</h2>
      <div>
        <el-button @click="load()">刷新</el-button>
        <el-button type="primary" @click="router.push('/tasks/create')">新建</el-button>
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
      <el-table-column label="名称/ID" min-width="200">
        <template #default="{ row }: { row: TaskRow }">
          <div class="task-name" :title="row.name">{{ row.name }}</div>
          <div class="task-uuid" :title="row.publicId">{{ row.publicId.slice(0, 19) }}…</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <span class="status" :class="`status-${row.status.tone}`">
            <el-icon v-if="row.status.tone === 'running'" class="is-loading"><Loading /></el-icon>
            <el-icon v-else-if="row.status.tone === 'done'"><CircleCheck /></el-icon>
            <el-icon v-else-if="row.status.tone === 'failed'"><CircleClose /></el-icon>
            <span>{{ row.status.label }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="迁移阶段" width="110">
        <template #default="{ row }">{{ row.phaseLabel }}</template>
      </el-table-column>
      <el-table-column label="源库引擎" min-width="110">
        <template #default="{ row }">{{ row.sourceLabel }}</template>
      </el-table-column>
      <el-table-column label="目标库引擎" min-width="110">
        <template #default="{ row }">{{ row.targetLabel }}</template>
      </el-table-column>
      <el-table-column label="迁移模式" width="110">
        <template #default="{ row }">{{ row.modeLabel }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ row.createdLabel }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
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
              暂停
            </el-button>
            <el-button
              type="warning"
              link
              :disabled="!row.terminatable"
              :loading="actionId === row.id && actionKind === 'terminate'"
              @click="onTerminate(row)"
            >
              结束
            </el-button>
            <el-button type="danger" link :disabled="!row.deletable" @click="onDelete(row)">
              删除
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
