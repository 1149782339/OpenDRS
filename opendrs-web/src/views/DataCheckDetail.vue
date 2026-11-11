<script setup lang="ts">
import {
  getDataCheckTask,
  listDataCheckResults,
  listDataCheckTables,
  startDataCheckTask,
  stopDataCheckTask,
} from '@/api/datacheck'
import { apiErrorMessage } from '@/api/http'
import type {
  DataCheckResultItem,
  DataCheckTableResult,
  DataCheckTaskResponse,
} from '@/types/datacheck'
import {
  canStartDataCheck,
  canStopDataCheck,
  dataCheckFlagLabel,
  dataCheckLagText,
  dataCheckModeLabel,
  dataCheckPhaseLabel,
  dataCheckStateOf,
  dataCheckTableTone,
  isLiveDataCheck,
} from '@/utils/datacheck'
import { formatDateTime } from '@/utils/datetime'
import { DASH } from '@/utils/taskStatus'
import {
  ArrowLeft,
  CircleCheckFilled,
  CircleCloseFilled,
  Loading,
  Refresh,
  RemoveFilled,
} from '@element-plus/icons-vue'
import { ElMessage, type TableInstance } from 'element-plus'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const PAGE_SIZE = 20

const checkId = computed(() => Number(route.params.checkId))
const task = ref<DataCheckTaskResponse | null>(null)
const migrationTaskId = computed(
  () => task.value?.migrationTaskId ?? Number(route.params.id),
)

const tables = ref<DataCheckTableResult[]>([])
const loading = ref(false)
const loadError = ref('')

const selected = ref<DataCheckTableResult | null>(null)
const checkTableRef = ref<TableInstance>()
const resultsSection = ref<HTMLElement | null>(null)
const results = ref<DataCheckResultItem[]>([])
const resultTotal = ref(0)
const resultPage = ref(1)
const resultFlag = ref<number | null>(null)
const resultLoading = ref(false)

const stateView = computed(() =>
  task.value ? dataCheckStateOf(task.value) : { tone: 'none' as const, label: '' },
)

/** 汇总只做加法：行数、不一致数、时延取最大，不引入第二套口径。 */
const summary = computed(() => {
  const rows = tables.value
  const completed = rows.filter((row) => row.status === 'COMPLETED').length
  const failed = rows.filter((row) => row.status === 'FAILED').length
  const inconsistent = rows.reduce((sum, row) => sum + (row.totalCount ?? 0), 0)
  const sourceOnly = rows.reduce((sum, row) => sum + (row.sourceOnlyCount ?? 0), 0)
  const targetOnly = rows.reduce((sum, row) => sum + (row.targetOnlyCount ?? 0), 0)
  const checked = rows.some((row) => row.rowsChecked != null)
    ? rows.reduce((sum, row) => sum + (row.rowsChecked ?? 0), 0)
    : null
  const lags = rows
    .filter((row) => row.phase === 'INCREMENTAL')
    .map((row) => row.lagMs)
    .filter((value): value is number => value != null)
  return {
    total: rows.length,
    completed,
    failed,
    inconsistent,
    sourceOnly,
    targetOnly,
    checked,
    maxLag: lags.length > 0 ? Math.max(...lags) : null,
    waiting: rows.some((row) => row.waitingForReplay),
  }
})

const lagSummary = computed(() => {
  if (summary.value.waiting) return t('datacheck.phase.WAITING_REPLAY')
  if (summary.value.maxLag == null) return DASH
  return t('datacheck.lagMs', { n: summary.value.maxLag })
})

/** 大数字统一按千分位显示；空值仍走破折号。 */
function countText(value: number | null): string {
  return value == null ? DASH : value.toLocaleString()
}

// ------------------------------------------------------------------
// Load + polling
// ------------------------------------------------------------------

let pollTimer: ReturnType<typeof setInterval> | null = null

async function loadAll(silent = false) {
  if (!silent) loading.value = true
  try {
    task.value = await getDataCheckTask(checkId.value)
    tables.value = await listDataCheckTables(checkId.value)
    loadError.value = ''
    // 轮询会换掉表格行对象；按源表 id 找回当前选中项，避免高亮丢失。
    if (selected.value) {
      const current = selected.value.sourceTableId
      const next = tables.value.find((row) => row.sourceTableId === current) ?? null
      selected.value = next
      // el-table 的 currentRow 存的是行对象引用；轮询换掉了对象，需要重新指回去。
      if (next) checkTableRef.value?.setCurrentRow(next)
    }
    if (silent && selected.value) {
      await loadResults(true)
    }
  } catch (error) {
    if (!silent) {
      loadError.value = apiErrorMessage(error)
      ElMessage.error(loadError.value)
    }
  } finally {
    if (!silent) loading.value = false
    syncPolling()
  }
}

function syncPolling() {
  const live = task.value != null && isLiveDataCheck(task.value)
  if (live && pollTimer == null) {
    pollTimer = setInterval(() => {
      void loadAll(true)
    }, 3000)
  } else if (!live && pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// ------------------------------------------------------------------
// Table drill-down
// ------------------------------------------------------------------

function selectTable(row: DataCheckTableResult | null) {
  selected.value = row
  resultPage.value = 1
  results.value = []
  resultTotal.value = 0
  if (row) {
    void loadResults()
    void nextTick(() => {
      const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
      resultsSection.value?.scrollIntoView({
        behavior: reduce ? 'auto' : 'smooth',
        block: 'nearest',
      })
    })
  }
}

async function loadResults(silent = false) {
  const table = selected.value
  if (!table) return
  if (!silent) resultLoading.value = true
  try {
    const page = await listDataCheckResults(checkId.value, {
      tableId: table.sourceTableId,
      flag: resultFlag.value,
      page: resultPage.value,
      size: PAGE_SIZE,
    })
    results.value = page.items
    resultTotal.value = page.total
  } catch (error) {
    if (!silent) ElMessage.error(apiErrorMessage(error))
  } finally {
    if (!silent) resultLoading.value = false
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
// Actions
// ------------------------------------------------------------------

const starting = ref(false)
const stopping = ref(false)

async function startCheck() {
  if (!task.value) return
  starting.value = true
  try {
    await startDataCheckTask(task.value.id)
    await loadAll()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    starting.value = false
  }
}

async function stopCheck() {
  const current = task.value
  if (!current) return
  if (!current.stoppable) {
    ElMessage.warning(t('datacheck.stopFullDisabled'))
    return
  }
  stopping.value = true
  try {
    await stopDataCheckTask(current.id)
    await loadAll()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    stopping.value = false
  }
}

// ------------------------------------------------------------------
// Navigation
// ------------------------------------------------------------------

function backToChecks() {
  void router.push({
    name: 'task-detail',
    params: { id: migrationTaskId.value },
    query: { tab: 'datacheck' },
  })
}

function openMigrationTask() {
  void router.push({ name: 'task-detail', params: { id: migrationTaskId.value } })
}

// ------------------------------------------------------------------
// Lifecycle
// ------------------------------------------------------------------

watch(checkId, () => {
  selected.value = null
  results.value = []
  resultTotal.value = 0
  void loadAll()
})

onMounted(() => {
  void loadAll()
})

onUnmounted(() => {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<template>
  <div v-loading="loading" class="check-detail">
    <div class="back-row">
      <el-button link class="back-btn" :icon="ArrowLeft" @click="backToChecks">
        {{ t('datacheck.detail.back') }}
      </el-button>
    </div>

    <el-alert
      v-if="loadError"
      :title="loadError"
      type="error"
      :closable="false"
      show-icon
      class="load-error"
    />

    <template v-if="task">
      <header class="head">
        <div class="head-main">
          <h2>{{ task.name }}</h2>
          <div class="head-meta">
            <span class="mode-pill">{{ dataCheckModeLabel(task.mode) }}</span>
            <span class="status-text" :class="`status-${stateView.tone}`">
              <el-icon v-if="stateView.tone === 'running'" class="is-loading"><Loading /></el-icon>
              <el-icon v-else-if="stateView.tone === 'failed'"><CircleCloseFilled /></el-icon>
              <el-icon v-else-if="stateView.tone === 'done'"><CircleCheckFilled /></el-icon>
              <el-icon v-else><RemoveFilled /></el-icon>
              <span>{{ stateView.label }}</span>
            </span>
          </div>
        </div>
        <div class="head-actions">
          <el-button
            v-if="canStartDataCheck(task)"
            type="primary"
            :loading="starting"
            @click="startCheck"
          >
            {{ t('datacheck.start') }}
          </el-button>
          <el-tooltip
            v-if="canStopDataCheck(task)"
            :disabled="task.stoppable !== false"
            :content="t('datacheck.stopFullDisabled')"
            placement="top"
          >
            <span>
              <el-button :disabled="task.stoppable === false" :loading="stopping" @click="stopCheck">
                {{ t('datacheck.stop') }}
              </el-button>
            </span>
          </el-tooltip>
          <el-button :icon="Refresh" circle :loading="loading" @click="loadAll()" />
        </div>
      </header>

      <el-alert
        v-if="task.errorMessage"
        :title="task.errorMessage"
        type="error"
        :closable="false"
        show-icon
        class="task-error"
      />

      <!-- 四格台账：数字是这一页的主视觉，其余全部让位。 -->
      <section class="ledger">
        <div class="metric">
          <div class="metric-label">{{ t('datacheck.detail.metrics.tables') }}</div>
          <div class="metric-value">{{ summary.total }}</div>
          <div class="metric-sub">
            {{ t('datacheck.detail.metrics.completed', { done: summary.completed, total: summary.total }) }}
            <span v-if="summary.failed > 0" class="metric-failed">
              · {{ t('datacheck.detail.metrics.failed', { n: summary.failed }) }}
            </span>
          </div>
        </div>
        <div class="metric">
          <div class="metric-label">{{ t('datacheck.detail.metrics.checked') }}</div>
          <div class="metric-value">{{ countText(summary.checked) }}</div>
          <div class="metric-sub">{{ t('datacheck.detail.metrics.checkedHint') }}</div>
        </div>
        <div class="metric">
          <div class="metric-label">{{ t('datacheck.detail.metrics.inconsistent') }}</div>
          <div class="metric-value" :class="{ danger: summary.inconsistent > 0 }">
            {{ countText(summary.inconsistent) }}
          </div>
          <div class="metric-sub">
            <template v-if="summary.inconsistent > 0">
              {{ t('datacheck.detail.metrics.breakdown', {
                source: summary.sourceOnly,
                target: summary.targetOnly,
              }) }}
            </template>
            <template v-else>{{ t('datacheck.detail.metrics.consistent') }}</template>
          </div>
        </div>
        <div class="metric">
          <div class="metric-label">{{ t('datacheck.detail.metrics.lag') }}</div>
          <div class="metric-value">{{ lagSummary }}</div>
          <div class="metric-sub">{{ t('datacheck.detail.metrics.lagHint') }}</div>
        </div>
      </section>

      <section class="block">
        <h3>{{ t('datacheck.detail.taskInfo') }}</h3>
        <div class="kv">
          <div class="k">{{ t('datacheck.detail.taskId') }}</div>
          <div class="v mono" :title="task.publicId">{{ task.publicId }}</div>
          <div class="k">{{ t('datacheck.detail.mode') }}</div>
          <div class="v">{{ dataCheckModeLabel(task.mode) }}</div>

          <div class="k">{{ t('datacheck.detail.status') }}</div>
          <div class="v">{{ stateView.label }}</div>
          <div class="k">{{ t('datacheck.detail.migrationTask') }}</div>
          <div class="v">
            <el-button link type="primary" @click="openMigrationTask">
              {{ t('datacheck.detail.migrationTaskLink', { id: migrationTaskId }) }}
            </el-button>
          </div>

          <div class="k">{{ t('datacheck.detail.createdAt') }}</div>
          <div class="v">{{ formatDateTime(task.createdAt) }}</div>
          <div class="k">{{ t('datacheck.detail.updatedAt') }}</div>
          <div class="v">{{ formatDateTime(task.updatedAt) }}</div>
        </div>
      </section>

      <section class="block">
        <div class="block-head">
          <h3>{{ t('datacheck.detail.checks') }}</h3>
          <span class="block-note">{{ t('datacheck.detail.tableCount', { n: tables.length }) }}</span>
        </div>

        <el-table
          ref="checkTableRef"
          v-loading="loading"
          :data="tables"
          class="check-table"
          highlight-current-row
          :empty-text="t('datacheck.detail.noTables')"
          @current-change="selectTable"
        >
          <el-table-column prop="sourceTableId" :label="t('datacheck.detail.table.source')" min-width="190">
            <template #default="{ row }">
              <span class="mono">{{ row.sourceTableId }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="targetTableId" :label="t('datacheck.detail.table.target')" min-width="190">
            <template #default="{ row }">
              <span class="mono">{{ row.targetTableId }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('datacheck.detail.table.phase')" width="130">
            <template #default="{ row }">{{ dataCheckPhaseLabel(row) }}</template>
          </el-table-column>
          <el-table-column :label="t('datacheck.detail.table.status')" width="110">
            <template #default="{ row }">
              <span class="table-status" :class="`status-${dataCheckTableTone(row.status)}`">
                {{ t(`datacheck.tableStatus.${row.status}`) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="t('datacheck.detail.table.rowsChecked')" width="120" align="right">
            <template #default="{ row }">
              <span class="num">{{ countText(row.rowsChecked) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('datacheck.detail.table.inconsistent')" width="120" align="right">
            <template #default="{ row }">
              <span class="num" :class="{ danger: row.totalCount > 0 }">{{ countText(row.totalCount) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('datacheck.detail.table.lag')" width="140">
            <template #default="{ row }">{{ dataCheckLagText(row) }}</template>
          </el-table-column>
          <el-table-column :label="t('datacheck.detail.table.lastCheckAt')" width="190">
            <template #default="{ row }">
              <span class="time">{{ row.lastCheckAt ? formatDateTime(row.lastCheckAt) : DASH }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('datacheck.detail.table.error')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.waitingForReplay" class="waiting">
                {{ t('datacheck.detail.table.waitingHint') }}
              </span>
              <span v-else class="error-text">{{ row.errorMessage || DASH }}</span>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section v-if="selected" ref="resultsSection" class="block">
        <div class="block-head">
          <h3>{{ t('datacheck.detail.results') }}</h3>
          <el-select
            v-model="resultFlag"
            clearable
            size="small"
            class="flag-select"
            :placeholder="t('datacheck.detail.flags.all')"
            @change="onFlagChange"
          >
            <el-option :label="t('datacheck.detail.flags.different')" :value="0" />
            <el-option :label="t('datacheck.detail.flags.sourceOnly')" :value="1" />
            <el-option :label="t('datacheck.detail.flags.targetOnly')" :value="2" />
          </el-select>
        </div>

        <p class="pair">
          <span class="mono">{{ selected.sourceTableId }}</span>
          <span class="pair-arrow">→</span>
          <span class="mono">{{ selected.targetTableId }}</span>
        </p>

        <el-table v-loading="resultLoading" :data="results" class="result-table" size="small">
          <el-table-column prop="pk" :label="t('datacheck.detail.pk')" min-width="240" show-overflow-tooltip />
          <el-table-column :label="t('datacheck.detail.flag')" width="120">
            <template #default="{ row }">{{ dataCheckFlagLabel(row.flag) }}</template>
          </el-table-column>
          <el-table-column :label="t('datacheck.detail.resultUpdatedAt')" width="190">
            <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
          </el-table-column>
        </el-table>

        <div class="result-foot">
          <span class="total">{{ t('datacheck.detail.totalRows', { n: resultTotal }) }}</span>
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
      </section>
      <p v-else class="select-hint">{{ t('datacheck.detail.selectTable') }}</p>
    </template>

    <el-empty v-else-if="!loading && !loadError" :description="t('detail.notExist')" />
  </div>
</template>

<style scoped>
.check-detail {
  padding-bottom: 12px;
}

.back-row {
  padding-bottom: 6px;
}

.back-btn {
  --el-button-text-color: var(--el-text-color-secondary);
  --el-button-hover-text-color: var(--el-color-primary);
  font-size: 13px;
}

.load-error {
  margin-top: 10px;
}

.head {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 16px 20px 18px;
  background: #fff;
  border-radius: 8px;
}

.head-main {
  min-width: 0;
}

.head h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 650;
  line-height: 1.3;
  color: var(--el-text-color-primary);
  overflow-wrap: anywhere;
}

.head-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 8px;
}

.mode-pill {
  display: inline-block;
  padding: 0 8px;
  border-radius: 9px;
  font-size: 12px;
  line-height: 20px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
}

.status-text {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0 8px;
  border-radius: 9px;
  font-size: 12px;
  line-height: 20px;
  font-weight: 500;
}

.status-running {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}

.status-done {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.status-failed {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}

.status-none {
  color: var(--el-text-color-secondary);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  flex-shrink: 0;
}

.task-error {
  margin-top: 12px;
}

/* 台账条：四列数字，用发丝线分隔，不做四张卡片。 */
.ledger {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 12px;
  background: #fff;
  border-radius: 8px;
}

.metric {
  min-width: 0;
  padding: 14px 20px 16px;
  border-left: 1px solid var(--el-border-color-lighter);
}

.metric:first-child {
  border-left: none;
}

.metric-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.metric-value {
  margin-top: 4px;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--el-text-color-primary);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.metric-value.danger {
  color: var(--el-color-danger);
}

.metric-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.metric-failed {
  color: var(--el-color-danger);
}

.block {
  margin-top: 12px;
  background: #fff;
  border-radius: 8px;
  padding: 18px 20px;
}

.block h3 {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.block-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 8px;
}

.block-head h3 {
  margin: 0;
}

.block-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.kv {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr) 96px minmax(0, 1fr);
  column-gap: 24px;
}

.kv > div {
  padding: 9px 0;
  min-width: 0;
  font-size: 13px;
}

.k {
  color: var(--el-text-color-secondary);
}

.v {
  color: var(--el-text-color-primary);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.num {
  font-variant-numeric: tabular-nums;
}

.danger {
  color: var(--el-color-danger);
}

.time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.table-status {
  font-size: 13px;
}

.table-status.status-done {
  background: none;
  color: var(--el-color-success);
  padding: 0;
}

.table-status.status-running {
  background: none;
  color: var(--el-color-primary);
  padding: 0;
}

.table-status.status-failed {
  background: none;
  color: var(--el-color-danger);
  padding: 0;
}

.table-status.status-none {
  color: var(--el-text-color-secondary);
}

.check-table :deep(.el-table__cell),
.result-table :deep(.el-table__cell) {
  padding: 8px 0;
}

.check-table :deep(.el-table__row) {
  cursor: pointer;
}

.waiting {
  color: var(--el-color-warning);
}

.error-text {
  color: var(--el-color-danger);
}

.flag-select {
  width: 140px;
  margin-left: auto;
}

.pair {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
}

.pair-arrow {
  color: var(--el-text-color-placeholder);
}

.result-foot {
  display: flex;
  align-items: center;
  margin-top: 8px;
}

.total {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.result-foot .el-pagination {
  margin-left: auto;
}

.select-hint {
  margin: 10px 2px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

@media (max-width: 900px) {
  .ledger {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .metric:nth-child(3) {
    border-left: none;
  }

  .metric:nth-child(n + 3) {
    border-top: 1px solid var(--el-border-color-lighter);
  }

  .kv {
    grid-template-columns: 88px minmax(0, 1fr);
  }
}

@media (max-width: 600px) {
  .ledger {
    grid-template-columns: minmax(0, 1fr);
  }

  .metric {
    border-left: none;
  }

  .metric + .metric {
    border-top: 1px solid var(--el-border-color-lighter);
  }

  .head {
    flex-direction: column;
  }

  .head-actions {
    margin-left: 0;
  }
}
</style>
