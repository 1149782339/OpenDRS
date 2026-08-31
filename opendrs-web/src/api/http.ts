import axios, { type AxiosRequestConfig } from 'axios'

const SUCCESS_CODE = 1000

export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

function envelopeMessage(payload: unknown, fallback: string): string {
  if (payload && typeof payload === 'object' && 'message' in payload) {
    const message = (payload as { message?: unknown }).message
    if (typeof message === 'string' && message.length > 0) {
      return message
    }
  }
  return fallback
}

const client = axios.create({
  timeout: 60_000,
})

client.interceptors.response.use(
  (response) => {
    const body = response.data as ApiEnvelope<unknown>
    if (body && typeof body === 'object' && typeof body.code === 'number') {
      if (body.code === SUCCESS_CODE) {
        return { ...response, data: body.data }
      }
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return response
  },
  (error: unknown) => {
    if (axios.isAxiosError(error)) {
      const payload = error.response?.data
      return Promise.reject(new Error(envelopeMessage(payload, error.message || '网络错误')))
    }
    return Promise.reject(error instanceof Error ? error : new Error('网络错误'))
  },
)

export function apiErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return '请求失败'
}

export const http = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return client.get(url, config).then((response) => response.data as T)
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return client.post(url, data, config).then((response) => response.data as T)
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return client.delete(url, config).then((response) => response.data as T)
  },
}
