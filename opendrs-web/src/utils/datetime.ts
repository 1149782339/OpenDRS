/** 后端 Instant 是 UTC 的 ISO 串，列表和向导统一按本地时间显示成 2026/10/18 14:00:58。 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (count: number) => String(count).padStart(2, '0')
  return (
    `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
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
