/**
 * Web 端听书收藏（服务端为准 + 本地缓存）。
 *
 * 收藏上云：服务端 /api/favorites 是唯一真源，localStorage 仅作离线缓存与即时 UI 反馈。
 * 这样换设备 / 换浏览器 / 清缓存都不再丢收藏，且与 Android 端共用同一份数据。
 *
 * 关键设计：
 * - 唯一身份 = 书名 + 段号（服务端同此口径；taskId 只作「去听」深链辅助，不参与去重）——
 *   否则 Web 端（带 taskId）与 Android 端（无 taskId）对同一段会各存一条、跨端去不掉重。
 * - 未同步项（升级迁移 / 离线新增）标 pending，同步时经 /favorites/sync 一次性上报合并，
 *   上传成功后才清掉旧 key，保证老用户的本地收藏不会因改造而丢失。
 * - 增删采用「乐观更新 + 后台落库」：UI 立即响应；落库失败时本地保留（pending），
 *   下次同步自动补传，绝不静默丢数据。
 */

import { ref } from 'vue'
import { message } from 'ant-design-vue'
import apiClient from './apiClient'
import { useAuthStore } from '@/stores/auth'

export interface FavoriteItem {
  id: number
  bookName: string
  taskId?: number
  segmentNumber: number
  content: string
  createdAt: string
  /** 仅本地存在、尚未同步到服务端（离线新增 / 升级迁移）时置 true */
  pending?: boolean
}

interface FavoriteListResponse {
  success: boolean
  favorites?: FavoriteItem[]
  total?: number
}

interface AddFavoriteResponse {
  success: boolean
  favorite?: FavoriteItem
  message?: string
}

/** 新版缓存 key（与旧版本地区分：旧 key 一次性迁移后清除） */
const CACHE_KEY = 'audiobook_favorites_v2'
/** 改造前的纯本地 key：首次登录时并入并上传后清除 */
const LEGACY_KEY = 'audiobook_favorites'
/** 本地缓存条数上限（服务端不限；此处只为避免 localStorage 写满） */
const MAX_ITEMS = 1000
/**
 * 本地缓存归属的账号名。
 * 共享浏览器换账号登录时，若不清掉上一个账号的缓存，会短暂（同步失败时则一直）
 * 显示他人收藏。这里记录归属并在账号变化时清空本地缓存，服务端同步会重新拉取当前账号的数据。
 */
const OWNER_KEY = 'audiobook_favorites_owner'

/** 缓存变更版本号：增删/同步后自增，供 UI 响应式刷新与「是否已收藏」重算 */
export const favoritesVersion = ref(0)

let cache: FavoriteItem[] = readJSON(CACHE_KEY)
let loaded = false
let inflight: Promise<void> | null = null

function readJSON(key: string): FavoriteItem[] {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as FavoriteItem[]).filter(isValidItem) : []
  } catch {
    return []
  }
}

function isValidItem(v: unknown): v is FavoriteItem {
  if (!v || typeof v !== 'object') return false
  const f = v as FavoriteItem
  return typeof f.bookName === 'string' && typeof f.segmentNumber === 'number' && typeof f.content === 'string'
}

function writeCache(list: FavoriteItem[]) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(list))
  } catch (e) {
    // 缓存写满/被禁用都不影响正确性（服务端才是真源），仅告警
    console.warn('收藏本地缓存写入失败（不影响云端数据）:', e)
  }
}

/** 唯一身份键：书名 + 段号 */
function keyOf(bookName: string, segmentNumber: number): string {
  return `${bookName}\u0000${segmentNumber}`
}

/** 当前登录用户名（未初始化 pinia 或未登录时返回空串） */
function currentUsername(): string {
  try {
    return useAuthStore().username || ''
  } catch {
    return ''
  }
}

/**
 * 账号归属守卫：本地缓存记录的归属账号与当前登录账号不一致时清空本地缓存
 * （含旧版本地 key），避免共享浏览器把上一个账号的收藏展示/上传给当前账号。
 */
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
      localStorage.removeItem(LEGACY_KEY)
    } catch {
      // 忽略
    }
    favoritesVersion.value++
  }
  try {
    localStorage.setItem(OWNER_KEY, me)
  } catch {
    // 忽略
  }
}

function bump() {
  writeCache(cache)
  favoritesVersion.value++
}

/** 缓存条数钳制：超出上限丢弃最旧（服务端仍保留，下次同步可拉回） */
function trim() {
  if (cache.length > MAX_ITEMS) cache = cache.slice(0, MAX_ITEMS)
}

/**
 * 与服务端同步：
 * 1) 把旧 key 的本地收藏并入 cache 并标记 pending（一次性迁移）；
 * 2) 上报所有 pending 项（若有），服务端合并去重后返回全量；
 * 3) 以服务端列表覆盖本地缓存；上传成功后清除旧 key。
 * 离线 / 未登录时保留本地缓存并静默返回，不打断听书。
 */
async function syncFromServer(): Promise<void> {
  ensureOwner() // 换账号先清掉旧账号缓存，避免串号

  const legacy = readJSON(LEGACY_KEY)
  if (legacy.length) {
    for (const l of legacy) {
      const exists = cache.some((c) => keyOf(c.bookName, c.segmentNumber) === keyOf(l.bookName, l.segmentNumber))
      if (!exists) cache.push({ ...l, pending: true })
    }
    writeCache(cache)
  }

  const pending = cache.filter((i) => i.pending)
  let synced = false
  try {
    let serverList: FavoriteItem[] | null = null
    if (pending.length) {
      // 一次性上报并合并，服务端返回合并后的全量列表（省一次 GET）
      const res = await apiClient.post<FavoriteListResponse>('/favorites/sync', {
        favorites: pending.map((i) => ({
          bookName: i.bookName,
          segmentNumber: i.segmentNumber,
          content: i.content,
          taskId: i.taskId ?? null,
        })),
      })
      if (res.data?.success && Array.isArray(res.data.favorites)) {
        serverList = res.data.favorites
      }
    }
    if (!serverList) {
      const res = await apiClient.get<FavoriteListResponse>('/favorites')
      if (res.data?.success && Array.isArray(res.data.favorites)) {
        serverList = res.data.favorites
      }
    }
    if (serverList) {
      cache = serverList.slice(0, MAX_ITEMS).map((i) => ({ ...i, pending: false }))
      writeCache(cache)
      synced = true
      // 迁移成功后才清旧 key，确保失败时可重试、数据不丢
      if (legacy.length) {
        try {
          localStorage.removeItem(LEGACY_KEY)
        } catch {
          // 忽略
        }
      }
    }
  } catch {
    // 离线 / 未登录 / 服务端异常：保留本地缓存（pending 项下次再传）
  } finally {
    // 仅成功同步才标记已加载：失败时下次进入听书页/我的页会自动重试，而不是永久停用云端同步
    if (synced) loaded = true
    favoritesVersion.value++
  }
}

export const favorites = {
  /** 当前缓存的收藏列表（同步读取，供模板直接渲染） */
  list(): FavoriteItem[] {
    return cache
  },

  /** 是否已收藏（身份 = 书名 + 段号，与去重口径一致） */
  has(bookName: string, segmentNumber: number): boolean {
    return cache.some((f) => keyOf(f.bookName, f.segmentNumber) === keyOf(bookName, segmentNumber))
  },

  /** 按身份查找收藏项（供取消收藏时取 id 用） */
  find(bookName: string, segmentNumber: number): FavoriteItem | undefined {
    return cache.find((f) => keyOf(f.bookName, f.segmentNumber) === keyOf(bookName, segmentNumber))
  },

  /** 首次进入（听书页/我的页）时拉取云端收藏；并发调用共享同一次请求 */
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
   * 添加收藏。乐观更新本地 + 后台落库；返回是否新增（已存在返回 false）。
   * 落库失败时本地保留为 pending，下次同步自动补传，不会丢。
   */
  async add(bookName: string, segmentNumber: number, content: string, taskId?: number): Promise<boolean> {
    const trimmed = content.trim()
    if (!trimmed) {
      message.error('收藏内容为空，无法收藏')
      return false
    }
    if (favorites.has(bookName, segmentNumber)) return false

    const optimistic: FavoriteItem = {
      id: -Date.now(), // 负 id = 尚未上云
      bookName,
      taskId,
      segmentNumber,
      content: trimmed,
      createdAt: new Date().toISOString(),
      pending: true,
    }
    cache.unshift(optimistic)
    trim()
    bump()

    try {
      const res = await apiClient.post<AddFavoriteResponse>('/favorites', {
        bookName,
        segmentNumber,
        content: trimmed,
        taskId: taskId ?? null,
      })
      const fav = res.data?.favorite
      if (res.data?.success && fav) {
        // 用服务端记录替换乐观项；服务端可能返回既有记录，故先按身份清理再插入
        cache = cache.filter(
          (i) => i !== optimistic && keyOf(i.bookName, i.segmentNumber) !== keyOf(fav.bookName, fav.segmentNumber),
        )
        cache.unshift({ ...fav, pending: false })
        trim()
        bump()
      }
      return true
    } catch (e) {
      // 失败（离线/网络）：保留 pending 项，稍后自动补传
      console.warn('收藏上云失败，已保留在本地待同步:', e)
      message.warning('收藏已保存在本地，联网后会自动同步')
      return true
    }
  },

  /** 取消收藏。乐观本地删除 + 后台落库；未上云的 pending 项只删本地。 */
  async remove(id: number): Promise<void> {
    const item = cache.find((i) => i.id === id)
    if (!item) return
    cache = cache.filter((i) => i.id !== id)
    bump()

    if (item.pending || id < 0) return // 尚未上云，无需通知服务端
    try {
      await apiClient.delete(`/favorites/${id}`)
    } catch (e) {
      // 删除失败：本地已移除；下次同步会因服务端仍有该条而重新拉回，属于可接受的最终一致
      console.warn('取消收藏未同步到服务端:', e)
    }
  },
}
