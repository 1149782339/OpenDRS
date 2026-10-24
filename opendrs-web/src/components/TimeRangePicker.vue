<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, CaretBottom, CaretTop } from '@element-plus/icons-vue'
import type { DatePickerInstance } from 'element-plus'
import { PRESET_RANGES, stepForSpan, type RangeWindow } from '@/types/monitor'
import { formatDateTimeShort } from '@/utils/datetime'

const props = defineProps<{ modelValue: RangeWindow }>()
const emit = defineEmits<{ 'update:modelValue': [value: RangeWindow] }>()

/** 面板的两种形态：预设档位列表 / 自定义时间区间。 */
const mode = ref<'list' | 'custom'>('list')
/** 自定义区间的草稿：点「确定」之前不动 modelValue。 */
const draft = ref<Date[] | null>(null)
/** 浮层开合由我们自己控制 —— 选完预设要立刻收起，不能等用户再点一次外面。 */
const visible = ref(false)
/** 自定义形态的根节点，用来量日历面板占了多高。 */
const customRoot = ref<HTMLElement | null>(null)
/** 日历面板要占掉的高度（从日期输入框下沿算到面板底边）。 */
const panelSpace = ref(0)
/** 日期选择器实例，用来直接展开日历。 */
const pickerRef = ref<DatePickerInstance | null>(null)

/** 单栏日历面板的宽度：Element Plus 的 single-panel 模式固定 322px。 */
const CALENDAR_WIDTH = 322
/** 浮层自己的内边距（左右各 12px）和边框（左右各 1px）。 */
const POPOVER_CHROME = 26

const triggerText = computed(() => {
  const window = props.modelValue
  if (window.key === 'custom' && window.start !== null && window.end !== null) {
    return `${formatDateTimeShort(window.start * 1000)} ~ ${formatDateTimeShort(window.end * 1000)}`
  }
  return window.label
})

/** 自定义形态要塞下日历，宽度和预设列表不是一个量级。 */
const panelWidth = computed(() =>
  mode.value === 'custom' ? CALENDAR_WIDTH + POPOVER_CHROME : 176,
)
const popperClass = computed(() =>
  mode.value === 'custom' ? 'range-popover range-popover--custom' : 'range-popover',
)

/** 未来的时刻还没有数据，选了也是空的。 */
function disableFuture(date: Date): boolean {
  return date.getTime() > Date.now()
}

function selectPreset(preset: RangeWindow) {
  emit('update:modelValue', { ...preset })
  visible.value = false
}

async function enterCustom() {
  const window = props.modelValue
  if (window.key === 'custom' && window.start !== null && window.end !== null) {
    draft.value = [new Date(window.start * 1000), new Date(window.end * 1000)]
  } else {
    // 默认给最近一小时：和「近1小时」档位对齐，用户改起来最省事。
    const end = new Date()
    draft.value = [new Date(end.getTime() - 60 * 60 * 1000), end]
  }
  mode.value = 'custom'
  // 日历直接展开：让用户再点一次输入框才看到日历，多一步且很容易以为没反应。
  await nextTick()
  pickerRef.value?.handleOpen()
}

function confirmCustom() {
  const range = draft.value
  if (!range || range.length !== 2) return
  const startSec = Math.floor(range[0].getTime() / 1000)
  const endSec = Math.floor(range[1].getTime() / 1000)
  emit('update:modelValue', {
    key: 'custom',
    label: '自定义',
    minutes: null,
    start: startSec,
    end: endSec,
    step: stepForSpan(Math.max(1, endSec - startSec)),
  })
  visible.value = false
}

// 日历面板是绝对定位的浮层，会盖住它下面的按钮。量出「输入框下沿到面板底边」
// 的高度，在下面留出等高的空位，按钮正好落在面板之下。
function measurePanel() {
  const root = customRoot.value
  if (!root) return
  const panel = root.querySelector<HTMLElement>('.range-calendar-panel')
  const editor = root.querySelector<HTMLElement>('.el-date-editor')
  if (!panel || !editor) return
  const space = panel.getBoundingClientRect().bottom - editor.getBoundingClientRect().bottom
  if (space > 0) panelSpace.value = Math.ceil(space)
}

// 面板展开时量一次：nextTick 拿到的是刚挂进 DOM 的面板，
// 下一帧再量一次是为了等 popper 把位置算准。
function onCalendarVisible(open: boolean) {
  if (!open) return
  nextTick(() => {
    measurePanel()
    requestAnimationFrame(measurePanel)
  })
}

// 收起时回到档位列表，下次打开还是那个一眼扫完的短菜单。
watch(visible, (open) => {
  if (!open) mode.value = 'list'
})
</script>

<template>
  <el-popover
    v-model:visible="visible"
    trigger="click"
    placement="bottom-start"
    :width="panelWidth"
    :popper-class="popperClass"
  >
    <template #reference>
      <button type="button" class="range-trigger" :aria-expanded="visible">
        <span class="range-trigger-label">{{ triggerText }}</span>
        <el-icon class="range-trigger-arrow">
          <CaretTop v-if="visible" />
          <CaretBottom v-else />
        </el-icon>
      </button>
    </template>

    <!-- 预设档位 -->
    <div v-if="mode === 'list'" class="range-list">
      <button
        v-for="preset in PRESET_RANGES"
        :key="preset.key"
        type="button"
        class="range-option"
        :class="{ active: preset.key === modelValue.key }"
        @click="selectPreset(preset)"
      >
        {{ preset.label }}
      </button>
      <button type="button" class="range-option range-option-split" @click="enterCustom">
        <span>自定义</span>
        <el-icon><ArrowRight /></el-icon>
      </button>
    </div>

    <!-- 自定义区间 -->
    <div
      v-else
      ref="customRoot"
      class="range-custom"
      :style="{ '--range-calendar-width': `${CALENDAR_WIDTH}px` }"
    >
      <button type="button" class="range-back" @click="mode = 'list'">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回</span>
      </button>
      <el-date-picker
        ref="pickerRef"
        v-model="draft"
        type="datetimerange"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        format="YYYY/MM/DD HH:mm:ss"
        :default-time="[new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]"
        :disabled-date="disableFuture"
        :teleported="false"
        popper-class="range-calendar-panel"
        single-panel
        :clearable="false"
        @visible-change="onCalendarVisible"
      />
      <!-- 日历面板浮在这一块上，留出它的高度，下面的按钮才不会被盖住 -->
      <div class="range-calendar-space" :style="{ height: `${panelSpace}px` }" />
      <div class="range-custom-foot">
        <span class="range-custom-hint">只能选择过去的时间</span>
        <el-button size="small" type="primary" :disabled="draft === null" @click="confirmCustom">
          确定
        </el-button>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
/* 触发器复刻 el-select 的外观：同高、同圆角、同聚焦环，
   和旁边的搜索框并排时看着是一套控件，不是一个原生按钮混进来。 */
.range-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  /* 下限宽度：档位文字长短差很多（「近15分钟」对一段起止时间），
     不设下限的话切换一次，工具栏就跟着抖一下。 */
  min-width: 128px;
  height: 32px;
  padding: 0 12px;
  font-size: 14px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  cursor: pointer;
}

.range-trigger:hover {
  border-color: var(--el-border-color-hover);
}

.range-trigger:focus-visible {
  outline: none;
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}

.range-trigger-label {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.range-trigger-arrow {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.range-list {
  padding: 4px 0;
}

.range-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 7px 12px;
  font-size: 14px;
  color: var(--el-text-color-regular);
  background: none;
  border: 0;
  text-align: left;
  cursor: pointer;
}

.range-option:hover {
  background: var(--el-fill-color-light);
}

/* 当前档位只靠颜色区分，不加勾也不加底色：列表是「选项」不是「状态清单」。 */
.range-option.active {
  color: var(--el-color-primary);
}

.range-option-split {
  margin-top: 4px;
  padding-top: 9px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.range-back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 8px;
  padding: 2px 4px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  background: none;
  border: 0;
  cursor: pointer;
}

.range-back:hover {
  color: var(--el-color-primary);
}

/*
 * 日期输入框要和日历面板一样宽，否则面板相对输入框居中，两边各差一截，
 * 输入框的右边界就顶出浮层了。
 *
 * el-date-editor 的 box-sizing 是 content-box：width 算的是内容宽，
 * 还要再加它自己的内边距和边框，所以这里必须显式改成 border-box。
 * 宽度由 --range-calendar-width 给（单栏日历面板的宽度）。
 */
.range-custom :deep(.el-date-editor) {
  box-sizing: border-box;
  width: var(--range-calendar-width);
}

/* 一个月的宽度放不下两段完整时间戳，字号收一号才不至于被截断 */
.range-custom :deep(.el-range-input) {
  font-size: 12px;
}

.range-calendar-space {
  width: 100%;
}

.range-custom-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
  padding-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.range-custom-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>

<!-- 浮层本体被 teleport 到 body 上，scoped 选不到它，单独给这一小段用非 scoped 样式。 -->
<style>
.range-popover.el-popover {
  padding: 4px 0;
}

.range-popover.el-popover .el-popper__arrow {
  display: none;
}

/* 自定义形态是一张卡片：日历面板浮在输入框下面，两段拼成一张完整的卡 */
.range-popover--custom.el-popover {
  /* 单栏日历面板的宽度（Element Plus 的 .el-date-range-picker.single-panel
     也是这个数），日期输入框按它对齐。组件里由 CALENDAR_WIDTH 同步写进来。 */
  --range-calendar-width: 322px;
  padding: 12px;
}

/* 日历面板去掉自己的边框和阴影，直接坐在浮层这张卡里 —— 免得框中框。
   面板底部 Element Plus 自带的确定按钮也去掉：确认动作统一由浮层底部的按钮做，
   两处确认会让人怀疑是不是要点两次。 */
.range-popover--custom .range-calendar-panel {
  width: var(--range-calendar-width);
  border: none;
  border-radius: 0;
  box-shadow: none;
}

.range-popover--custom .range-calendar-panel .el-picker-panel__footer {
  display: none;
}

/* 日历自带的那行起止时间输入和上面的日期输入框是同一份值，摆两遍只是噪音。
   时间就着上面的输入框改，格式一样是到秒。 */
.range-popover--custom .range-calendar-panel .el-date-range-picker__time-header {
  display: none;
}
</style>
