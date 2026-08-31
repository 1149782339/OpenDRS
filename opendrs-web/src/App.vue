<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Link, List } from '@element-plus/icons-vue'

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
          <span>连接</span>
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><List /></el-icon>
          <span>任务</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="app-header">{{ (route.meta.title as string) || '管理台' }}</el-header>
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
</style>
