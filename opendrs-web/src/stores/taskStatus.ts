import { getTask, getTaskStatus } from '@/api/task'
import { isActiveJobState, type MigrationStatusResponse, type MigrationTaskResponse } from '@/types/api'
import { defineStore } from 'pinia'
import { ref } from 'vue'

const POLL_MS = 2000

export const useTaskStatusStore = defineStore('taskStatus', () => {
  const task = ref<MigrationTaskResponse | null>(null)
  const status = ref<MigrationStatusResponse | null>(null)
  const loading = ref(false)
  let timer: ReturnType<typeof setInterval> | null = null
  let pollingId: number | null = null

  function stopPolling() {
    if (timer != null) {
      clearInterval(timer)
      timer = null
    }
    pollingId = null
  }

  async function refreshStatus(id: number) {
    status.value = await getTaskStatus(id)
    if (!isActiveJobState(status.value.jobState)) {
      stopPolling()
      task.value = await getTask(id)
    }
  }

  function startPolling(id: number) {
    stopPolling()
    pollingId = id
    timer = setInterval(() => {
      if (pollingId == null) {
        return
      }
      void refreshStatus(pollingId).catch(() => {
        /* keep the last known status; next tick retries */
      })
    }, POLL_MS)
  }

  async function load(id: number) {
    loading.value = true
    try {
      task.value = await getTask(id)
      status.value = await getTaskStatus(id)
      if (isActiveJobState(status.value.jobState)) {
        startPolling(id)
      } else {
        stopPolling()
      }
    } finally {
      loading.value = false
    }
  }

  function reset() {
    stopPolling()
    task.value = null
    status.value = null
  }

  return { task, status, loading, load, refreshStatus, startPolling, stopPolling, reset }
})
