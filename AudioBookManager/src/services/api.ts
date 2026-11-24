import apiClient from './apiClient'
import type { AxiosResponse } from 'axios'
import { createAuthApi } from '@audiobook/shared'
import type { ApiResult, CreditPackage, CreditTransaction, StoreBook } from '@audiobook/shared'

// 类型定义统一放在 @audiobook/shared，这里重新导出以保持视图层既有 import 不变
export type {
  ApiResult,
  StoreBook,
  CreditPackage,
  CreditTransaction,
  AuthResponse,
} from '@audiobook/shared'

// ============================================================
// 类型定义 —— 与后端 AdminController / AdminCreditController /
// AuthController 的 JSON 返回结构保持一致。
// ============================================================

export interface AdminUser {
  id: number
  username: string
  email?: string | null
  isAdmin: boolean
  credits: number
  bannedUntil?: string | null
  createdAt?: string | null
}

export interface AdminReferenceAudio {
  id: number
  name: string
  description?: string | null
  fileName?: string | null
  fileSize?: number | null
  /** 所属主题 id；null / 缺省 = 未归类 */
  topicId?: number | null
  createdAt?: string | null
}

/**
 * 参考音频主题（GET /admin/reference-audio-topics）。
 *
 * 用来把同源的一批参考音频归到一起管理，典型场景是「电视剧雍正王朝」，
 * 主题下挂着各个角色的台词片段。
 */
export interface AdminReferenceTopic {
  id: number
  name: string
  description?: string | null
  /** 排序值，越小越靠前 */
  sortOrder?: number | null
  /** 该主题下的参考音频数量（服务端一次 GROUP BY 统计后下发） */
  audioCount?: number | null
  createdAt?: string | null
}

/**
 * 预设音色（GET /tts/voices）。id 用作合成入参 voiceId，name 是中文展示名。
 * 清单来自服务端配置 moss.tts.voices（代码里有 7 个默认值，不会为空）。
 */
export interface AdminVoiceOption {
  id: string
  name: string
}

/**
 * MOSS-TTS-Nano 合成节点（GET /admin/tts/nodes）。
 *
 * 前半段是数据库里的持久化配置，后半段是服务进程内的实时运行指标，
 * 后端按 id 关联后一并下发，避免管理端为「看一眼在途数」再发一次请求。
 */
export interface AdminTtsNode {
  id: number
  name: string
  url: string
  /** 权重：最少在途选点中仅在途数相同时作为次级排序，越大越优先 */
  weight: number
  enabled: boolean
  sortOrder: number
  remark?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  /** 当前在途合成请求数 —— 负载均衡的选点依据，也是判断是否分布均匀的唯一指标 */
  inFlight: number
  /** 是否处于熔断冷却中（冷却期内不再分配新请求） */
  coolingDown: boolean
  cooldownRemainingMillis: number
  consecutiveFailures: number
  totalRequests: number
  totalFailures: number
  /** 主动探活结论（TCP 能否建连）；探活关闭时恒为 true */
  reachable: boolean
  lastProbeAt?: number | null
  lastSuccessAt?: number | null
  lastError?: string | null
  /** 服务端推导的展示状态；区分「已停用」与「探活不可达」很重要 */
  status: 'disabled' | 'pending' | 'unreachable' | 'cooling' | 'idle' | 'online'
}

/** GET /admin/tts/nodes 列表 */
export interface AdminTtsNodeListPayload {
  nodes: AdminTtsNode[]
  total: number
  enabledCount: number
  /** 真正参与调度的节点数（已启用 且 未冷却 且 探活正常） */
  schedulableCount: number
  /** app.tts.concurrency：全局在途合成请求上限，与节点数无关 */
  ttsConcurrency: number
  healthCheckEnabled: boolean
  refreshMillis: number
  /** 节点数与并发上限不匹配时的提示文案；匹配时为 null */
  concurrencyHint?: string | null
}

/**
 * 客户端版本（AdminAppVersionController，/api/admin/app-version）。
 *
 * 一条记录对应一个已上传的 APK：前半段是落库的配置/元信息，
 * {@code fileExists} 是服务端现场探测磁盘文件是否还在（人为清理过 uploads/app 时为 false），
 * 用来拦住「发布一个下载必然 404 的版本」。
 */
export interface AdminAppVersion {
  id: number
  platform: string
  /** 单调递增的版本号，客户端据此判断有无更新；也是同平台「当前版本」唯一性的依据 */
  versionCode: number
  /** 展示版本号（会拼进下载文件名，服务端做了字符白名单校验） */
  versionName: string
  /** uploads/app 下的相对存储路径 */
  fileName?: string | null
  fileSize?: number | null
  md5?: string | null
  changelog?: string | null
  /** 是否强制更新 */
  forceUpdate: boolean
  /** 是否为对外发布的「当前版本」 */
  released: boolean
  createdAt?: string | null
  releasedAt?: string | null
  /** 磁盘上安装包是否仍存在；false 时发布会被后端拒绝 */
  fileExists: boolean
}

/** GET /admin/app-version 列表 */
export interface AdminAppVersionListPayload {
  versions: AdminAppVersion[]
  total: number
}

/** POST /admin/app-version/upload 成功后回传的元信息 */
export interface AdminAppVersionUploadPayload {
  id: number
  versionCode: number
  versionName: string
  fileSize: number
  md5: string
  released: boolean
}

// ============================================================
// 后台接口响应载荷（强类型化 ApiResult<T> 的 T）
// 字段名与后端 AdminController / AdminCreditController 返回的 JSON 键保持一致，
// 直接对齐各视图里既有的 response.data.xxx 读取，去掉 unknown 逼出的强制转换。
// ============================================================

/** GET /admin/users 列表 */
export interface AdminUserListPayload {
  content: AdminUser[]
  totalElements: number
}
/** GET /admin/books 列表 */
export interface AdminBookListPayload {
  content: StoreBook[]
  totalElements: number
}
/** GET /admin/books/categories */
export interface AdminBookCategoriesPayload {
  categories: string[]
}
/** GET /admin/books/tags 已用过的全部标签名（编辑时做联想，不做在售过滤） */
export interface AdminBookTagsPayload {
  tags: string[]
}
/** POST /admin/books 上传成功后返回的落库书籍 */
export interface AdminBookUploadPayload {
  book: StoreBook
}
/** GET /admin/reference-audios 列表 */
export interface AdminReferenceListPayload {
  content: AdminReferenceAudio[]
}
/** GET /admin/reference-audio-topics 列表 */
export interface AdminReferenceTopicListPayload {
  content: AdminReferenceTopic[]
}
/** GET /admin/credits/packages 列表 */
export interface AdminCreditPackagesPayload {
  packages: CreditPackage[]
}
/** GET /admin/credits/transactions 列表 */
export interface AdminCreditTransactionsPayload {
  content: CreditTransaction[]
  totalElements: number
}
/** POST /admin/credits/users/{userId}/adjust 调整积分后返回的最新余额 */
export interface AdminCreditAdjustPayload {
  balance: number
}

// ============================================================
// 认证接口
// ============================================================

// 共享认证接口（login / validateToken / logout 两端一致），逻辑由 @audiobook/shared 提供
export const authApi = createAuthApi(apiClient)

// ============================================================
// 后台管理接口（/api/admin）
// ============================================================

export const adminApi = {
  // -------- 用户 --------
  getUsers(page: number, size: number): Promise<AxiosResponse<ApiResult<AdminUserListPayload>>> {
    return apiClient.get('/admin/users', { params: { page, size } })
  },
  deleteUser(id: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.delete(`/admin/users/${id}`)
  },
  setUserAdmin(id: number, isAdmin: boolean): Promise<AxiosResponse<ApiResult>> {
    return apiClient.put(`/admin/users/${id}/admin`, { isAdmin })
  },
  banUser(id: number, durationMinutes: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.put(`/admin/users/${id}/ban`, { durationMinutes })
  },
  unbanUser(id: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.put(`/admin/users/${id}/unban`)
  },
  resetUserPassword(id: number, newPassword: string): Promise<AxiosResponse<ApiResult>> {
    return apiClient.put(`/admin/users/${id}/password`, { newPassword })
  },

  // -------- 书城书籍 --------
  getBooks(page: number, size: number, keyword?: string): Promise<AxiosResponse<ApiResult<AdminBookListPayload>>> {
    const params: Record<string, unknown> = { page, size }
    if (keyword) params.keyword = keyword
    return apiClient.get('/admin/books', { params })
  },
  deleteBook(id: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.delete(`/admin/books/${id}`)
  },
  /**
   * 上传电子书到书城。
   * 后端按分类落盘到 store_books/{分类}/，分类目录不存在时自动新建。
   * 超时按大文件档位设置：全局默认 10s 会让 50MB 的电子书必然失败。
   */
  uploadBook(
    file: File,
    meta: {
      title?: string
      /** 原书名（选填）：仅存档，不参与落盘文件名与目录命名 */
      originalTitle?: string
      author?: string
      category?: string
      description?: string
      /**
       * 标签，**逗号分隔的一个字符串**（如 "历史,战争"）。
       * 服务端 multipart 收的就是单个 `tags` 字段，解析后再规范化；
       * 用数组会让 FormData 变成 tags[]=a&tags[]=b 的多值形式，服务端收不到。
       */
      tags?: string
    },
    onProgress?: (percent: number) => void,
    signal?: AbortSignal,
  ): Promise<AxiosResponse<ApiResult<AdminBookUploadPayload>>> {
    const form = new FormData()
    form.append('file', file)
    if (meta.title) form.append('title', meta.title)
    if (meta.originalTitle) form.append('originalTitle', meta.originalTitle)
    if (meta.author) form.append('author', meta.author)
    if (meta.category) form.append('category', meta.category)
    if (meta.description) form.append('description', meta.description)
    if (meta.tags) form.append('tags', meta.tags)
    return apiClient.post('/admin/books', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300000,
      onUploadProgress: (e) => {
        if (!onProgress) return
        const percent = e.total ? Math.round((e.loaded / e.total) * 100) : 0
        onProgress(percent)
      },
      signal,
    })
  },
  /** 服务器上已有的书城分类名（磁盘目录 ∪ 数据库已用分类） */
  getBookCategories(): Promise<AxiosResponse<ApiResult<AdminBookCategoriesPayload>>> {
    return apiClient.get('/admin/books/categories')
  },
  updateBook(
    id: number,
    data: { title?: string; author?: string; category?: string; description?: string },
  ): Promise<AxiosResponse<ApiResult>> {
    return apiClient.put(`/admin/books/${id}`, data)
  },
  /** 已用过的全部标签名（编辑/上传时的联想词；不做在售过滤） */
  getBookTags(): Promise<AxiosResponse<ApiResult<AdminBookTagsPayload>>> {
    return apiClient.get('/admin/books/tags')
  },
  /**
   * 全量设置某本书的标签（**提交即最终集合**：没提交的会被删掉，传空数组即清空）。
   * 服务端会规范化（trim / 忽略大小写去重 / 单标签≤20 字 / 最多 10 个）并回传实际生效的值。
   */
  setBookTags(id: number, tags: string[]): Promise<AxiosResponse<ApiResult<AdminBookUploadPayload>>> {
    return apiClient.put(`/admin/books/${id}/tags`, { tags })
  },

  // -------- 书城合成语音 --------
  /**
   * 为书城中指定书发起 TTS 合成（管理员操作，adminExempt 时 0 积分）。
   * 后端通过 {@code POST /api/admin/books/{id}/synthesize} 提交，
   * 返回 { success, bookId, taskId, message }。
   * 入参二选一：
   *   - referenceId：服务端已存在的参考音频文件名（来自 GET /admin/reference-audios）
   *   - voiceId：预设音色 ID（来自 GET /tts/voices，仅登录可用；客户端改走 adminApi 内的就近列表或新 helper）
   *
   * 失败原因（书已合成/已下架/参考音频失效/积分不足等）走 success=false + message 表达，
   * 由前端 Interceptor 之外的 try/catch 负责提示。
   */
  synthesizeBook(
    bookId: number,
    body: { referenceId?: string | null; voiceId?: string | null },
  ): Promise<AxiosResponse<ApiResult<{ bookId: number; taskId: number }>>> {
    return apiClient.post(`/admin/books/${bookId}/synthesize`, body)
  },
  /**
   * 暂停合成：后端落库 paused 并通知工作线程在下一个段落边界收手，
   * 已完成的段与 mp3 全部保留；点「继续合成」即从断点续跑。
   * 仅 pending/queued/processing 可暂停，其余状态返回 400 + message。
   */
  pauseBookSynthesis(bookId: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.post(`/admin/books/${bookId}/synthesize/pause`)
  },
  /**
   * 继续合成：paused → queued 并重新派发，从 completedSegments 断点续跑。
   * 非 paused 状态返回 400 + message（如「本书当前状态为 completed，只有已暂停的合成才能继续」）。
   */
  resumeBookSynthesis(bookId: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.post(`/admin/books/${bookId}/synthesize/resume`)
  },
  /**
   * 继续合成（补齐缺失段）：partial / failed → queued，只重跑 failed / pending 的段。
   *
   * <p>与 {@link resumeBookSynthesis} 的分工：那个只认 paused（清暂停标记后原样续跑），
   * 这个专治「跑到终态但没跑全」—— 失败段在库里是 failed，不重置就直接派发，
   * 工作线程会当作已处理跳过，任务立刻又回到 partial。
   *
   * <p>非 partial / failed 状态返回 400 + message。
   */
  retryBookSynthesis(bookId: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.post(`/admin/books/${bookId}/synthesize/retry`)
  },
  /**
   * 删除这本书已合成的语音：清音频目录、段落记录与任务行，并解绑回「未合成」，可重新发起。
   * 书本身（电子文件/封面/分类）保留。仍在合成中的书会被拒绝，需先暂停。
   */
  deleteBookAudio(bookId: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.delete(`/admin/books/${bookId}/synthesize`)
  },
  /**
   * 批量合成未合成书（task_id IS NULL）。
   * 不传 bookIds 时后端扫描全部 task_id IS NULL 的书；传则仅合成指定 id。
   * 返回 { success, total, submitted, skipped, results: [{bookId, taskId, message}] }。
   */
  batchSynthesize(
    body: {
      referenceId?: string | null
      voiceId?: string | null
      bookIds?: number[] | null
    },
  ): Promise<
    AxiosResponse<
      ApiResult<{
        total: number
        submitted: number
        skipped: number
        results: { submitted?: Array<{ bookId: number; taskId: number; message?: null }>; skipped?: Array<{ bookId: number; taskId: null; message: string }> }
      }>
    >
  > {
    return apiClient.post('/admin/books/batch-synthesize', body)
  },
  /**
   * 批量补齐：只处理「上一轮没跑完」的书（partial / failed），只重跑没成功的段，
   * 已完成的段与 mp3 原样保留。与 batchSynthesize（从头做一版）是两个动作。
   * 不传 bookIds 时后端扫描全部 partial/failed 的书。
   */
  batchGapRetry(
    body: { bookIds?: number[] | null } = {},
  ): Promise<
    AxiosResponse<
      ApiResult<{
        total: number
        submitted: number
        skipped: number
        results: { submitted?: Array<{ bookId: number; taskId: number | null; message?: null }>; skipped?: Array<{ bookId: number; taskId: number | null; message: string }> }
      }>
    >
  > {
    return apiClient.post('/admin/books/batch-gap-retry', body)
  },
  /**
   * 预设音色清单（GET /api/tts/voices），合成弹窗「预设音色」下拉的数据源。
   * 该端点不在 /admin 下、只需登录即可访问，管理员 token 同样可用。
   */
  getVoices(): Promise<AxiosResponse<ApiResult<{ voices: AdminVoiceOption[] }>>> {
    return apiClient.get('/tts/voices')
  },

  // -------- 参考音频 --------
  getReferenceAudios(): Promise<AxiosResponse<ApiResult<AdminReferenceListPayload>>> {
    return apiClient.get('/admin/reference-audios')
  },
  uploadReferenceAudio(
    file: File,
    name?: string,
    description?: string,
    topicId?: number | null,
    onProgress?: (percent: number) => void,
    signal?: AbortSignal,
  ): Promise<AxiosResponse<ApiResult>> {
    const form = new FormData()
    form.append('file', file)
    if (name) form.append('name', name)
    if (description) form.append('description', description)
    // 只在有归属时下发：不传即服务端按「未归类」处理
    if (topicId != null) form.append('topicId', String(topicId))
    return apiClient.post('/admin/reference-audios', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      // 服务端该端点含视频转 WAV（最长约 120s），全局默认 10s 超时
      // 会让管理端上传视频/大音频必然失败。与 Android 端重接口 300s 档位对齐。
      timeout: 300000,
      // e 由 axios 推断为 AxiosProgressEvent；自定义 ProgressEvent & { progress?: number }
      // 与 axios 类型不兼容，会导致 vue-tsc 构建失败
      onUploadProgress: (e) => {
        if (!onProgress) return
        const percent = e.total ? Math.round((e.loaded / e.total) * 100) : 0
        onProgress(percent)
      },
      signal,
    })
  },
  deleteReferenceAudio(id: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.delete(`/admin/reference-audios/${id}`)
  },
  updateReferenceAudio(
    id: number,
    data: { name?: string; description?: string; topicId?: number | null },
  ): Promise<AxiosResponse<ApiResult>> {
    return apiClient.put(`/admin/reference-audios/${id}`, data)
  },

  // -------- 参考音频主题 --------
  getReferenceTopics(): Promise<AxiosResponse<ApiResult<AdminReferenceTopicListPayload>>> {
    return apiClient.get('/admin/reference-audio-topics')
  },
  createReferenceTopic(
    data: { name: string; description?: string; sortOrder?: number },
  ): Promise<AxiosResponse<ApiResult>> {
    return apiClient.post('/admin/reference-audio-topics', data)
  },
  updateReferenceTopic(
    id: number,
    data: { name?: string; description?: string; sortOrder?: number },
  ): Promise<AxiosResponse<ApiResult>> {
    return apiClient.put(`/admin/reference-audio-topics/${id}`, data)
  },
  /** 主题下仍有音频时服务端会拒绝（400），前端把 message 原样透出 */
  deleteReferenceTopic(id: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.delete(`/admin/reference-audio-topics/${id}`)
  },

  // -------- TTS 合成节点（多节点调度） --------
  /*
   * 这些接口的写操作都是即时生效的：后端写入 tts_nodes 表后会立刻刷新本地快照，
   * 下一次合成就用新配置。因此增删机器完全不需要重启 AudioBookServer ——
   * 重启会截断正在进行的合成任务（app.tts.concurrency 个），这正是本功能要避免的。
   */
  /** 节点列表：配置字段 + 实时运行指标（在途数、熔断冷却、探活结果） */
  getTtsNodes(): Promise<AxiosResponse<ApiResult<AdminTtsNodeListPayload>>> {
    return apiClient.get('/admin/tts/nodes')
  },
  createTtsNode(body: {
    name: string
    url: string
    weight?: number
    sortOrder?: number
    remark?: string
  }): Promise<AxiosResponse<ApiResult>> {
    return apiClient.post('/admin/tts/nodes', body)
  },
  /** 局部更新：未提交的字段后端保持原值（null 表示「本次不改」） */
  updateTtsNode(
    id: number,
    body: {
      name?: string
      url?: string
      weight?: number
      sortOrder?: number
      remark?: string
      enabled?: boolean
    },
  ): Promise<AxiosResponse<ApiResult>> {
    return apiClient.put(`/admin/tts/nodes/${id}`, body)
  },
  /**
   * 启用/停用节点。停用是下线一台机器做维护的推荐方式：
   * 立即停止分配新请求，但不打断已经在途的那次调用。
   */
  setTtsNodeEnabled(id: number, enabled: boolean): Promise<AxiosResponse<ApiResult>> {
    return apiClient.put(`/admin/tts/nodes/${id}/enabled`, { enabled })
  },
  /** 物理删除（连同历史统计一起消失）；一般场景请优先用停用 */
  deleteTtsNode(id: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.delete(`/admin/tts/nodes/${id}`)
  },

  // -------- 客户端版本发布（APK 上传 / 发布 / 下线 / 删除） --------
  /*
   * 对应后端 AdminAppVersionController（/api/admin/app-version）。
   * 「当前对外版本」在同平台内唯一：发布某版本时后端会自动下线同平台其它版本，
   * 因此列表里同时处于 released 态的每个平台最多一个。
   */
  /** 版本列表（按 versionCode 倒序），可按平台筛选；不传平台后端按 android 处理 */
  getAppVersions(
    platform?: string,
  ): Promise<AxiosResponse<ApiResult<AdminAppVersionListPayload>>> {
    const params: Record<string, unknown> = {}
    if (platform) params.platform = platform.trim()
    return apiClient.get('/admin/app-version', { params })
  },
  /**
   * 上传新版本 APK。
   * 表单：apk(文件) / versionCode / versionName / changelog? / forceUpdate? / release? / platform?。
   * 容器 multipart 上限 50MB（与参考音频共用），超限会在服务端被拒（413）。
   * versionCode 必须大于该平台当前最大版本号，否则后端返回 400 + message。
   */
  uploadAppVersion(
    file: File,
    meta: {
      versionCode: number
      versionName: string
      changelog?: string
      forceUpdate?: boolean
      release?: boolean
      platform?: string
    },
    onProgress?: (percent: number) => void,
    signal?: AbortSignal,
  ): Promise<AxiosResponse<ApiResult<AdminAppVersionUploadPayload>>> {
    const form = new FormData()
    form.append('apk', file)
    form.append('versionCode', String(meta.versionCode))
    form.append('versionName', meta.versionName)
    if (meta.changelog) form.append('changelog', meta.changelog)
    // 布尔用字符串显式下发：Spring 会把 "true"/"false" 正确解析为 Boolean，缺省则由后端取默认值
    if (meta.forceUpdate != null) form.append('forceUpdate', String(meta.forceUpdate))
    if (meta.release != null) form.append('release', String(meta.release))
    if (meta.platform) form.append('platform', meta.platform.trim())
    return apiClient.post('/admin/app-version/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      // APK 体积大，全局 10s 默认超时必然失败；与电子书/参考音频同用 300s 档位
      timeout: 300000,
      onUploadProgress: (e) => {
        if (!onProgress) return
        const percent = e.total ? Math.round((e.loaded / e.total) * 100) : 0
        onProgress(percent)
      },
      signal,
    })
  },
  /** 发布指定版本：同平台其它版本一并下线，保证「当前版本」唯一 */
  releaseAppVersion(id: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.post(`/admin/app-version/${id}/release`)
  },
  /** 下线指定版本（客户端不再收到该版本的更新提示） */
  unreleaseAppVersion(id: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.post(`/admin/app-version/${id}/unrelease`)
  },
  /** 删除版本记录并清理磁盘 APK（已发布版本也允许删除——删除即下线） */
  deleteAppVersion(id: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.delete(`/admin/app-version/${id}`)
  },
}

// ============================================================
// 积分管理接口（/api/admin/credits）
// ============================================================

export const creditAdminApi = {
  /** 手动调整用户积分：POST /admin/credits/users/{userId}/adjust */
  adjustUser(userId: number, delta: number, remark?: string): Promise<AxiosResponse<ApiResult<AdminCreditAdjustPayload>>> {
    return apiClient.post(`/admin/credits/users/${userId}/adjust`, { delta, remark })
  },

  // -------- 充值套餐 --------
  listPackages(): Promise<AxiosResponse<ApiResult<AdminCreditPackagesPayload>>> {
    return apiClient.get('/admin/credits/packages')
  },
  createPackage(data: Partial<CreditPackage>): Promise<AxiosResponse<ApiResult>> {
    return apiClient.post('/admin/credits/packages', data)
  },
  updatePackage(id: number, data: Partial<CreditPackage>): Promise<AxiosResponse<ApiResult>> {
    return apiClient.put(`/admin/credits/packages/${id}`, data)
  },
  deletePackage(id: number): Promise<AxiosResponse<ApiResult>> {
    return apiClient.delete(`/admin/credits/packages/${id}`)
  },

  // -------- 流水与订单 --------
  listTransactions(
    page = 0,
    size = 20,
    userId?: number,
    type?: string,
  ): Promise<AxiosResponse<ApiResult<AdminCreditTransactionsPayload>>> {
    const params: Record<string, unknown> = { page, size }
    if (userId != null) params.userId = userId
    if (type) params.type = type
    return apiClient.get('/admin/credits/transactions', { params })
  },
}

// ============================================================
// 书城（用户端）接口 —— 管理后台只用到「取封面」
// ============================================================

/** 封面端点路径（与 Web 的 storeApi.getCoverUrl、Android 的 BASE_URL+路径同源） */
const coverUrlOf = (id: number) => `/store/book/${id}/cover`

/**
 * 书城书籍封面（GET /api/store/book/{id}/cover）。
 *
 * <h3>为什么必须走 apiClient 拿 blob，不能塞进 &lt;img src&gt;</h3>
 * ① **凭据**：该端点只需登录（`anyRequest().authenticated()`），但 `<img>` 不会带 {@link
 *    AuthCookieService.APP_ID_HEADER}（`X-Client-App`）。服务端读凭据时，**不带该头就只认历史共享
 *    `AUTH_TOKEN`**，而管理端登录时下发的是专属 `AUTH_TOKEN_MANAGER` 并顺手把历史 Cookie 置为过期
 *    ⇒ `<img>` 请求必然 **401**。所以「把 URL 塞进 src」这条路在管理端是死的。
 * ② **字段形态**：`store_books.cover_image` 存的是 covers 目录下的**裸文件名**
 *    （如 `auto_48135ddd….jpg`），不是 URL；拿它当 src 会按相对路径解析到管理后台自己的源上 ⇒ 404。
 * ③ **下架书返回 404**：`offshelf` 的书按设计不返回封面（防探测），调用方应先判断状态再决定是否拉。
 *
 * <p>与 Web 端 `BookStore.vue#loadCovers`、Android 端 `BookStoreAdapter`（Coil + `addHeader`
 * `Authorization`）保持同一口径：**带凭据拉字节 → objectURL → 交给 &lt;img&gt;**。
 */
export const storeApi = {
  getCoverUrl: coverUrlOf,

  /** 封面图片字节。调用方负责转 objectURL，并在列表变化/组件卸载时 revoke。 */
  getCoverBlob(id: number): Promise<AxiosResponse<Blob>> {
    return apiClient.get(coverUrlOf(id), { responseType: 'blob' })
  },
}
