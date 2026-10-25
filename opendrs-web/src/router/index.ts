import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { i18n } from '@/i18n'

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题的 i18n key；切换语言时按 key 重译。 */
    titleKey?: string
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/connections' },
    {
      path: '/connections',
      name: 'connections',
      component: () => import('@/views/Connections.vue'),
      meta: { titleKey: 'nav.connections' },
    },
    {
      path: '/tasks',
      name: 'tasks',
      component: () => import('@/views/Tasks.vue'),
      meta: { titleKey: 'nav.tasks' },
    },
    {
      path: '/tasks/create',
      name: 'task-create',
      component: () => import('@/views/TaskCreate.vue'),
      meta: { titleKey: 'nav.taskCreate' },
    },
    {
      path: '/tasks/:id',
      name: 'task-detail',
      component: () => import('@/views/TaskDetail.vue'),
      meta: { titleKey: 'nav.taskDetail' },
    },
  ],
})

function applyTitle(to: RouteLocationNormalized) {
  const key = to.meta.titleKey
  const title = key ? i18n.global.t(key) : i18n.global.t('nav.console')
  document.title = `${title} · OpenDRS`
}

router.afterEach((to) => {
  applyTitle(to)
})

/** 语言切换后重刷当前页标题（App.vue 里 watch locale 调用）。 */
export function refreshTitle(): void {
  applyTitle(router.currentRoute.value)
}

export default router
