<script setup lang="ts">
import { CaretBottom, Check } from '@element-plus/icons-vue'
import { computed, ref } from 'vue'

/** 可选语言。label 用各自语言自己的写法：中文写「简体中文」，英文写「English」。 */
const LOCALES = [
  { value: 'zh-CN', label: '简体中文' },
  { value: 'en-US', label: 'English' },
]

const current = ref('zh-CN')

const currentLabel = computed(
  () => LOCALES.find((item) => item.value === current.value)?.label ?? '',
)

function onSelect(value: string) {
  current.value = value
}
</script>

<template>
  <el-dropdown trigger="click" popper-class="locale-dropdown" @command="onSelect">
    <!-- 触发器：地球图标 + 当前语言 + 展开箭头 -->
    <button type="button" class="locale-trigger">
      <svg class="locale-globe" viewBox="0 0 16 16" aria-hidden="true">
        <circle cx="8" cy="8" r="6.25" />
        <path d="M1.75 8h12.5" />
        <ellipse cx="8" cy="8" rx="2.9" ry="6.25" />
      </svg>
      <span class="locale-label">{{ currentLabel }}</span>
      <el-icon class="locale-caret"><CaretBottom /></el-icon>
    </button>

    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="item in LOCALES"
          :key="item.value"
          :command="item.value"
          :class="['locale-item', { active: item.value === current }]"
        >
          <!-- 当前语言左边的对勾；非当前项留同样宽的空格，菜单里的文字因此对齐 -->
          <el-icon v-if="item.value === current" class="locale-check"><Check /></el-icon>
          <span v-else class="locale-check-blank" />
          <span>{{ item.label }}</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.locale-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 10px;
  font-size: 14px;
  font-weight: 400;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  cursor: pointer;
}

.locale-trigger:hover {
  border-color: var(--el-border-color-hover);
}

.locale-trigger:focus-visible {
  outline: none;
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}

/* 地球图标：内联 SVG，图标库里没有现成的 */
.locale-globe {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.2;
  stroke-linecap: round;
}

.locale-label {
  white-space: nowrap;
}

.locale-caret {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>

<!-- 下拉菜单被 teleport 到 body 上，scoped 选不到它，单独给这一小段用非 scoped 样式。 -->
<style>
.locale-dropdown.el-popper {
  padding: 4px 0;
}

/* 菜单底色：纯白，不铺蓝色底 */
.locale-dropdown .el-dropdown-menu {
  padding: 4px;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgb(0 0 0 / 12%);
}

.locale-dropdown .el-dropdown-menu__item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 140px;
  padding: 7px 12px;
  border-radius: 4px;
  color: var(--el-text-color-regular);
}

/* 当前语言：对勾 + 蓝字 */
.locale-dropdown .locale-item.active {
  color: var(--el-color-primary);
}

/* 悬停只给一层中性灰，不铺蓝色底 */
.locale-dropdown .locale-item:not(.is-disabled):hover {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}

.locale-check {
  font-size: 14px;
  color: var(--el-color-primary);
}

/* 空占位：宽度与对勾一致，两个选项的文字才对得齐 */
.locale-check-blank {
  width: 14px;
}
</style>
