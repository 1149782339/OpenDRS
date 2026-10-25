import { createI18n } from 'vue-i18n'
import zhCN from './locales/zh-CN'
import enUS from './locales/en-US'

const STORAGE_KEY = 'opendrs-locale'

export function getSavedLocale(): string {
  const saved = localStorage.getItem(STORAGE_KEY)
  return saved === 'zh-CN' || saved === 'en-US' ? saved : 'zh-CN'
}

export function saveLocale(locale: string): void {
  localStorage.setItem(STORAGE_KEY, locale)
}

export const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: getSavedLocale(),
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
})

export default i18n
