/** 后端 Instant 是 UTC 的 ISO 串，列表和向导统一按本地时间显示成 2026/10/18 14:00:58。 */
export function formatDateTime(value: string | number | Date | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const date = toDate(value)
  if (!date) return String(value)
  const pad = (count: number) => String(count).padStart(2, '0')
  return (
    `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

/**
 * 省略秒的时间格式，用在控件触发器这类宽度紧张的地方：
 * 时间区间一行要放得下起止两个时刻，带秒就挤了。
 */
export function formatDateTimeShort(value: string | number | Date | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const date = toDate(value)
  if (!date) return String(value)
  const pad = (count: number) => String(count).padStart(2, '0')
  return (
    `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}

/**
 * 日志表按行看，用短横线（2026-10-19 00:31:12）更接近日志文件的习惯，
 * 也和列表页的斜杠格式区分开，一眼能看出这是流水而不是某个时间字段。
 */
export function formatLogTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (count: number) => String(count).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

/** 数字按 epoch 毫秒理解（epoch 秒要先乘 1000），解析不出来时返回 null。 */
function toDate(value: string | number | Date): Date | null {
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}
