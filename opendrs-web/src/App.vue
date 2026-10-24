<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Link, List } from '@element-plus/icons-vue'
import LocaleSwitcher from '@/components/LocaleSwitcher.vue'

const route = useRoute()
const active = computed(() => {
  if (route.path.startsWith('/tasks')) {
    return '/tasks'
  }
  return '/connections'
})
</script>

<template>
  <el-container class="app-shell">
    <el-aside width="208px" class="app-aside">
      <div class="logo">OpenDRS</div>
      <el-menu
        :default-active="active"
        :router="true"
        background-color="#1d2b3a"
        text-color="#c5d0dc"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/connections">
          <el-icon><Link /></el-icon>
          <span>数据源</span>
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><List /></el-icon>
          <span>任务</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="app-header">
        <span class="app-title">{{ (route.meta.title as string) || '管理台' }}</span>
        <LocaleSwitcher />
      </el-header>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
}
body {
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  background: #f5f7fa;
}
.app-shell {
  height: 100vh;
}
.app-aside {
  background: #1d2b3a;
  color: #fff;
}
.app-aside .el-menu {
  border-right: none;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #fff;
}
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  font-size: 16px;
  font-weight: 600;
}
.app-main {
  padding: 20px;
}
.page-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.page-toolbar h2 {
  margin: 0;
  font-size: 18px;
}
.json-block {
  margin: 0;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

/* ── 表单基线 ──────────────────────────────────────────────
   标签列定宽、单行控件限宽：所有字段起点落在同一条竖线上，
   单行输入不再按容器宽度拉伸。说明文字一律走 ? 提示，
   不在行与行之间堆段落，行高因此恒定。 */
.ops-form {
  --ops-label-w: 120px;
  --ops-field-w: 320px;
}
/* EP 把 18px 写死在 .el-form-item 上，只能直接覆盖 */
.ops-form .el-form-item {
  margin-bottom: 14px;
}
.ops-form .el-form-item__label {
  position: relative;
  padding-right: 14px;
  font-size: 13px;
  /* 标签比值淡一档：层级靠明度差，不靠加粗 */
  color: var(--el-text-color-regular);
}
/* 必填星号挪进左侧留白区，label 文字本身仍从同一 x 起排 */
.ops-form .el-form-item.is-required:not(.is-no-asterisk) > .el-form-item__label::before {
  position: absolute;
  left: -12px;
  margin-right: 0;
  color: var(--el-color-danger);
}
.ops-form .field {
  width: var(--ops-field-w);
  max-width: 100%;
}
/* ? 说明：默认淡灰不可见，悬停/聚焦才亮起来 */
.ops-help {
  margin-left: 8px;
  font-size: 14px;
  vertical-align: middle;
  color: var(--el-text-color-placeholder);
  cursor: help;
}
.ops-help:hover {
  color: var(--el-color-primary);
}
.ops-help:focus-visible {
  border-radius: 50%;
  color: var(--el-color-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-5);
  outline: none;
}
</style>
