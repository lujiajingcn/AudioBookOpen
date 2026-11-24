/**
 * Web 端单词本（服务端为准 + 本地缓存）。
 *
 * 与 favorites.ts 同族：服务端 `/api/word-book` 是唯一真源，localStorage 只作离线缓存
 * 与即时 UI 反馈；增删采用「乐观更新 + 后台落库」，落库失败保留 pending，下次同步补传。
 *
 * 跨端契约（服务端 WordBookItemRequest / WordBookService.toItem 已实现，本端只消费）：
 *  - 唯一身份 = `sw`（stripWord 归一化，一词一条），服务端按 sw 幂等 upsert；
 *  - `translations` 是 **LF(\n) 分隔的字符串**（不是数组）。DictPanel 拿到 `string[]`，
 *    收藏上报前 `join('\n')`，展示时 `split('\n')`；
 *  - 出处 6 键 `bookName/bookKey/storeBookId/segmentNumber/lineNumber/sentenceText`
 *    **全部可空**：旧数据 / 服务端返回 null 时单词本「跳转到原书」必须禁用并提示，
 *    绝不崩、绝不白屏。因此上传（toWire）务必 6 键齐带、拉回（normalizeItem）务必解析 6 键。
 *
 * 服务端按「最近一次带出处的上报」刷新出处；旧客户端不带出处时保留原值。故 Web 端
 * add/同步一律带出处（即便值为 null 也显式上报，服务端 hasSource() 据此判定）。
 */

import { ref } from 'vue'
import { message } from 'ant-design-vue'
import apiClient from './apiClient'
import { useAuthStore } from '@/stores/auth'

/** 单词本条目（与服务端 `toItem` 输出逐字段对齐） */
export interface WordBookItem {
  id: number
  sw: string
  /** 展示用原词（保留大小写与连字符），不是发音/去重文本 */
  word: string
  /** 音标，可能为空串 */
  phonetic: string
  /** 释义：LF(\n) 分隔的**字符串**（wire 约定），展示时 split('\n') */
  translations: string
  createdAt: string
  updatedAt: string | null
  // ---- 收藏出处 6 键（全部可空）----
  /** 书名/展示名 */
  bookName: string | null
  /** 跨端统一书键 `s:{storeBookId}` / `t:{taskId}` / `n:{归一化书名}`；Web 书城书恒为 `s:{bookId}` */
  bookKey: string | null
  /** 书城书 id；本地书为 null ⇒ Web 端无法打开，跳转必须禁用 */
  storeBookId: number | null
  /** 段号，1-based */
  segmentNumber: number | null
  /** 行号 hint（会随切分粒度漂移，仅兜底） */
  lineNumber: number | null
  /** 收藏时所在句子/段落原文 —— 定位第一判据，优先于段号 */
  sentenceText: string | null
  /** 仅本地存在、尚未同步到服务端（离线新增 / 上云失败）时置 true */
  pending?: boolean
}

/** 收藏出处（与服务端 WordBookItemRequest 的 6 个可空字段逐字对齐） */
export interface WordBookSource {
  bookName: string | null
  bookKey: string | null
  storeBookId: number | null
  segmentNumber: number | null
  lineNumber: number | null
  sentenceText: string | null
}

/** add() 入参：词条快照 + 出处。translations 收数组，内部 join('\n') 落 wire */
export interface AddWordInput extends WordBookSource {
  word: string
  phonetic: string
  translations: string[]
}

interface WordBookListResponse {
  success: boolean
  words?: WordBookItem[]
  total?: number
  imported?: number
}

interface AddWordResponse {
  success: boolean
  word?: WordBookItem
  message?: string
}

const CACHE_KEY = 'audiobook_wordbook_v1'
/** 本地缓存归属账号：共享浏览器换账号时清缓存，避免把上一账号的单词本显示/上传给当前账号 */
const OWNER_KEY = 'audiobook_wordbook_owner'
/** 本地缓存条数上限（服务端不限，避免 localStorage 写满） */
const MAX_ITEMS = 2000

/** 缓存变更版本号：增删/同步后自增，供 UI 响应式刷新与「是否已收藏」重算 */
export const wordBookVersion = ref(0)

let cache: WordBookItem[] = readJSON()
let loaded = false
let inflight: Promise<void> | null = null

/**
 * 归一化服务端/本地读回的条目：把 6 个可空出处键、translations、updatedAt 统一补齐。
 * 旧缓存可能没有这些字段（undefined），组件判空统一用 `== null` 也能兜住，但这里补齐后
 * 类型与运行时更一致 —— 呼应契约「拉回必须解析 6 键」。
 */
function normalizeItem(raw: WordBookItem): WordBookItem {
  return {
    ...raw,
    phonetic: raw.phonetic ?? '',
    translations: typeof raw.translations === 'string' ? raw.translations : '',
    updatedAt: raw.updatedAt ?? null,
    bookName: raw.bookName ?? null,
    bookKey: raw.bookKey ?? null,
    storeBookId: raw.storeBookId ?? null,
    segmentNumber: raw.segmentNumber ?? null,
    lineNumber: raw.lineNumber ?? null,
    sentenceText: raw.sentenceText ?? null,
  }
}

function readJSON(): WordBookItem[] {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed)
      ? (parsed as WordBookItem[]).filter(isValidItem).map(normalizeItem)
      : []
  } catch {
    return []
  }
}

function isValidItem(v: unknown): v is WordBookItem {
  if (!v || typeof v !== 'object') return false
  const i = v as WordBookItem
  return typeof i.sw === 'string' && typeof i.word === 'string'
}

function writeCache(list: WordBookItem[]) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(list))
  } catch (e) {
    // 缓存写满/被禁用都不影响正确性（服务端才是真源），仅告警
    console.warn('单词本本地缓存写入失败（不影响云端数据）:', e)
  }
}

function bump() {
  writeCache(cache)
  wordBookVersion.value++
}

function trim() {
  if (cache.length > MAX_ITEMS) cache = cache.slice(0, MAX_ITEMS)
}

/**
 * sw = stripWord 归一化：**逐字符去非字母数字 + 小写**。
 *
 * 与 Android `DictionaryManager.stripWord`（`ch.isLetterOrDigit()` + `lowercaseChar()`）、
 * PC `_sw_key`（`ch.isalnum()` + `ch.lower()`）、服务端建库脚本 `strip_word()` 逐字符一致。
 * JS 侧用 Unicode 属性转义 `/[\p{L}\p{N}]/u` 对应 `isLetterOrDigit/isalnum` 的 Unicode 语义，
 * 用 `Array.from` 按 code point 逐字符（与 Python 一致；Kotlin 按 UTF-16 code unit，
 * 差异只落在 BMP 外的扩展汉字/emoji，而单词本里的词是英文词或 BMP 内汉字，不受影响）。
 *
 * 单测级 golden（改实现前务必逐条核对，跨端去重重全靠它）：
 *   stripWord('Hello')    === 'hello'
 *   stripWord('take-off') === 'takeoff'
 *   stripWord("don't")    === 'dont'
 *   stripWord("'hood")    === 'hood'      （与 'Hood' / 'hood' 同 sw，一词一条）
 *   stripWord('MP3')      === 'mp3'
 *   stripWord('Café')     === 'café'      （Unicode 字母保留，不能只留 ASCII）
 *   stripWord('，。！？')  === ''          （CJK 标点非字母数字）
 *   stripWord('...')      === ''
 * 纯符号/空白得到空串 ⇒ 调用方须拒绝收藏，不能拿空 sw 去撞服务端 @NotBlank。
 */
export function stripWord(text: string): string {
  let out = ''
  for (const ch of Array.from(text || '')) {
    if (/[\p{L}\p{N}]/u.test(ch)) out += ch.toLowerCase()
  }
  return out
}

/** 当前登录用户名（未初始化 pinia 或未登录时返回空串） */
function currentUsername(): string {
  try {
    return useAuthStore().username || ''
  } catch {
    return ''
  }
}

/** 账号归属守卫：归属账号变化时清空本地缓存，服务端同步会重新拉取当前账号的数据 */
function ensureOwner() {
  const me = currentUsername()
  if (!me) return
  let owner = ''
  try {
    owner = localStorage.getItem(OWNER_KEY) || ''
  } catch {
    owner = ''
  }
  if (owner && owner !== me) {
    cache = []
    try {
      localStorage.removeItem(CACHE_KEY)
    } catch {
      // 忽略
    }
    wordBookVersion.value++
  }
  try {
    localStorage.setItem(OWNER_KEY, me)
  } catch {
    // 忽略
  }
}

/**
 * 上传 wire 对象：6 个出处键**一律带上**（即便为 null）。
 *
 * JSON.stringify 会保留 `null` 值 ⇒ 服务端 `WordBookItemRequest.hasSource()` 由
 * 「bookName 或 bookKey 非空」判定是否刷新出处；显式 null 时服务端保留既有出处，
 * 带出处时按「最近一次」刷新。这一步是契约「上传务必带出处」的落点。
 */
function toWire(i: WordBookItem) {
  return {
    sw: i.sw,
    word: i.word,
    phonetic: i.phonetic,
    translations: i.translations,
    bookName: i.bookName,
    bookKey: i.bookKey,
    storeBookId: i.storeBookId,
    segmentNumber: i.segmentNumber,
    lineNumber: i.lineNumber,
    sentenceText: i.sentenceText,
  }
}

/**
 * 与服务端同步：有 pending 项先经 /word-book/sync 批量补传并拿回合并全量，
 * 否则 GET /word-book 拉全量；以服务端列表覆盖本地缓存。
 * 离线 / 未登录时保留本地缓存并静默返回，不打断阅读。
 */
async function syncFromServer(): Promise<void> {
  ensureOwner()
  const pending = cache.filter((i) => i.pending)
  let synced = false
  try {
    let serverList: WordBookItem[] | null = null
    if (pending.length) {
      const res = await apiClient.post<WordBookListResponse>('/word-book/sync', {
        words: pending.map(toWire),
      })
      if (res.data?.success && Array.isArray(res.data.words)) serverList = res.data.words
    }
    if (!serverList) {
      const res = await apiClient.get<WordBookListResponse>('/word-book')
      if (res.data?.success && Array.isArray(res.data.words)) serverList = res.data.words
    }
    if (serverList) {
      cache = serverList.slice(0, MAX_ITEMS).map((i) => ({ ...normalizeItem(i), pending: false }))
      writeCache(cache)
      synced = true
    }
  } catch {
    // 离线 / 未登录 / 服务端异常：保留本地缓存（pending 项下次再传）
  } finally {
    // 仅成功同步才标记已加载：失败时下次进入会自动重试
    if (synced) loaded = true
    wordBookVersion.value++
  }
}

export const wordBook = {
  /** 当前缓存的单词列表（同步读取，供模板直接渲染） */
  list(): WordBookItem[] {
    return cache
  },

  /** 是否已收藏（身份 = sw，与服务端唯一键一致） */
  has(sw: string): boolean {
    return cache.some((i) => i.sw === sw)
  },

  /** 按 sw 查找条目（供取消收藏时取 id / 出处用） */
  find(sw: string): WordBookItem | undefined {
    return cache.find((i) => i.sw === sw)
  },

  /** 首次进入时拉取云端单词本；并发调用共享同一次请求 */
  async ensureLoaded(): Promise<void> {
    if (loaded) return
    if (!inflight) inflight = syncFromServer().finally(() => (inflight = null))
    await inflight
  },

  /** 强制从服务端刷新（下拉重试 / 跨端同步时机） */
  async refresh(): Promise<void> {
    if (!inflight) inflight = syncFromServer().finally(() => (inflight = null))
    await inflight
  },

  /**
   * 加入单词本。乐观更新本地 + 后台落库；返回是否新增（已存在返回 false）。
   * 已存在时不再上报刷新出处 —— Web 端按钮此时呈「★ 移出」，再次点击走 remove，
   * 与 Android「再次点星移出」一致（一词一条，避免二次添加产生歧义）。
   */
  async add(input: AddWordInput): Promise<boolean> {
    const sw = stripWord(input.word)
    if (!sw) {
      message.error('无法收藏该词（归一化后为空）')
      return false
    }
    if (wordBook.has(sw)) return false

    const optimistic: WordBookItem = {
      id: -Date.now(), // 负 id = 尚未上云
      sw,
      word: input.word.trim() || input.word,
      phonetic: input.phonetic,
      // 数组 → LF 分隔字符串（wire 约定），展示端再 split('\n')
      translations: input.translations.join('\n'),
      createdAt: new Date().toISOString(),
      updatedAt: null,
      bookName: input.bookName,
      bookKey: input.bookKey,
      storeBookId: input.storeBookId,
      segmentNumber: input.segmentNumber,
      lineNumber: input.lineNumber,
      sentenceText: input.sentenceText,
      pending: true,
    }
    cache.unshift(optimistic)
    trim()
    bump()

    try {
      const res = await apiClient.post<AddWordResponse>('/word-book', toWire(optimistic))
      const saved = res.data?.word
      if (res.data?.success && saved) {
        // 用服务端记录替换乐观项；服务端可能返回既有记录，故按 sw 清理后再插入
        cache = cache.filter((i) => i !== optimistic && i.sw !== saved.sw)
        cache.unshift({ ...normalizeItem(saved), pending: false })
        trim()
        bump()
      }
      return true
    } catch (e) {
      // 失败（离线/网络）：保留 pending 项，稍后自动补传，不会丢
      console.warn('单词本上云失败，已保留在本地待同步:', e)
      message.warning('已保存在本地，联网后会自动同步')
      return true
    }
  },

  /**
   * 移出单词本。乐观本地删除 + 后台落库；未上云的 pending 项只删本地。
   * 有服务端 id 走 DELETE /word-book/{id}；没有有效 id（升级迁移只有 sw 的老数据）
   * 走 DELETE /word-book/by-sw/{sw} —— 这正是服务端该降级端点的设计用途。
   */
  async remove(item: WordBookItem): Promise<void> {
    const cur = cache.find((i) => i.sw === item.sw) ?? item
    cache = cache.filter((i) => i.sw !== item.sw)
    bump()

    if (cur.pending || cur.id < 0) return // 尚未上云，无需通知服务端
    try {
      if (cur.id > 0) {
        await apiClient.delete(`/word-book/${cur.id}`)
      } else {
        await apiClient.delete(`/word-book/by-sw/${encodeURIComponent(cur.sw)}`)
      }
    } catch (e) {
      // 删除失败：本地已移除；下次同步会因服务端仍有该条而重新拉回，属可接受的最终一致
      console.warn('移出单词本未同步到服务端:', e)
    }
  },
}
