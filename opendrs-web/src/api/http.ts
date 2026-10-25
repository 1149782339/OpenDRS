import axios, { type AxiosRequestConfig } from 'axios'
import { i18n } from '@/i18n'

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
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  timeout: 60_000,
})

// 语言跟全局 i18n 走：后端按 Accept-Language 返回对应语言的 message
client.interceptors.request.use((config) => {
  config.headers.set('Accept-Language', i18n.global.locale.value)
  return config
})

client.interceptors.response.use(
  (response) => {
    const body = response.data as ApiEnvelope<unknown>
    if (body && typeof body === 'object' && typeof body.code === 'number') {
      if (body.code === SUCCESS_CODE) {
        return { ...response, data: body.data }
      }
      const error = new Error(body.message || i18n.global.t('http.requestFailed')) as Error & {
        code?: number
      }
      error.code = body.code
      return Promise.reject(error)
    }
    return response
  },
  (error: unknown) => {
    if (axios.isAxiosError(error)) {
      const payload = error.response?.data
      return Promise.reject(
        new Error(envelopeMessage(payload, error.message || i18n.global.t('http.networkError'))),
      )
    }
    return Promise.reject(
      error instanceof Error ? error : new Error(i18n.global.t('http.networkError')),
    )
  },
)

export function apiErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return i18n.global.t('http.requestFailed')
}

/** 业务错误码（1001–1006），拦截器挂在 Error 上；非业务错误返回 null。 */
export function apiErrorCode(error: unknown): number | null {
  if (error instanceof Error && typeof (error as unknown as { code?: unknown }).code === 'number') {
    return (error as unknown as { code: number }).code
  }
  return null
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
