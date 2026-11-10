<script setup lang="ts">
import {
  listDataCheckResults,
  listDataCheckTables,
  listDataCheckTasks,
  startDataCheckTask,
  stopDataCheckTask,
} from '@/api/datacheck'
import { apiErrorMessage } from '@/api/http'
import type { TaskState } from '@/types/api'
import type {
  DataCheckMode,
  DataCheckResultItem,
  DataCheckTableResult,
  DataCheckTaskResponse,
} from '@/types/datacheck'
import { formatDateTime } from '@/utils/datetime'
import { DASH, stateTone } from '@/utils/taskStatus'
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

const { t, locale } = useI18n()
const router = useRouter()

const PAGE_SIZE = 20

const tasks = ref<DataCheckTaskResponse[]>([])
const loading = ref(false)

// ------------------------------------------------------------------
// List + polling
// ------------------------------------------------------------------

const hasLiveTask = computed(() =>
  tasks.value.some(
    (task) =>
      task.state === 'STARTING' ||
      task.state === 'RUNNING' ||
      task.state === 'STOPPING' ||
      task.state === 'TERMINATING',
  ),
)

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
      if (reportVisible.value) {
        void loadTableResults()
      }
    }, 3000)
  } else if (!hasLiveTask.value && pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function openCreate() {
  void router.push({ name: 'datacheck-create', params: { id: props.taskId } })
}

// ------------------------------------------------------------------
// Display helpers
// ------------------------------------------------------------------

function modeLabel(mode: DataCheckMode): string {
  return t(`datacheck.mode.${mode}`)
}

function stateView(task: DataCheckTaskResponse): { tone: string; label: string } {
  void locale.value
  if (task.state == null) {
    return { tone: 'none', label: t('status.notStarted') }
  }
  return { tone: stateTone(task.state), label: t(`status.state.${task.state}`) }
}

function isTerminal(state: TaskState | null): boolean {
  return state === 'COMPLETED' || state === 'FAILED' || state === 'STOPPED' || state === 'TERMINATED'
}

function tableStatusLabel(status: string): string {
  return t(`datacheck.tableStatus.${status}`)
}

function tablePhaseLabel(row: DataCheckTableResult): string {
  if (row.status === 'FAILED') return t('datacheck.phase.FAILED')
  if (row.status === 'COMPLETED' && row.phase !== 'INCREMENTAL') {
    return t('datacheck.phase.COMPLETED')
  }
  if (row.waitingForReplay) return t('datacheck.phase.WAITING_REPLAY')
  return t(`datacheck.phase.${row.phase}`)
}

function formatLag(row: DataCheckTableResult): string {
  if (row.waitingForReplay) return t('datacheck.phase.WAITING_REPLAY')
  if (row.phase !== 'INCREMENTAL' || row.lagMs == null) return DASH
  return t('datacheck.lagMs', { n: row.lagMs })
}

function canStart(task: DataCheckTaskResponse): boolean {
  return task.state == null || task.state === 'STOPPED' || task.state === 'FAILED'
}

function canStop(task: DataCheckTaskResponse): boolean {
  return task.state === 'RUNNING' || task.state === 'STARTING'
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

function flagLabel(flag: number): string {
  if (flag === 0) return t('datacheck.reportDialog.flags.different')
  if (flag === 1) return t('datacheck.reportDialog.flags.sourceOnly')
  if (flag === 2) return t('datacheck.reportDialog.flags.targetOnly')
  return String(flag)
}

// ------------------------------------------------------------------
// Report drawer
// ------------------------------------------------------------------

const reportVisible = ref(false)
const reportTask = ref<DataCheckTaskResponse | null>(null)
const tableResults = ref<DataCheckTableResult[]>([])
const reportTableLoading = ref(false)
const currentTable = ref<DataCheckTableResult | null>(null)
const results = ref<DataCheckResultItem[]>([])
const resultTotal = ref(0)
const resultPage = ref(1)
const resultFlag = ref<number | null>(null)
const resultLoading = ref(false)

async function openReport(task: DataCheckTaskResponse) {
  reportTask.value = task
  currentTable.value = null
  results.value = []
  resultTotal.value = 0
  resultPage.value = 1
  resultFlag.value = null
  reportVisible.value = true
  await loadTableResults()
}

async function loadTableResults() {
  const task = reportTask.value
  if (!task) {
    return
  }
  reportTableLoading.value = true
  try {
    tableResults.value = await listDataCheckTables(task.id)
  } catch (error) {
    tableResults.value = []
    ElMessage.error(apiErrorMessage(error))
  } finally {
    reportTableLoading.value = false
  }
}

function selectTable(row: DataCheckTableResult | null) {
  currentTable.value = row
  resultPage.value = 1
  results.value = []
  resultTotal.value = 0
  if (row) {
    void loadResults()
  }
}

async function loadResults() {
  const task = reportTask.value
  const table = currentTable.value
  if (!task || !table) {
    return
  }
  resultLoading.value = true
  try {
    const page = await listDataCheckResults(task.id, {
      tableId: table.sourceTableId,
      flag: resultFlag.value,
      page: resultPage.value,
      size: PAGE_SIZE,
    })
    results.value = page.items
    resultTotal.value = page.total
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    resultLoading.value = false
  }
}

function onFlagChange() {
  resultPage.value = 1
  void loadResults()
}

function onResultPage(next: number) {
  resultPage.value = next
  void loadResults()
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
      <el-table-column prop="name" :label="t('datacheck.nameColumn')" min-width="180" show-overflow-tooltip />
      <el-table-column :label="t('datacheck.compareType')" min-width="160">
        <template #default="{ row }">
          <span>{{ modeLabel(row.mode) }}</span>
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
          <span class="status" :class="`status-${stateView(row).tone}`">
            <el-icon v-if="stateView(row).tone === 'running'" class="is-loading"><Loading /></el-icon>
            <el-icon v-else-if="stateView(row).tone === 'failed'"><CircleCloseFilled /></el-icon>
            <el-icon v-else-if="stateView(row).tone === 'done'"><CircleCheckFilled /></el-icon>
            <el-icon v-else><RemoveFilled /></el-icon>
            <span>{{ stateView(row).label }}</span>
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
      <el-table-column :label="t('common.actions')" min-width="180">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openReport(row)">
            {{ t('datacheck.viewReport') }}
          </el-button>
          <el-button
            v-if="canStart(row)"
            link
            type="primary"
            size="small"
            :loading="startingId === row.id"
            @click="startTask(row)"
          >
            {{ t('datacheck.start') }}
          </el-button>
          <el-tooltip
            v-if="canStop(row)"
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

    <!-- 对比报表 -->
    <el-drawer v-model="reportVisible" size="88%" :title="t('datacheck.reportDialog.title')">
      <div v-if="reportTask" class="report">
        <div class="report-title">
          <span>{{ reportTask.name }}</span>
          <span class="time">{{ formatDateTime(reportTask.createdAt) }}</span>
        </div>

        <el-table
          v-loading="reportTableLoading"
          :data="tableResults"
          class="report-table"
          highlight-current-row
          @current-change="selectTable"
        >
          <el-table-column prop="sourceTableId" :label="t('datacheck.reportDialog.sourceTable')" min-width="160" />
          <el-table-column prop="targetTableId" :label="t('datacheck.reportDialog.targetTable')" min-width="160" />
          <el-table-column :label="t('datacheck.reportDialog.phase')" width="130">
            <template #default="{ row }">{{ tablePhaseLabel(row) }}</template>
          </el-table-column>
          <el-table-column :label="t('datacheck.reportDialog.status')" width="100">
            <template #default="{ row }">{{ tableStatusLabel(row.status) }}</template>
          </el-table-column>
          <el-table-column :label="t('datacheck.reportDialog.rowsChecked')" width="110" align="right">
            <template #default="{ row }">{{ row.rowsChecked != null ? row.rowsChecked : DASH }}</template>
          </el-table-column>
          <el-table-column :label="t('datacheck.reportDialog.inconsistent')" width="110" align="right">
            <template #default="{ row }">{{ row.totalCount }}</template>
          </el-table-column>
          <el-table-column :label="t('datacheck.reportDialog.lag')" width="130">
            <template #default="{ row }">
              <span>{{ formatLag(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('datacheck.reportDialog.lastCheckAt')" width="190">
            <template #default="{ row }">{{ row.lastCheckAt ? formatDateTime(row.lastCheckAt) : DASH }}</template>
          </el-table-column>
          <el-table-column :label="t('datacheck.reportDialog.error')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.waitingForReplay">{{ t('datacheck.reportDialog.waitingHint') }}</span>
              <span v-else>{{ row.errorMessage || DASH }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="results-head">
          <h4>{{ t('datacheck.reportDialog.results') }}</h4>
          <el-select
            v-model="resultFlag"
            clearable
            size="small"
            class="flag-select"
            :placeholder="t('datacheck.reportDialog.flags.all')"
            :disabled="!currentTable"
            @change="onFlagChange"
          >
            <el-option :label="t('datacheck.reportDialog.flags.different')" :value="0" />
            <el-option :label="t('datacheck.reportDialog.flags.sourceOnly')" :value="1" />
            <el-option :label="t('datacheck.reportDialog.flags.targetOnly')" :value="2" />
          </el-select>
        </div>

        <template v-if="currentTable">
          <div class="current-table">{{ currentTable.sourceTableId }} → {{ currentTable.targetTableId }}</div>
          <el-table v-loading="resultLoading" :data="results" size="small" class="result-table">
            <el-table-column prop="pk" :label="t('datacheck.reportDialog.pk')" min-width="220" show-overflow-tooltip />
            <el-table-column :label="t('datacheck.reportDialog.flag')" width="110">
              <template #default="{ row }">{{ flagLabel(row.flag) }}</template>
            </el-table-column>
            <el-table-column :label="t('datacheck.reportDialog.updatedAt')" width="190">
              <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
            </el-table-column>
          </el-table>
          <div class="result-foot">
            <span class="total">{{ t('datacheck.reportDialog.totalRows', { n: resultTotal }) }}</span>
            <el-pagination
              v-if="resultTotal > PAGE_SIZE"
              :current-page="resultPage"
              :page-size="PAGE_SIZE"
              :total="resultTotal"
              layout="prev, pager, next"
              background
              @current-change="onResultPage"
            />
          </div>
        </template>
        <el-empty v-else :description="t('datacheck.reportDialog.selectTable')" />
      </div>
    </el-drawer>
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

.report-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 10px;
  font-weight: 600;
}

.report-table :deep(.el-table__cell) {
  padding: 6px 0;
}

.results-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 18px 0 6px;
}

.results-head h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.flag-select {
  width: 140px;
}

.current-table {
  margin-bottom: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.result-table :deep(.el-table__cell) {
  padding: 6px 0;
}

.result-foot {
  display: flex;
  align-items: center;
  margin-top: 8px;
}

.total {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.result-foot .el-pagination {
  margin-left: auto;
}
</style>
