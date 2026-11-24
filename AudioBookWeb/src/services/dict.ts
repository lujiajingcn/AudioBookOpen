/**
 * 离线词典查询（Web 阅读页长按「翻译」「查字典」）。
 *
 * 与 Android 端的差别只有一处：Android 把 `dict.db` 下载到本地用 SQLiteDatabase 直查，
 * 浏览器没有可用的 SQLite，所以由服务端代查（`GET /api/app/dict/lookup`、`/char`）。
 * 两端口径一致 —— 同一份词典库、同一套 sw 归一与释义切分规则。
 *
 * 端点免鉴权（与 `/api/app/dict/{info,download}` 同源理由：词典与账号无关），
 * 但 apiClient 仍会带上凭据 Cookie，登录与否都能查。
 */
import apiClient from './apiClient'

/** 英汉词条（ECDICT） */
export interface DictEntry {
  word: string
  /** 音标，可能为空串 */
  phonetic: string
  /** 释义，已由服务端按「字面量 \n」切成多行（客户端不必再关心那个转义约定） */
  translations: string[]
  /** 词形变化的原始串，如 `p:ran/i:running/d:run/3:runs`；展示前需格式化 */
  exchange: string
  /** 考试大纲标签，如 CET4 / CET6 / KY / TOEFL */
  tags: string[]
  /** 柯林斯星级（0~5），0 表示无 */
  collins: number
  /** 牛津 3000 核心词（1 = 是） */
  oxford: number
}

/** 中文单字词条（新华字典）。多音字会有多条，每条一个读音 */
export interface ChineseEntry {
  pinyin: string
  definitions: string[]
}

export interface DictLookupResponse {
  success: boolean
  /** 词典是否已部署；false 表示服务端没装词典，不是「查无此词」 */
  available: boolean
  query: string
  /** 归一化后的查询键（去符号 + 小写），排查查不到时看它 */
  normalized: string
  entries: DictEntry[]
}

export interface ChinLookupResponse {
  success: boolean
  available: boolean
  /** 服务端认可的「单字」；传入多字时为空串 */
  char: string
  entries: ChineseEntry[]
}

export const dictApi = {
  /** 查英汉词条。查无此词返回 `entries: []`（不是错误） */
  lookup: (word: string) =>
    apiClient.get<DictLookupResponse>('/app/dict/lookup', {
      params: { word },
      // 首次查询服务端可能要解压 55MB 词典库（1~2 秒），别用默认 30s 以外的短超时
      timeout: 20000,
    }),

  /** 查中文单字。非单字返回 `entries: []` */
  lookupChar: (c: string) =>
    apiClient.get<ChinLookupResponse>('/app/dict/char', {
      params: { c },
      timeout: 20000,
    }),
}

/**
 * 判断选区是否是可查的英文词（与 Android `DictionaryManager.isEnglishWord` 同口径）。
 *
 * 含 CJK / 假名 / 谚文 ⇒ 不是；去掉非字母数字后为空（纯符号）⇒ 不是。
 * 这个判定同时决定「翻译」菜单项是否出现 —— 两处分叉就会出现
 * 「菜单项在、点了却查不到」这种最差的形态。
 */
export function isEnglishWord(text: string): boolean {
  if (!text || !text.trim()) return false
  for (const ch of text) {
    const code = ch.codePointAt(0) ?? 0
    if (
      (code >= 0x2e80 && code <= 0x9fff) ||
      (code >= 0xac00 && code <= 0xd7af) ||
      (code >= 0xf900 && code <= 0xfaff)
    ) {
      return false
    }
  }
  return /[\p{L}\p{N}]/u.test(text)
}

/** 汉字判定（基本区 + 扩展 A，与客户端/服务端同口径） */
export function isCjkChar(ch: string): boolean {
  const code = ch.codePointAt(0) ?? 0
  return (code >= 0x4e00 && code <= 0x9fff) || (code >= 0x3400 && code <= 0x4dbf)
}

/**
 * 从选区文本里取出唯一的汉字（「查字典」只对单字有意义）。
 *
 * 选区可能带前后标点（长按手柄容易越界），所以先挑出汉字再判个数。
 * 0 个或 2 个及以上都返回 null。
 */
export function singleCjkChar(text: string): string | null {
  if (!text) return null
  const chars = Array.from(text).filter((c) => isCjkChar(c))
  return chars.length === 1 ? chars[0] : null
}

/**
 * 词形变化的中文标签（ECDICT 的 `exchange` 键含义）。
 *
 * `p` 过去式 / `d` 过去分词 / `i` 现在分词 / `3` 第三人称单数 /
 * `r` 比较级 / `t` 最高级 / `s` 复数 / `0` 原形 / `1` 变换形式。
 */
const EXCHANGE_LABELS: Record<string, string> = {
  p: '过去式',
  d: '过去分词',
  i: '现在分词',
  '3': '第三人称单数',
  r: '比较级',
  t: '最高级',
  s: '复数',
  '0': '原形',
  '1': '变换形式',
}

/**
 * 把 `exchange` 格式化成「过去式：ran，现在分词：running」这样的人话。
 *
 * 同一个形态可能对应多个键（`runs` 既是三单也是复数，因为 run 的两者同形），
 * 所以按形态聚合标签，而不是一个形态只取第一个。
 */
export function formatExchange(exchange: string, word: string): string {
  if (!exchange) return ''
  const byForm = new Map<string, string[]>()
  for (const part of exchange.split('/')) {
    const idx = part.indexOf(':')
    if (idx <= 0) continue
    const key = part.slice(0, idx)
    const form = part.slice(idx + 1).trim()
    const label = EXCHANGE_LABELS[key]
    if (!label || !form) continue
    // 原形/变换形式这两类对读者没有信息量（都是词本身），跳过
    if (key === '0' || key === '1') continue
    if (form === word) continue
    const list = byForm.get(form) ?? []
    if (!list.includes(label)) list.push(label)
    byForm.set(form, list)
  }
  return Array.from(byForm.entries())
    .map(([form, labels]) => `${labels.join('/')}：${form}`)
    .join('，')
}
