import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/connections' },
    {
      path: '/connections',
      name: 'connections',
      component: () => import('@/views/Connections.vue'),
      meta: { title: '连接' },
    },
    {
      path: '/tasks',
      name: 'tasks',
      component: () => import('@/views/Tasks.vue'),
      meta: { title: '任务' },
    },
    {
      path: '/tasks/create',
      name: 'task-create',
      component: () => import('@/views/TaskCreate.vue'),
      meta: { title: '新建任务' },
    },
    {
      path: '/tasks/:id',
      name: 'task-detail',
      component: () => import('@/views/TaskDetail.vue'),
      meta: { title: '任务详情' },
    },
  ],
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : '管理台'
  document.title = `${title} · OpenDRS`
})

export default router
