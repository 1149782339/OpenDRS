/** 监控页时间窗口的档位。custom 表示用户在面板里自选的绝对区间。 */
export type RangeKey = '15m' | '30m' | '1h' | 'custom'

export interface RangeWindow {
  key: RangeKey
  /** 触发器上显示的文本。 */
  label: string
  /** 相对窗口的长度（分钟）；自定义区间为 null，改用 start/end。 */
  minutes: number | null
  /** 自定义区间的起止（epoch 秒）；预设档位为 null。 */
  start: number | null
  end: number | null
  /** 采样步长（秒）：与窗口长度一起决定曲线的点数密度。 */
  step: number
}

/** 每条曲线大约的目标点数：点多了传输和绘制都变重，点少了会削掉尖峰。 */
export const TARGET_POINTS = 180

/**
 * 采样步长的上下限（只约束自定义区间：预设档位的步长是写死的固定值，不经过 stepForSpan）。
 * 服务端对自定义区间的 step 钳在 [5,3600]，区间过宽导致单序列点数超过 Prometheus 的
 * 11000 上限时还会自动放大 step；前端先收敛到同一区间，免得发一个服务端会偷偷改掉的请求。
 */
export const MIN_STEP_SECONDS = 5
export const MAX_STEP_SECONDS = 3600

/**
 * 预设档位。
 *
 * minutes 不超过 60：服务端的窗口上限就是 60 分钟，越界会静默退回 15 分钟 ——
 * 用户选了「近2小时」看到的却是近15分钟，而界面上毫无提示。要放开这个上限，
 * 先改服务端 clamp 的语义（越界收敛到边界，而不是退回默认值）。
 */
export const PRESET_RANGES: RangeWindow[] = [
  { key: '15m', label: '近15分钟', minutes: 15, start: null, end: null, step: 5 },
  { key: '30m', label: '近30分钟', minutes: 30, start: null, end: null, step: 10 },
  { key: '1h', label: '近1小时', minutes: 60, start: null, end: null, step: 20 },
]

/** 按窗口长度挑一个步长，让每条曲线的点数都落在 TARGET_POINTS 附近。 */
export function stepForSpan(seconds: number): number {
  const raw = Math.ceil(seconds / TARGET_POINTS)
  return Math.min(MAX_STEP_SECONDS, Math.max(MIN_STEP_SECONDS, raw))
}

/** 窗口的跨度（秒）：自定义区间看起止，预设看长度。 */
export function spanSeconds(window: RangeWindow): number {
  if (window.start !== null && window.end !== null) {
    return Math.max(1, window.end - window.start)
  }
  return (window.minutes ?? 15) * 60
}
