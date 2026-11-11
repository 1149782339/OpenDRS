<script setup lang="ts">
import { listDataCheckTasks, startDataCheckTask, stopDataCheckTask } from '@/api/datacheck'
import { apiErrorMessage } from '@/api/http'
import type { TaskState } from '@/types/api'
import type { DataCheckTaskResponse } from '@/types/datacheck'
import {
  canStartDataCheck,
  canStopDataCheck,
  dataCheckModeLabel,
  dataCheckStateOf,
  isLiveDataCheck,
} from '@/utils/datacheck'
import { formatDateTime } from '@/utils/datetime'
import { DASH } from '@/utils/taskStatus'
import {
  CircleCheckFilled,
  CircleCloseFilled,
  Loading,
  Refresh,
  RemoveFilled,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

const props = defineProps<{ taskId: number }>()

const { t } = useI18n()
const router = useRouter()

const tasks = ref<DataCheckTaskResponse[]>([])
const loading = ref(false)

// ------------------------------------------------------------------
// List + polling
// ------------------------------------------------------------------

const hasLiveTask = computed(() => tasks.value.some(isLiveDataCheck))

let pollTimer: ReturnType<typeof setInterval> | null = null

async function loadTasks(silent = false) {
  if (!silent) loading.value = true
  try {
    tasks.value = await listDataCheckTasks(props.taskId)
  } catch (error) {
    if (!silent) ElMessage.error(apiErrorMessage(error))
  } finally {
    if (!silent) loading.value = false
    syncPolling()
  }
}

function syncPolling() {
  if (hasLiveTask.value && pollTimer == null) {
    pollTimer = setInterval(() => {
      void loadTasks(true)
    }, 3000)
  } else if (!hasLiveTask.value && pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function openCreate() {
  void router.push({ name: 'datacheck-create', params: { id: props.taskId } })
}

/** 任务名和「查看详情」都进独立详情页，不再用抽屉承载报表。 */
function openDetail(task: DataCheckTaskResponse) {
  void router.push({
    name: 'datacheck-detail',
    params: { id: props.taskId, checkId: task.id },
  })
}

// ------------------------------------------------------------------
// Display helpers
// ------------------------------------------------------------------

function isTerminal(state: TaskState | null): boolean {
  return state === 'COMPLETED' || state === 'FAILED' || state === 'STOPPED' || state === 'TERMINATED'
}

const startingId = ref<number | null>(null)
const stoppingId = ref<number | null>(null)

async function startTask(task: DataCheckTaskResponse) {
  startingId.value = task.id
  try {
    await startDataCheckTask(task.id)
    await loadTasks()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    startingId.value = null
  }
}

async function stopTask(task: DataCheckTaskResponse) {
  if (!task.stoppable) {
    ElMessage.warning(t('datacheck.stopFullDisabled'))
    return
  }
  stoppingId.value = task.id
  try {
    await stopDataCheckTask(task.id)
    await loadTasks()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    stoppingId.value = null
  }
}

// ------------------------------------------------------------------
// Lifecycle
// ------------------------------------------------------------------

watch(
  () => props.taskId,
  () => {
    void loadTasks()
  },
)

onMounted(() => {
  void loadTasks()
})

onUnmounted(() => {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<template>
  <section class="datacheck">
    <p class="notice">{{ t('datacheck.notice') }}</p>

    <div class="toolbar">
      <el-button @click="openCreate">{{ t('datacheck.create') }}</el-button>
      <el-button class="refresh" :icon="Refresh" circle :loading="loading" @click="loadTasks()" />
    </div>

    <el-table v-loading="loading" :data="tasks" class="dc-table" :empty-text="t('datacheck.empty')">
      <el-table-column :label="t('datacheck.nameColumn')" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span
            class="name-link"
            role="link"
            tabindex="0"
            @click="openDetail(row)"
            @keydown.enter="openDetail(row)"
            @keydown.space.prevent="openDetail(row)"
          >
            {{ row.name }}
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="t('datacheck.compareType')" min-width="160">
        <template #default="{ row }">
          <span>{{ dataCheckModeLabel(row.mode) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('datacheck.startedAt')" width="190">
        <template #default="{ row }">
          <span class="time">{{ formatDateTime(row.createdAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('datacheck.finishedAt')" width="190">
        <template #default="{ row }">
          <span class="time">{{ isTerminal(row.state) ? formatDateTime(row.updatedAt) : DASH }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('datacheck.status')" width="130">
        <template #default="{ row }">
          <span class="status" :class="`status-${dataCheckStateOf(row).tone}`">
            <el-icon v-if="dataCheckStateOf(row).tone === 'running'" class="is-loading"><Loading /></el-icon>
            <el-icon v-else-if="dataCheckStateOf(row).tone === 'failed'"><CircleCloseFilled /></el-icon>
            <el-icon v-else-if="dataCheckStateOf(row).tone === 'done'"><CircleCheckFilled /></el-icon>
            <el-icon v-else><RemoveFilled /></el-icon>
            <span>{{ dataCheckStateOf(row).label }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="t('datacheck.report')" width="160">
        <template #default>
          <span class="report-cell">
            <el-icon><RemoveFilled /></el-icon>
            <span>{{ t('datacheck.none') }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" min-width="200">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">
            {{ t('datacheck.viewDetail') }}
          </el-button>
          <el-button
            v-if="canStartDataCheck(row)"
            link
            type="primary"
            size="small"
            :loading="startingId === row.id"
            @click="startTask(row)"
          >
            {{ t('datacheck.start') }}
          </el-button>
          <el-tooltip
            v-if="canStopDataCheck(row)"
            :disabled="row.stoppable !== false"
            :content="t('datacheck.stopFullDisabled')"
            placement="top"
          >
            <span>
              <el-button
                link
                type="primary"
                size="small"
                :disabled="row.stoppable === false"
                :loading="stoppingId === row.id"
                @click="stopTask(row)"
              >
                {{ t('datacheck.stop') }}
              </el-button>
            </span>
          </el-tooltip>
          <el-tooltip :content="t('datacheck.notSupported')" placement="top">
            <span class="action-disabled">{{ t('datacheck.exportReport') }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.datacheck {
  margin-top: 22px;
}

.notice {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 10px;
}

.refresh {
  margin-left: auto;
}

.dc-table :deep(.el-table__cell) {
  padding: 8px 0;
}

.name-link {
  color: var(--el-color-primary);
  cursor: pointer;
}

.name-link:hover,
.name-link:focus-visible {
  text-decoration: underline;
}

.name-link:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 2px;
  border-radius: 2px;
}

.time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
}

.status-done {
  color: var(--el-color-success);
}

.status-running {
  color: var(--el-color-primary);
}

.status-failed {
  color: var(--el-color-danger);
}

.status-none {
  color: var(--el-text-color-secondary);
}

.report-cell {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.action-disabled {
  margin-left: 10px;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  cursor: not-allowed;
}
</style>
