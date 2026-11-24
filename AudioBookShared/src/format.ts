import dayjs from 'dayjs'

/**
 * 展示层格式化工具（两端共用）。
 *
 * 格式化逻辑集中在此，保证各页面显示口径一致。
 */

/**
 * 时间格式化：'YYYY-MM-DD HH:mm:ss'（本地时区）。
 * 空值返回 '-'；无法解析时原样返回，避免页面出现 Invalid Date。
 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-'
  const d = dayjs(value)
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm:ss') : String(value)
}

/**
 * 时间格式化（精确到分钟）：'YYYY-MM-DD HH:mm'。
 * 表格列等空间有限处使用，秒级精度对「上传时间」类信息没有意义。
 * 空值返回 '-'；无法解析时原样返回。
 */
export function formatDateTimeShort(value: string | null | undefined): string {
  if (!value) return '-'
  const d = dayjs(value)
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm') : String(value)
}

/**
 * 人类可读的文件大小。
 * - null/undefined 返回 '-'
 * - 0 字节返回 '0 B'（不要退化成 '-'）
 * - 其余按 1024 进制给出 KB / MB / GB / TB，保留 1 位小数（B 为整数）
 */
export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes == null) return '-'
  if (bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const k = 1024
  let size = bytes
  let i = 0
  while (size >= k && i < units.length - 1) {
    size /= k
    i++
  }
  return `${i === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[i]}`
}

/**
 * 积分等数值的千分位格式化：1234567 → '1,234,567'。
 * null/undefined 返回 '0'。
 */
export function formatCredits(n: number | string | null | undefined): string {
  if (n == null) return '0'
  // 不能直接 n.toLocaleString()：调用点（如antd 的 Table column `text: any`）
  // 可能传进来的是**字符串**（后端把 BigDecimal 序列化成字符串时），
  // 那样会抛 TypeError 并让整列渲染失败 ⇒ 整页白屏。
  const v = typeof n === 'number' ? n : Number(n)
  return Number.isFinite(v) ? v.toLocaleString('en-US') : '0'
}

/**
 * 人类可读的时长：秒 → 分钟 → 小时（不足 1 分钟用秒，不足 1 小时用分钟）。
 * 用于任务耗时估算的展示。
 */
export function formatDuration(seconds: number): string {
  const s = Number(seconds) || 0
  if (s < 60) return `${Math.round(s)} 秒`
  if (s < 3600) return `${Math.round(s / 60)} 分钟`
  return `${(s / 3600).toFixed(1)} 小时`
}
