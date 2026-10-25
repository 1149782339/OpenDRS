<script setup lang="ts">
import { apiErrorMessage } from '@/api/http'
import { getTask, getTaskStatus, listTaskLogs } from '@/api/task'
import {
  type ConnectionInfo,
  type MigrationLogItem,
  type MigrationLogLevel,
  type MigrationOffset,
  type MigrationStatusResponse,
  type MigrationTaskResponse,
} from '@/types/api'
import { formatDateTime, formatLogTime } from '@/utils/datetime'
import {
  DASH,
  dbLabel,
  isLiveTask,
  modeLabel,
  phaseLabelOf,
  stateOf,
} from '@/utils/taskStatus'
import { ArrowLeft, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import TaskMonitor from '@/components/TaskMonitor.vue'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()

const taskId = computed(() => Number(route.params.id))
const task = ref<MigrationTaskResponse | null>(null)
const status = ref<MigrationStatusResponse | null>(null)
const loading = ref(false)

type DetailTab = 'info' | 'logs' | 'monitor'
const activeTab = ref<DetailTab>('info')
/** 日志懒加载：第一次进入「迁移日志」页签才拉数据，之后切走再切回保留已加载内容。 */
const logsVisited = ref(false)

/** 后端每页固定给 20 条；超过一页才出翻页按钮。 */
const PAGE_SIZE = 20
const LEVELS: MigrationLogLevel[] = ['INFO', 'WARNING', 'ERROR']

const logs = ref<MigrationLogItem[]>([])
const logTotal = ref(0)
const page = ref(1)
/** 空串代表「全部级别」：el-select 把 null 当作未选择，会退回 placeholder。 */
const level = ref<MigrationLogLevel | ''>('')
const logLoading = ref(false)

/** 任务状态：优先用 /status 轮询（含 暂停中/结束中），没轮到时退回任务详情。 */
const stateView = computed(() => {
  void locale.value // 语言切换时重译状态标签
  if (status.value) return stateOf(status.value)
  if (task.value) return stateOf(task.value)
  return { tone: 'none' as const, label: '' }
})
const tone = computed(() => stateView.value.tone)
const stateLabel = computed(() => stateView.value.label)
/** 迁移进度：位点与回放时延来自 /status 接口，随轮询刷新。 */
const offset = computed(() => status.value?.offset ?? null)
const lagText = computed(() => {
  const lagMs = status.value?.progress.lagMs
  if (lagMs == null) return DASH
  return lagMs >= 10_000 ? `${(lagMs / 1000).toFixed(0)} s` : `${(lagMs / 1000).toFixed(1)} s`
})

/**
 * 同步进度的一个方向块。双向同步任务拆成「正向同步进度」「反向同步进度」两块，各自有位点；
 * 单向任务只有一块、不显示标题。回放时延目前只有正向一份（/status 的 lagMs）。
 */
interface ProgressBlock {
  title: string | null
  offset: MigrationOffset | null
  lag: string | null
}

const progressBlocks = computed<ProgressBlock[]>(() => {
  const forward: ProgressBlock = {
    title: null,
    offset: offset.value,
    lag: lagText.value,
  }
  const reverse = status.value?.reverse ?? null
  if (!reverse) return [forward]
  return [
    { title: t('detail.forward'), offset: forward.offset, lag: forward.lag },
    { title: t('detail.reverse'), offset: reverse, lag: null },
  ]
})
/** 迁移阶段独立成栏，任何状态都显示：失败时一眼看出死在结构/全量/增量哪一步。 */
const phaseLabel = computed(() =>
  task.value ? phaseLabelOf(task.value.phase) : '',
)
const lastPage = computed(() => Math.max(1, Math.ceil(logTotal.value / PAGE_SIZE)))
const showPager = computed(() => logTotal.value > PAGE_SIZE)

let pollTimer: ReturnType<typeof setInterval> | null = null

function hostPort(connection: ConnectionInfo | null | undefined): string {
  if (!connection) return DASH
  return `${connection.host}:${connection.port}`
}

async function loadTask(silent = false) {
  if (!silent) loading.value = true
  try {
    task.value = await getTask(taskId.value)
  } catch (error) {
    if (!silent) ElMessage.error(apiErrorMessage(error))
  } finally {
    if (!silent) loading.value = false
    syncPolling()
  }
}

/** 位点/时延随任务轮询静默刷新，拉失败不打断页面（任务接口是主数据源）。 */
async function loadStatus() {
  try {
    status.value = await getTaskStatus(taskId.value)
  } catch {
    // 静默：进度块显示旧值或破折号
  }
}

/**
 * 取一页日志。若用户正停在最后一页，新写入的行会把页码往后推一格，
 * 这里自动跟到新的最后一页，免得「日志一直在写但屏幕不动」。
 * 首次加载（还不知道总数）不跟，否则会直接从第 1 页跳到最后一页。
 */
async function loadLogs(target = page.value, silent = false) {
  const wasLast = logTotal.value > 0 && target >= lastPage.value
  if (!silent) logLoading.value = true
  try {
    const result = await listTaskLogs(taskId.value, {
      level: level.value === '' ? null : level.value,
      page: target,
      size: PAGE_SIZE,
    })
    logTotal.value = result.total
    const maxPage = Math.max(1, Math.ceil(result.total / PAGE_SIZE))
    if (wasLast && maxPage > target) {
      await loadLogs(maxPage, silent)
      return
    }
    page.value = Math.min(target, maxPage)
    logs.value = result.items
  } catch (error) {
    if (!silent) ElMessage.error(apiErrorMessage(error))
  } finally {
    if (!silent) logLoading.value = false
  }
}

async function refreshAll(silent = false) {
  await loadTask(silent)
  await loadStatus()
  if (activeTab.value === 'logs' && logsVisited.value) {
    await loadLogs(page.value, silent)
  }
}

/** 任务在动就 3s 刷一次状态和当前页日志；结束之后停掉，页面不再自己动。 */
function syncPolling() {
  const live = task.value != null && isLiveTask(task.value)
  if (live && pollTimer == null) {
    pollTimer = setInterval(() => {
      void refreshAll(true)
    }, 3000)
  } else if (!live && pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function onLevelChange() {
  page.value = 1
  void loadLogs(1)
}

function onPageChange(next: number) {
  void loadLogs(next)
}

watch(taskId, () => {
  if (!Number.isNaN(taskId.value)) {
    page.value = 1
    level.value = ''
    logsVisited.value = false
    activeTab.value = 'info'
    void refreshAll()
  }
})

watch(activeTab, (tab) => {
  if (tab === 'logs' && !logsVisited.value) {
    logsVisited.value = true
    void loadLogs(1)
  }
})

onMounted(() => {
  void refreshAll()
})

onUnmounted(() => {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<template>
  <div v-loading="loading" class="detail">
    <div class="back-row">
      <span class="back" @click="router.push('/tasks')">
        <el-icon><ArrowLeft /></el-icon>
        <span>{{ t('detail.backToList') }}</span>
      </span>
    </div>

    <el-tabs v-model="activeTab" class="detail-tabs">
      <el-tab-pane :label="t('detail.tabs.info')" name="info">
        <template v-if="task">
          <section class="block">
            <h3>{{ t('detail.taskInfo') }}</h3>
            <div class="kv">
              <div class="k">{{ t('detail.taskName') }}</div>
              <div class="v">{{ task.name }}</div>
              <div class="k">{{ t('detail.taskId') }}</div>
              <div class="v mono" :title="task.publicId">{{ task.publicId }}</div>

              <div class="k">{{ t('detail.createdAt') }}</div>
              <div class="v">{{ formatDateTime(task.createdAt) }}</div>
              <div class="k">{{ t('detail.mode') }}</div>
              <div class="v">{{ modeLabel(task.mode) }}</div>

              <div class="k">{{ t('detail.status') }}</div>
              <div class="v">
                <span v-if="stateLabel" class="status-text" :class="`status-${tone}`">
                  <el-icon v-if="tone === 'running'" class="is-loading"><Loading /></el-icon>
                  <span>{{ stateLabel }}</span>
                </span>
                <span v-else>{{ DASH }}</span>
              </div>
              <div class="k">{{ t('detail.phase') }}</div>
              <div class="v"><span class="phase-tag">{{ phaseLabel }}</span></div>
            </div>
          </section>

          <section class="block">
            <h3>{{ t('detail.instanceInfo') }}</h3>
            <div class="kv">
              <div class="k">{{ t('detail.sourceEngine') }}</div>
              <div class="v">{{ dbLabel(task.source.type) }}</div>
              <div class="k">{{ t('detail.ip') }}</div>
              <div class="v mono">{{ hostPort(task.source) }}</div>

              <div class="k">{{ t('detail.targetEngine') }}</div>
              <div class="v">{{ dbLabel(task.target.type) }}</div>
              <div class="k">{{ t('detail.ip') }}</div>
              <div class="v mono">{{ hostPort(task.target) }}</div>
            </div>
          </section>

          <section class="block">
            <h3>{{ t('detail.progress') }}</h3>
            <template v-for="(block, index) in progressBlocks" :key="index">
              <h4 v-if="block.title" class="direction-title">{{ block.title }}</h4>
              <div class="kv">
                <div class="k">{{ t('detail.updatedAt') }}</div>
                <div class="v kv-wide">{{ block.offset?.updatedAt ?? DASH }}</div>
                <div class="k">{{ t('detail.sourceOffset') }}</div>
                <div class="v mono">{{ block.offset?.source ?? DASH }}</div>
                <div class="k">{{ t('detail.capturedOffset') }}</div>
                <div class="v mono">{{ block.offset?.captured ?? DASH }}</div>
                <div class="k">{{ t('detail.appliedOffset') }}</div>
                <div class="v mono">{{ block.offset?.applied ?? DASH }}</div>
                <div class="k">{{ t('detail.lag') }}</div>
                <div class="v">{{ block.lag ?? DASH }}</div>
              </div>
            </template>
          </section>
        </template>
        <el-empty v-else-if="!loading" :description="t('detail.notExist')" />
      </el-tab-pane>

      <el-tab-pane :label="t('detail.tabs.logs')" name="logs">
        <section class="block">
          <div class="log-head">
            <h3>{{ t('detail.tabs.logs') }}</h3>
            <div class="log-tools">
              <el-select
                v-model="level"
                size="small"
                class="level-select"
                :placeholder="t('detail.allLevels')"
                @change="onLevelChange"
              >
                <el-option :label="t('detail.allLevels')" value="" />
                <el-option v-for="item in LEVELS" :key="item" :label="item" :value="item" />
              </el-select>
              <el-button size="small" :loading="logLoading" @click="loadLogs()">{{ t('detail.refresh') }}</el-button>
            </div>
          </div>

          <el-table v-loading="logLoading" :data="logs" size="small" class="log-table" :empty-text="t('detail.noLogs')">
            <el-table-column :label="t('detail.time')" width="176">
              <template #default="{ row }">
                <span class="mono time">{{ formatLogTime(row.loggedAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('detail.level')" width="92">
              <template #default="{ row }">
                <span class="lv" :class="`lv-${row.level.toLowerCase()}`">{{ row.level }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="message" :label="t('detail.description')" min-width="320" show-overflow-tooltip />
          </el-table>

          <div class="log-foot">
            <span class="total">{{ t('detail.total', { n: logTotal }) }}</span>
            <el-pagination
              v-if="showPager"
              :current-page="page"
              :page-size="PAGE_SIZE"
              :total="logTotal"
              layout="prev, pager, next"
              background
              @current-change="onPageChange"
            />
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane :label="t('detail.tabs.monitor')" name="monitor" lazy>
        <section class="block">
          <TaskMonitor v-if="task" :task-id="taskId" />
          <el-empty v-else-if="!loading" :description="t('detail.notExist')" />
        </section>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.back-row {
  padding-bottom: 6px;
}

.back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  cursor: pointer;
}

.back:hover {
  color: var(--el-color-primary);
}

/* 页签内容直接接在页签栏下面，不再套一层卡片间距；选中的页签才有下划线。 */
.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.detail-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--el-border-color-light);
}

.detail-tabs :deep(.el-tabs__item) {
  font-size: 14px;
}

.detail-tabs :deep(.el-tabs__content) {
  padding-top: 2px;
}

.block {
  margin-top: 22px;
  background: #fff;
  border-radius: 8px;
  padding: 18px 20px;
}

/* 分组标题：面板里唯一的结构入口，必须明显强于下面的键值行。
   15px/600 + 最深色，与 13px 的 direction-title 拉开一级。 */
.block h3 {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  letter-spacing: 0.01em;
}

.direction-title {
  margin: 14px 0 2px;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-regular);
}

.direction-title:first-of-type {
  margin-top: 2px;
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

.kv-wide {
  grid-column: 2 / 5;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.status-text {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-weight: 500;
}

.status-running {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
  padding: 0 7px;
  border-radius: 9px;
  font-size: 12px;
  line-height: 18px;
}

.status-done {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  padding: 0 7px;
  border-radius: 9px;
  font-size: 12px;
  line-height: 18px;
}

.status-failed {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  padding: 0 7px;
  border-radius: 9px;
  font-size: 12px;
  line-height: 18px;
}

.status-none {
  color: var(--el-text-color-secondary);
}

/* 与日志级别 .lv-info 同款：迁移阶段是关键信息，用浅蓝胶囊提醒目。 */
.phase-tag {
  display: inline-block;
  padding: 0 7px;
  border-radius: 9px;
  font-size: 12px;
  line-height: 18px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.log-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.log-head h3 {
  margin: 0;
}

.log-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.level-select {
  width: 118px;
}

.log-table :deep(.el-table__cell) {
  padding: 7px 0;
}

.time {
  color: var(--el-text-color-secondary);
}

.lv {
  display: inline-block;
  padding: 0 7px;
  border-radius: 9px;
  font-size: 11px;
  line-height: 17px;
}

.lv-info {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.lv-warning {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}

.lv-error {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}

.log-foot {
  display: flex;
  align-items: center;
  padding-top: 12px;
}

.total {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.log-foot .el-pagination {
  margin-left: auto;
}
</style>
