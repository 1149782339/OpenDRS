<script setup lang="ts">
import { apiErrorMessage } from '@/api/http'
import { getReplayMetrics } from '@/api/task'
import type { ReplayDirectionMetrics, ReplayMetricsPoint } from '@/types/api'
import { Search } from '@element-plus/icons-vue'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import * as echarts from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { ElMessage } from 'element-plus'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ taskId: number }>()

interface MetricCard {
  key: string
  title: string
  /** Y 轴单位，也是 tooltip 后缀。 */
  unit: string
  /** 指标说明，展示在 ? 提示里。 */
  hint: string
  /** 正向（写目标库）或反向（写源库，仅双向任务有数据）。 */
  direction: 'forward' | 'reverse'
  pick: (m: ReplayDirectionMetrics) => number | null
  /** 统计值格式化：TPS 取整，延迟保留 1 位小数。 */
  format: (v: number) => string
}

/** 全部指标共用一种蓝：磁贴各自独立，不需要靠颜色区分，颜色留给「有无异常」这类语义。 */
const ACCENT = '#409eff'

const cards: MetricCard[] = [
  {
    key: 'replayTps',
    title: '目标库回放TPS',
    unit: 'count/s',
    direction: 'forward',
    hint: '目标库每秒成功回放的事务数，来自事务执行计时器次数的速率，与源端写入 TPS 同口径。',
    pick: (m) => m.applied,
    format: (v) => Math.round(v).toLocaleString(),
  },
  {
    key: 'replayQps',
    title: '目标库回放QPS',
    unit: 'rows/s',
    direction: 'forward',
    hint: '目标库每秒成功回放的行数：按 batch 内 addBatch 的行数计数（一次 executeBatch 含 N 行计 N），且只在 commit 成功后计入，与源端写入 QPS 同口径。',
    pick: (m) => m.appliedRows,
    format: (v) => Math.round(v).toLocaleString(),
  },
  {
    key: 'txExecTime',
    title: '目标库事务平均执行时间',
    unit: 'ms',
    direction: 'forward',
    hint: '单事务在目标库的平均执行时间：批次内每个源端事务继承该批次的语句执行耗时，为 N 个事务时延的算术平均。',
    pick: (m) => m.execMs,
    format: (v) => v.toFixed(1),
  },
  {
    key: 'txCommitTime',
    title: '目标库事务平均提交时间',
    unit: 'ms',
    direction: 'forward',
    hint: '单事务在目标库的平均提交时间：批次内每个源端事务继承该批次的 commit 耗时，为 N 个事务时延的算术平均。',
    pick: (m) => m.commitMs,
    format: (v) => v.toFixed(1),
  },
  {
    key: 'replayLag',
    title: '目标库数据同步延迟',
    unit: 's',
    direction: 'forward',
    hint: '源端 binlog 事件产生时间与该批次在目标库提交时间的差值，反映增量同步的落后程度。',
    pick: (m) => m.lag,
    format: (v) => v.toFixed(1),
  },
  {
    key: 'replayTpsRev',
    title: '源库回放TPS',
    unit: 'count/s',
    direction: 'reverse',
    hint: '双向同步的反向链路：每秒写回源库的事务数。',
    pick: (m) => m.applied,
    format: (v) => Math.round(v).toLocaleString(),
  },
  {
    key: 'replayQpsRev',
    title: '源库回放QPS',
    unit: 'rows/s',
    direction: 'reverse',
    hint: '双向同步的反向链路：每秒写回源库的行数，计数口径与正向一致。',
    pick: (m) => m.appliedRows,
    format: (v) => Math.round(v).toLocaleString(),
  },
  {
    key: 'txExecTimeRev',
    title: '源库事务平均执行时间',
    unit: 'ms',
    direction: 'reverse',
    hint: '双向同步的反向链路：单事务写回源库的平均执行时间。',
    pick: (m) => m.execMs,
    format: (v) => v.toFixed(1),
  },
  {
    key: 'txCommitTimeRev',
    title: '源库事务平均提交时间',
    unit: 'ms',
    direction: 'reverse',
    hint: '双向同步的反向链路：单事务写回源库的平均提交时间。',
    pick: (m) => m.commitMs,
    format: (v) => v.toFixed(1),
  },
  {
    key: 'replayLagRev',
    title: '源库数据同步延迟',
    unit: 's',
    direction: 'reverse',
    hint: '双向同步的反向链路：目标端 binlog 事件产生时间与该批次在源库提交时间的差值。',
    pick: (m) => m.lag,
    format: (v) => v.toFixed(1),
  },
]

const keyword = ref('')
/** 反向磁贴只在数据里真的出现反向方向（双向任务）时渲染，单向任务保持五张的现状。 */
const hasReverse = computed(() => points.value.some((p) => p.reverse !== null))
const visibleCards = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return cards.filter(
    (c) =>
      (c.direction === 'forward' || hasReverse.value) &&
      (!kw || c.title.toLowerCase().includes(kw)),
  )
})

const hasData = ref(false)
/** 最近一次拉到的数据点，正反两个方向共用一份。 */
const points = ref<ReplayMetricsPoint[]>([])
/** 首次加载是否已尝试：只有第一次失败才弹错误，轮询中的瞬断静默。 */
const attempted = ref(false)

/** 每张卡片一个 echarts 实例 + 一个 ResizeObserver，随页签懒挂载矫正塌陷尺寸。 */
const charts = new Map<string, echarts.ECharts>()
const observers = new Map<string, ResizeObserver>()

function setCardEl(key: string, el: Element | null) {
  const existing = charts.get(key)
  if (existing) {
    if (el) return
    observers.get(key)?.disconnect()
    observers.delete(key)
    existing.dispose()
    charts.delete(key)
    return
  }
  if (el instanceof HTMLDivElement) {
    charts.set(key, echarts.init(el))
    const observer = new ResizeObserver(() => charts.get(key)?.resize())
    observer.observe(el)
    observers.set(key, observer)
  }
}

let timer: ReturnType<typeof setInterval> | null = null
let inFlight = false

async function load() {
  if (inFlight) return
  inFlight = true
  try {
    const data = await getReplayMetrics(props.taskId)
    points.value = data.points
    hasData.value = data.points.length > 0
    render(data.points)
  } catch (error) {
    if (!attempted.value) {
      ElMessage.error(apiErrorMessage(error))
    }
  } finally {
    attempted.value = true
    inFlight = false
  }
}

function render(data: ReplayMetricsPoint[]) {
  for (const card of cards) {
    const chart = charts.get(card.key)
    if (!chart) continue
    const sample = (p: ReplayMetricsPoint) => {
      const m = card.direction === 'forward' ? p.forward : p.reverse
      return m === null ? null : card.pick(m)
    }
    const series = data.map(sample)
    const values = series.filter((v): v is number => v != null)
    const stats = cardStats(values, series)
    // 无数据时给各卡一个默认尺度：TPS/QPS 0~400（间隔 100，五个点），其余 0~1，坐标框架饱满
    const empty = values.length === 0
    const isRate =
      card.key === 'replayTps' ||
      card.key === 'replayQps' ||
      card.key === 'replayTpsRev' ||
      card.key === 'replayQpsRev'
    const yMax = empty ? (isRate ? 400 : 1) : niceCeil(stats.max ?? 0)
    // TPS/QPS 刻度固定 5~6 个点；其余卡片交给 ECharts 自动选间隔
    const yInterval = isRate
      ? empty
        ? 100
        : Math.max(1, Math.ceil(yMax / 5))
      : undefined
    // 固定横轴为查询窗口（近 15 分钟），数据稀疏时坐标也不会收缩，刻度均匀分布。
    const nowMs = Date.now()
    chart.setOption({
      animation: false,
      // top 给坐标轴顶部的单位，bottom 给横轴刻度 + 图例留出两层高度
      grid: { left: 52, right: 14, top: 26, bottom: 48 },
      // 图例点明这条线是哪个指标，同时给图表一个底部的视觉落点
      legend: {
        bottom: 0,
        left: 0,
        icon: 'roundRect',
        itemWidth: 14,
        itemHeight: 2,
        itemGap: 6,
        textStyle: { color: '#909399', fontSize: 12 },
      },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v: unknown) =>
          typeof v === 'number' ? `${card.format(v)} ${card.unit}` : '—',
      },
      xAxis: {
        type: 'time',
        min: nowMs - 15 * 60 * 1000,
        max: nowMs,
        splitNumber: 5,
        axisLine: { lineStyle: { color: '#dcdfe6' } },
        axisTick: { show: false },
        axisLabel: {
          formatter: '{HH}:{mm}',
          hideOverlap: true,
          color: '#909399',
          fontSize: 12,
        },
      },
      yAxis: {
        type: 'value',
        name: card.unit,
        nameLocation: 'end',
        nameTextStyle: { align: 'right', color: '#a8abb2', fontSize: 12, padding: [0, 6, 0, 0] },
        // 全空数据时 ECharts 算不出范围会连刻度都不画：min 恒为 0，max/interval 显式给定，
        // 有数据按峰值向上取整齐值（1/2/5×10^n），无数据用兜底尺度，坐标框架始终存在
        min: 0,
        max: yMax,
        interval: yInterval,
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#909399', fontSize: 12 },
        // 点线而不是虚线：密集的虚线会在宽图上形成横向条纹，压过数据线
        splitLine: { lineStyle: { type: 'dotted', color: '#ebeef5' } },
      },
      series: [
        {
          name: card.title,
          type: 'line',
          // 采样点标出来：没有标记就无法区分「采样到 0」和「这一段没有采样」
          showSymbol: series.length <= 60,
          symbol: 'triangle',
          symbolSize: 7,
          // 不做平滑：平滑会在两个真实采样点之间画出从未测量的中间值
          smooth: false,
          connectNulls: false,
          lineStyle: { width: 2, color: ACCENT },
          itemStyle: { color: ACCENT },
          data: data.map((p) => [p.t * 1000, sample(p)] as [number, number | null]),
        },
      ],
    })
    statsMap[card.key] = stats
  }
}

function cardStats(values: number[], series: (number | null)[]) {
  if (values.length === 0) return { max: null, min: null, avg: null, current: null }
  let max = -Infinity
  let min = Infinity
  let sum = 0
  for (const v of values) {
    if (v > max) max = v
    if (v < min) min = v
    sum += v
  }
  // 当前值取最后一个采样点：它为 null 就如实显示 —，不拿旧值冒充现在。
  const current = series.length > 0 ? (series[series.length - 1] ?? null) : null
  return { max, min, avg: sum / values.length, current }
}

/** 向上取 1/2/5×10^n 的整齐上限，坐标刻度好看且全空时有兜底值。 */
function niceCeil(v: number): number {
  if (v <= 0) return 1
  const base = Math.pow(10, Math.floor(Math.log10(v)))
  const f = v / base
  const nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10
  return nice * base
}

type Stats = { max: number | null; min: number | null; avg: number | null; current: number | null }
const statsMap = reactive<Record<string, Stats>>({})

function statText(key: string, field: keyof Stats): string {
  const stats = statsMap[key]
  const card = cards.find((c) => c.key === key)
  const value = stats?.[field]
  if (value == null || !card) return '—'
  return card.format(value)
}

/** 单位跟着具体的那一项走：当前值没有采样时显示「—」，不能后面还挂一个单位。 */
function hasValue(key: string, field: keyof Stats): boolean {
  return statsMap[key]?.[field] != null
}

watch(
  () => props.taskId,
  () => {
    if (attempted.value) {
      void load()
    }
  },
)

// 反向磁贴随 hasReverse 首次出现时懒挂载，挂载后立刻补一次渲染，
// 不必等下一个 5 秒轮询周期
watch(hasReverse, async (nowHasReverse) => {
  if (nowHasReverse) {
    await nextTick()
    render(points.value)
  }
})

onMounted(() => {
  void load()
  timer = setInterval(() => void load(), 5000)
})

onUnmounted(() => {
  if (timer != null) {
    clearInterval(timer)
    timer = null
  }
  for (const observer of observers.values()) {
    observer.disconnect()
  }
  observers.clear()
  for (const chart of charts.values()) {
    chart.dispose()
  }
  charts.clear()
})
</script>

<template>
  <div class="monitor">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        class="search"
        placeholder="请输入指标名称"
        clearable
        :prefix-icon="Search"
      />
    </div>
    <div v-show="visibleCards.length > 0" class="card-grid">
      <div v-for="card in visibleCards" :key="card.key" class="metric-card">
        <div class="card-title">
          <span>{{ card.title }}</span>
          <el-tooltip :content="card.hint" placement="top">
            <span class="info-icon" tabindex="0">?</span>
          </el-tooltip>
        </div>
        <div class="card-stats">
          <div class="stat">
            <div class="stat-value">{{ statText(card.key, 'max') }}<span v-if="hasValue(card.key, 'max')" class="stat-unit">{{ card.unit }}</span></div>
            <div class="stat-label">最大值</div>
          </div>
          <div class="stat">
            <div class="stat-value">{{ statText(card.key, 'min') }}<span v-if="hasValue(card.key, 'min')" class="stat-unit">{{ card.unit }}</span></div>
            <div class="stat-label">最小值</div>
          </div>
          <div class="stat">
            <div class="stat-value">{{ statText(card.key, 'avg') }}<span v-if="hasValue(card.key, 'avg')" class="stat-unit">{{ card.unit }}</span></div>
            <div class="stat-label">平均值</div>
          </div>
          <div class="stat">
            <div class="stat-value">{{ statText(card.key, 'current') }}<span v-if="hasValue(card.key, 'current')" class="stat-unit">{{ card.unit }}</span></div>
            <div class="stat-label">当前值</div>
          </div>
        </div>
        <div :ref="(el) => setCardEl(card.key, el as Element | null)" class="card-chart" />
      </div>
    </div>
    <el-empty
      v-if="attempted && !hasData"
      class="empty"
      description="暂无监控数据：任务启动并进入增量迁移后，这里会显示目标库回放 TPS 与数据同步延迟曲线"
    />
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 16px;
}

.search {
  width: 280px;
}

/* 指标磁贴：接近方形的紧凑块，不跟着视口拉成横条。
   400px 下限让每张卡的宽高比接近 1:1，和图表的阅读密度匹配。 */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 16px;
}

.metric-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 18px 16px 12px;
}

/* 标题安静：数值才是主角，两者不能都靠加粗抢戏 */
.card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.info-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border: 1px solid #c0c4cc;
  border-radius: 50%;
  font-size: 10px;
  line-height: 1;
  color: #909399;
  cursor: help;
}

.info-icon:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}

/* 四个统计均分卡片宽度，不再挤在左侧一小块 */
.card-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-top: 14px;
}

.stat {
  min-width: 0;
}

.stat-value {
  font-size: 18px;
  font-weight: 600;
  line-height: 1.3;
  color: #303133;
  white-space: nowrap;
}

/* 单位紧贴数字，像 37.4% 那样读成一个整体；比标签更淡 */
.stat-unit {
  margin-left: 2px;
  font-size: 11px;
  font-weight: 400;
  color: #a8abb2;
}

.stat-label {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.4;
  color: #909399;
  white-space: nowrap;
}

.card-chart {
  height: 270px;
  width: 100%;
  margin-top: 6px;
}

.empty {
  padding: 40px 0;
}
</style>
