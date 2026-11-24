import apiClient from './apiClient'
import type { AxiosResponse } from 'axios'
import { isAxiosError } from 'axios'
import { createAuthApi } from '@audiobook/shared'
import type {
  AuthResponse,
  RegisterRequest,
  StoreBook,
  CreditPackage,
  CreditTransaction,
} from '@audiobook/shared'

// 类型定义统一放在 @audiobook/shared，这里重新导出以保持视图层既有 import 不变
export type {
  AuthResponse,
  LoginRequest,
  RegisterRequest,
  StoreBook,
  CreditPackage,
  CreditTransaction,
} from '@audiobook/shared'

/**
 * 校验下载接口返回的 Blob 是否为真实音频/二进制，而非后端错误 JSON。
 * axios 的 responseType:'blob' 会把错误响应也包成 Blob，若直接当音频触发下载，
 * 用户会下载到一个 .mp3/.zip 实质是错误 JSON。命中 JSON 时解析出
 * message 并抛出，交由调用方提示真实原因。
 */
export async function assertDownloadable(res: AxiosResponse<Blob>): Promise<Blob> {
  const blob = res.data
  const ct = (res.headers?.['content-type'] as string | undefined) || blob?.type || ''
  if (ct.includes('json')) {
    let msg = '下载失败，请稍后重试'
    try {
      const obj = JSON.parse(await blob.text())
      msg = obj?.message || obj?.error || msg
    } catch {
      // 解析失败保留默认提示
    }
    throw new Error(msg)
  }
  return blob
}

/**
 * 从下载/试听类（responseType:'blob'）请求的失败中提取后端返回的真实错误信息。
 * 后端错误响应均为非 2xx，axios 默认走 rejection 分支、根本到不了 assertDownloadable
 * 的校验路径；错误体会被包成 Blob，直接展示 error.message 只会得到
 * "Request failed with status code 403" 这类英文泛化文案。这里解析 Blob 里的 JSON 取出 message。
 */
export async function extractBlobErrorMessage(e: unknown, fallback: string): Promise<string> {
  if (isAxiosError(e)) {
    const data = e.response?.data
    if (data instanceof Blob) {
      const ct = (e.response?.headers?.['content-type'] as string | undefined) || data.type || ''
      try {
        const text = await data.text()
        // 后端手写错误 JSON 可能没有 content-type（如 download-all 的错误分支），
        // 无头部时靠首字符嗅探 JSON，避免真实原因被兜底文案吞掉
        if (ct.includes('json') || text.trimStart().startsWith('{')) {
          const obj = JSON.parse(text)
          const msg = obj?.message || obj?.error
          if (msg) return String(msg)
        }
      } catch {
        // 解析失败按 fallback
      }
    } else {
      const msg = (data as { message?: string } | undefined)?.message
      if (msg) return msg
    }
  } else if (
    e instanceof Error &&
    // DOMException（音频播放被浏览器策略拒绝等）消息是英文原生文案，不适合直接展示；
    // 仅透传 assertDownloadable 抛出的带中文提示的普通 Error
    !(typeof DOMException !== 'undefined' && e instanceof DOMException) &&
    e.message
  ) {
    return e.message
  }
  return fallback
}

/** 上传类接口超时：50MB 文件在慢网络上传可能远超默认 30s，给 10 分钟 */
const LONG_TIMEOUT = 600000
/**
 * 下载类接口超时：整本书 zip 可能很大，给足 10 分钟窗口。
 * 取有限大值而非 0（永不超时）：「半死连接」（TCP 活着但不再吐字节）时请求会无限挂起，
 * 前端既没有失败提示也没有重试入口，用户只能刷新页面。
 */
const DOWNLOAD_TIMEOUT = 600000

export interface TtsTaskResponse {
  taskId: number
  status: string
  totalSegments: number
  completedSegments: number
  createdAt: string
  originalBookName?: string | null
  /** 任务失败原因（failed 状态时直接展示） */
  errorMessage?: string | null
}

export interface UploadResponse {
  success: boolean
  taskId: number
  totalSegments?: number
  message: string
}

export interface SegmentInfo {
  id: number
  segmentNumber: number
  textContent: string
  status: string
  errorMessage?: string | null
  /** 本段绑定的预设音色 voiceId（null 表示沿用任务默认 / 克隆参考音频） */
  voice?: string | null
}

/** 可选预设音色（供「按段/按角色绑定音色」下拉选择） */
export interface VoiceOption {
  id: string
  name: string
}

export interface TocEntry {
  title: string
  segmentNumber: number
  pageNumber?: number | null
}

/**
 * 正文段落（直接阅读）。
 * 与后端 BookTextExtractor.Paragraph / BookContentController.toParagraphMaps 一一对应。
 * 注意 charOffset 是「该段在全文中的起始字符下标」——后端切段时会 trim，
 * 相邻段的 charOffset 因此并非严格连续，不要用它推算段落长度。
 */
export interface BookParagraph {
  text: string
  charOffset: number
  paragraphIndex: number
}

/**
 * 电子书目录条目（直接阅读）。
 * 与 ttsApi.getTaskToc 的 TocEntry 不同：这里的定位键是 paragraphIndex（正文段落下标），
 * 而不是 segmentNumber（合成片段号）——未合成的书没有片段号。
 */
export interface BookTocEntry {
  title: string
  paragraphIndex: number
  pageNumber?: number | null
}

export interface UserReferenceAudio {
  id: number
  name: string
  fileName: string
  description?: string | null
  fileSize: number | null
  createdAt?: string
}

/** 服务器共享参考音频库（所有登录用户合成时均可引用） */
export interface ServerReferenceAudio {
  id: number
  name: string
  fileName: string
  description?: string | null
  fileSize: number | null
  createdAt?: string
}

// 共享认证接口（login / validateToken / logout 两端一致）由 @audiobook/shared 提供，
// 用户端专属的 register / changePassword 在此补充。
export const authApi = {
  ...createAuthApi(apiClient),

  register: (data: RegisterRequest) =>
    apiClient.post<AuthResponse>('/auth/register', data),

  changePassword: (oldPassword: string, newPassword: string) =>
    apiClient.post<{ success: boolean; message: string; token?: string }>('/auth/change-password', {
      oldPassword,
      newPassword,
    }),
}

export const ttsApi = {
  uploadBook: (bookFile: File, referenceAudio: File, onUploadProgress?: (percentComplete: number) => void, voiceId?: string | null) => {
    const formData = new FormData()
    formData.append('book_file', bookFile)
    formData.append('reference_audio', referenceAudio)
    if (voiceId) formData.append('voice_id', voiceId)

    return apiClient.post<UploadResponse>('/tts/upload-book', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
      timeout: LONG_TIMEOUT,
      onUploadProgress: (progressEvent) => {
        if (progressEvent.total && onUploadProgress) {
          onUploadProgress(Math.round((progressEvent.loaded * 100) / progressEvent.total))
        }
      },
    })
  },

  /** 仅用预设音色合成（不传任何参考音频），voiceId 必填 */
  uploadBookOnly: (bookFile: File, voiceId: string, onUploadProgress?: (percentComplete: number) => void) => {
    const formData = new FormData()
    formData.append('book_file', bookFile)
    formData.append('voice_id', voiceId)
    return apiClient.post<UploadResponse>('/tts/upload-book', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: LONG_TIMEOUT,
      onUploadProgress: (progressEvent) => {
        if (progressEvent.total && onUploadProgress) {
          onUploadProgress(Math.round((progressEvent.loaded * 100) / progressEvent.total))
        }
      },
    })
  },

  /** 用「我的参考音频库」中的音频（reference_id 为文件名）合成；referenceSource 区分 user/server。
   *  voiceId 为可选预设音色：传了预设音色即可不传参考音频，用预置音色合成。 */
  uploadBookWithRef: (bookFile: File, referenceId: string, referenceSource?: 'user' | 'server', onUploadProgress?: (p: number) => void, voiceId?: string | null) => {
    const formData = new FormData()
    formData.append('book_file', bookFile)
    formData.append('reference_id', referenceId)
    if (referenceSource) formData.append('reference_source', referenceSource)
    if (voiceId) formData.append('voice_id', voiceId)
    return apiClient.post<UploadResponse>('/tts/upload-book', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: LONG_TIMEOUT,
      onUploadProgress: (progressEvent) => {
        if (progressEvent.total && onUploadProgress) {
          onUploadProgress(Math.round((progressEvent.loaded * 100) / progressEvent.total))
        }
      },
    })
  },

  /** 从书城书籍直接创建合成任务（参考音频支持文件或库文件名二选一）。
   *  voiceId 为可选预设音色。 */
  createTtsFromStore: (bookId: number, reference: { file?: File; referenceId?: string; source?: 'user' | 'server' }, voiceId?: string | null) => {
    const formData = new FormData()
    formData.append('bookId', String(bookId))
    if (reference.file) formData.append('reference_audio', reference.file)
    if (reference.referenceId) formData.append('reference_id', reference.referenceId)
    if (reference.source) formData.append('reference_source', reference.source)
    if (voiceId) formData.append('voice_id', voiceId)
    return apiClient.post<UploadResponse>('/tts/from-store', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: LONG_TIMEOUT,
    })
  },

  /** 获取可选预设音色清单（id + 中文名），供「按段/按角色绑定音色」下拉 */
  getVoices: () =>
    apiClient.get<{ success: boolean; voices: VoiceOption[] }>('/tts/voices'),

  /** 修改任务音色分配并触发受影响片段重新合成 */
  updateTaskVoices: (taskId: number, payload: { defaultVoiceId?: string | null; segmentVoices: Record<number, string | null> }) =>
    apiClient.post<{ success: boolean; message: string }>(`/tts/task/${taskId}/voices`, payload),

  // ---------- 我的参考音频库 ----------

  uploadUserReference: (file: File, name?: string, description?: string, onProgress?: (percent: number) => void) => {
    const formData = new FormData()
    formData.append('reference_audio', file)
    if (name) formData.append('name', name)
    if (description) formData.append('description', description)
    return apiClient.post<{ success: boolean; id: number; name: string; fileName: string; description?: string; message: string }>(
      '/tts/references',
      formData,
      {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: LONG_TIMEOUT,
        onUploadProgress: (e) => {
          if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
        },
      },
    )
  },

  listUserReferences: () =>
    apiClient.get<{ success: boolean; references: UserReferenceAudio[]; total: number }>(
      '/tts/references',
    ),

  /** 修改我的参考音频：可更新名称/描述，也可重新上传文件（视频自动转 WAV）。字段均可选。 */
  updateUserReference: (id: number, payload: { name?: string; description?: string; file?: File }, onProgress?: (percent: number) => void) => {
    const formData = new FormData()
    if (payload.name !== undefined && payload.name !== null) formData.append('name', payload.name)
    // description 允许显式清空，故仅在非 undefined 时追加（空串也追加）
    if (payload.description !== undefined && payload.description !== null) formData.append('description', payload.description)
    if (payload.file) formData.append('reference_audio', payload.file)
    return apiClient.post<{ success: boolean; id: number; name: string; fileName: string; description?: string; message: string }>(
      `/tts/references/${id}/update`,
      formData,
      {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: LONG_TIMEOUT,
        onUploadProgress: (e) => {
          if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
        },
      },
    )
  },

  deleteUserReference: (id: number) =>
    apiClient.delete<{ success: boolean; message: string }>(`/tts/references/${id}`),

  previewUserReference: (id: number) =>
    apiClient.get(`/tts/references/${id}/audio`, { responseType: 'blob' }),

  // ---------- 服务器共享参考音频库 ----------

  /** 列出服务器共享参考音频（所有登录用户合成时均可引用） */
  listServerReferences: () =>
    apiClient.get<{ success: boolean; references: ServerReferenceAudio[]; total: number }>(
      '/tts/server-references',
    ),

  /** 试听服务器共享参考音频 */
  previewServerReference: (id: number) =>
    apiClient.get(`/tts/server-references/${id}/audio`, { responseType: 'blob' }),

  getTaskStatus: (taskId: number) =>
    apiClient.get<{ success: boolean; task: TtsTaskResponse }>(`/tts/task/${taskId}`),

  getUserTasks: () =>
    apiClient.get<{ success: boolean; tasks: TtsTaskResponse[]; queuedCount?: number }>('/tts/tasks'),

  getTaskSegmentsList: (taskId: number) =>
    apiClient.get<{ success: boolean; segments: SegmentInfo[]; total: number }>(
      `/tts/task/${taskId}/segments-list`,
    ),

  getTaskToc: (taskId: number) =>
    apiClient.get<{ success: boolean; toc: TocEntry[] }>(`/tts/task/${taskId}/toc`),

  downloadSegment: (segmentId: number) =>
    apiClient.get(`/tts/segment/${segmentId}/download`, {
      responseType: 'blob',
      timeout: DOWNLOAD_TIMEOUT,
    }),

  retryTask: (taskId: number) =>
    apiClient.post<{ success: boolean; message: string }>(`/tts/task/${taskId}/retry`),

  /** 批量重试全部失败任务；返回重试成功数、总补扣积分与跳过明细 */
  retryFailedTasks: (taskIds: number[]) =>
    apiClient.post<{
      success: boolean
      message: string
      retried: number
      totalRecharged: number
      skipped: { taskId: number; message: string }[]
    }>('/tts/tasks/retry-failed', { taskIds }),

  deleteTask: (taskId: number) =>
    apiClient.delete<{ success: boolean; message: string }>(`/tts/task/${taskId}`),

  downloadAllSegments: (taskId: number) =>
    apiClient.get(`/tts/task/${taskId}/download-all`, {
      responseType: 'blob',
      timeout: DOWNLOAD_TIMEOUT,
    }),
}

// ---------- 书城 ----------

export interface StoreCategory {
  name: string
  count: number
}

/**
 * 书城标签（标签名 + 该标签下的在售书籍数）。
 * 结构与 {@link StoreCategory} 一致，标签栏与分类栏可以共用一套渲染逻辑。
 *
 * 注意与 `StoreBook.category` 的区别：分类是**单选**的业务归属（一本书只属一个分类，
 * 同时决定磁盘上的书籍目录），标签是**多选**的描述性元数据（一本书可有多个，
 * 只影响展示与筛选，不参与文件定位）。两者并存，不是互相替代。
 */
export interface StoreTag {
  name: string
  count: number
}

export const storeApi = {
  /** 书城首页：热门 + 新书 + 分类 + 标签 */
  getHome: () =>
    apiClient.get<{
      success: boolean
      hotBooks: StoreBook[]
      newBooks: StoreBook[]
      categories: StoreCategory[]
      tags: StoreTag[]
    }>('/store/home'),

  /** 书籍列表（分类/标签筛选 + 关键词搜索 + 分页；不传 page/size 时服务端返回全量） */
  getBooks: (params?: {
    category?: string
    tag?: string
    keyword?: string
    page?: number
    size?: number
  }) =>
    apiClient.get<{ success: boolean; books: StoreBook[]; total: number }>(
      '/store/books',
      { params },
    ),

  /** 分类列表 */
  getCategories: () =>
    apiClient.get<{ success: boolean; categories: StoreCategory[] }>(
      '/store/categories',
    ),

  /** 标签列表（标签名 + 在售书籍数） */
  getTags: () =>
    apiClient.get<{ success: boolean; tags: StoreTag[] }>('/store/tags'),

  /** 书籍详情（含 taskId，用于判断这本书是否已合成音频） */
  getBookDetail: (id: number) =>
    apiClient.get<{ success: boolean; book?: StoreBook; message?: string }>(`/store/book/${id}`),

  /**
   * 读取书城书籍正文（段落 + 目录 + 原文行），供「直接打开阅读」。
   * 服务端按 store_books.id 自行定位文件，无须先下载再上传。
   * 书不存在/已下架 → 404；文件缺失或无法解析（加密 PDF、扫描件等）→ 422，均带 message。
   * 整本提取是秒级操作，给 LONG_TIMEOUT 而非默认 30s。
   *
   * 三套数据的分工（都来自服务端**同一次**提取，坐标系相同）：
   * - `paragraphs`：完整句子 = 合成片段（与音频段号 1:1，段号 = paragraphIndex + 1）——
   *   用来播、用来定位，**不是**拿来显示的：它是切给 TTS 引擎的；
   * - `rows`：**原文行**（保留行首缩进）—— 阅读页显示这个。TXT 不返回（客户端本地解析）；
   * - `rowParagraphStart/End`：与 `rows` 等长的段落下标（第 i 行首/末实字各属哪一段）——
   *   把上面两套粒度对上，否则「显示行」与「播放段」无法互相定位。
   */
  getBookContent: (id: number) =>
    apiClient.get<{
      success: boolean
      title?: string
      paragraphs: BookParagraph[]
      toc: BookTocEntry[]
      extension?: string | null
      /** 原文行（保留行首缩进）；TXT 不返回 */
      rows?: string[]
      /** rows[i] 首/末实字所属的段落下标；与 rows 等长、各自单调不减 */
      rowParagraphStart?: number[]
      rowParagraphEnd?: number[]
      /**
       * 每行内各段落的字符区间 [start, end) 列表（v5/W8）。
       *
       * <p>阅读页的句级高亮靠它把「一行」切成按段落铺色的 span。原类型里没声明这个字段，
       * 消费方只能靠结构化传参「偷渡」，一旦后端字段改名，TS 不会报错、只会静默退化成整行高亮。
       */
      rowParagraphSegments?: number[][][]
      /**
       * 正文来源（W1）：
       * - `snapshot`：这本书有音频，正文取自**合成那一刻的快照** —— 与音频严格同源；
       * - `live`：按当前磁盘文件实时提取（无音频的书）。
       */
      source?: 'snapshot' | 'live'
      /**
       * 磁盘文件已与快照不是同一份（W1）。
       *
       * 此时服务端仍返回**快照**（那才是这些音频对应的文字），前端据此提示用户，
       * 而不是让「文字和声音对不上」看起来像 bug。
       */
      staleFile?: boolean
      message?: string
    }>(`/store/book/${id}/content`, { timeout: LONG_TIMEOUT }),

  /** 封面图片 URL（注意：不带 /api 前缀，apiClient baseURL 已含 /api） */
  getCoverUrl: (id: number) => `/store/book/${id}/cover`,

  /** 下载书籍文件（blob） */
  downloadBook: (id: number) =>
    apiClient.get(`/store/book/${id}/download`, { responseType: 'blob', timeout: DOWNLOAD_TIMEOUT }),

  /**
   * 下载书城书籍的合成音频（管理员在管理端合成后才有，task_id IS NULL 时后端返回 4xx + JSON）。
   * 用同一 blob + assertDownloadable 校验，避免「看起来是 zip 实为错误 JSON」的坑。
   */
  downloadAudio: (id: number) =>
    apiClient.get(`/store/book/${id}/audio/download`, { responseType: 'blob', timeout: DOWNLOAD_TIMEOUT }),

  // ---------- 阅读侧（书级） ----------
  //
  // 为什么不用 ttsApi 的同名接口：`/api/tts/**` 是**用户私有任务**口径，段列表/单段下载/
  // 进度全部以 tasks.user_id 做归属校验。书城里的书通常由管理员合成，task_id 指向管理员的任务，
  // 普通用户调私有接口必然 403（表现：「加载书籍内容失败」）。而书的音频是公共资源 ——
  // 与既有 getBookContent / downloadAudio 同口径，只要求登录。

  /** 本书的音频片段元数据（结构与 ttsApi.getTaskSegmentsList 一致，可直接喂给播放引擎） */
  getBookSegments: (bookId: number) =>
    apiClient.get<{ success: boolean; segments: SegmentInfo[]; total: number }>(
      `/store/book/${bookId}/segments`,
    ),

  /** 本书目录（segmentNumber 口径，与 ttsApi.getTaskToc 同构） */
  getBookToc: (bookId: number) =>
    apiClient.get<{ success: boolean; toc: TocEntry[] }>(`/store/book/${bookId}/toc`),

  /** 播放某一段音频（书级；服务端校验该段属于本书的任务） */
  downloadBookSegment: (bookId: number, segmentId: number) =>
    apiClient.get(`/store/book/${bookId}/segments/${segmentId}/audio`, {
      responseType: 'blob',
      timeout: DOWNLOAD_TIMEOUT,
    }),

  /**
   * 读取本书的阅读进度。
   * 服务端仍以 (user_id, store_books.task_id) 落库，故与任务播放页的进度互通。
   */
  getBookProgress: (bookId: number) =>
    apiClient.get<{
      success: boolean
      hasProgress?: boolean
      segment?: number
      offsetMs?: number
      updatedAt?: string | null
    }>(`/store/book/${bookId}/progress`),

  /** 保存本书的阅读进度（taskId 由服务端按书推导，不接受客户端传入） */
  saveBookProgress: (bookId: number, segment: number, offsetMs: number) =>
    apiClient.put(`/store/book/${bookId}/progress`, { segment, offsetMs }),

  /**
   * 读取**跨端**阅读进度（按 bookKey 身份）。
   *
   * 与上面的书级端点相比，本接口的身份是 `bookKey`（`s:`/`t:`/`n:`），
   * 与有声覆盖图同一套口径 ⇒ 同一本书在 Android/PC/Web 算出同一个 key，进度真正互通。
   *
   * 为什么不能只靠书级端点：它的服务端落库键曾挂在 `store_books.task_id` 上，
   * 而**未合成的书 task_id 为空** ⇒ 纯阅读的书在所有端都记不住位置。
   */
  getBookProgressByKey: (bookKey: string) =>
    apiClient.get<{
      success: boolean
      hasProgress?: boolean
      segment?: number
      offsetMs?: number
      updatedAt?: string | null
    }>('/book-progress', { params: { bookKey } }),

  /** 保存**跨端**阅读进度（按 bookKey 身份） */
  saveBookProgressByKey: (bookKey: string, segment: number, offsetMs: number) =>
    apiClient.put('/book-progress', { bookKey, segment, offsetMs }),
}

// ---------- 我的书架 ----------

/**
 * 书架条目。
 *
 * 服务端把 shelf_books 与 store_books 拼接后下发（见 ShelfService.toItem），
 * 所以这里既有「书架关系」字段也有「书籍元信息」字段。
 *
 * 书籍已被删除时：missing=true，但 title/author 会回退到「入架时冻结的快照」
 * （shelf_books.title/author），因此失效卡片仍能显示书名，用户认得出是哪本书；
 * 只有当连快照都为空（老数据且书早已删除）时 title 才是 null。
 * 无论如何都应渲染一张「已失效」卡片并允许移除，不能直接过滤掉——
 * 否则用户会发现书凭空消失、又找不到地方清理。
 */
export interface ShelfItem {
  /** shelf_books 主键（仅作列表 key，不用于移除接口） */
  shelfBookId: number
  storeBookId: number
  addedAt?: string | null
  /** 书已从书城删除（store_books 已无此行），但书名仍可能由入架快照回显 */
  missing: boolean
  /** 书名。书在架时为实时值；书已删时回退到入架快照（可能为 null） */
  title?: string | null
  /** 作者。同上，回退到入架快照 */
  author?: string | null
  category?: string | null
  coverColor?: string | null
  fileExtension?: string | null
  fileSize?: number | null
  taskId?: number | null
  /** 已下架（磁盘文件缺失），可看不可读 */
  offshelf: boolean
  /** 已有合成音频（可边读边播） */
  hasAudio: boolean
}

export const shelfApi = {
  /** 我的书架（按加入时间倒序，已带书籍元信息） */
  list: () =>
    apiClient.get<{ success: boolean; items: ShelfItem[]; total: number }>('/shelf'),

  /**
   * 书架中所有书籍 id。
   * 书城列表用它在开页时一次性标注「已在书架」，避免逐本请求 contains。
   */
  ids: () => apiClient.get<{ success: boolean; bookIds: number[] }>('/shelf/ids'),

  /** 某本书是否已在书架 */
  contains: (storeBookId: number) =>
    apiClient.get<{ success: boolean; contains: boolean }>(`/shelf/contains/${storeBookId}`),

  /** 加入书架（幂等；added=false 表示本来就在书架中） */
  add: (storeBookId: number) =>
    apiClient.post<{ success: boolean; shelfItem?: ShelfItem; added?: boolean; message?: string }>(
      `/shelf/${storeBookId}`,
    ),

  /** 移出书架（幂等）。用 storeBookId 而非 shelfBookId，书城页无须先查列表即可移除 */
  remove: (storeBookId: number) =>
    apiClient.delete<{ success: boolean; removed?: boolean; message?: string }>(
      `/shelf/${storeBookId}`,
    ),
}

// ---------- 全局配置（后端下发，前端以之为准，避免前后端阈值漂移） ----------

export interface AppConfig {
  success: boolean
  maxFileSize: number
  avgSegmentSeconds?: number
  ttsConcurrency?: number
}

export const configApi = {
  /** 获取全局配置（如上传大小上限，单位：字节） */
  getConfig: () =>
    apiClient.get<AppConfig>('/config'),
}

// ---------- 积分（充值 / 余额 / 流水） ----------

export interface CreditBalanceResponse {
  success: boolean
  balance: number
  enabled: boolean
  creditsPerTask: number
  refundOnTaskFailure: boolean
  description: string | null
  enoughForOneTask: boolean
}

export interface PaymentChannel {
  channel: string
  name: string
}

export interface PrepayResponse {
  success: boolean
  orderNo: string | null
  amount: number
  credits: number
  bonusCredits: number
  totalCredits: number
  packageName: string | null
  channel: string | null
  payType: string | null
  payUrl: string | null
  expireAt: string | null
  message?: string | null
}

/** 用户端积分接口（CreditController / PaymentController） */
export const creditApi = {
  getMyCredits: () =>
    apiClient.get<CreditBalanceResponse>('/credits/me'),

  getPackages: () =>
    apiClient.get<{ success: boolean; packages: CreditPackage[] }>('/credits/packages'),

  getMyTransactions: (params: { page?: number; size?: number }) =>
    apiClient.get<{ success: boolean; content: CreditTransaction[]; totalElements: number }>(
      '/credits/transactions',
      { params },
    ),

  getChannels: () =>
    apiClient.get<{ success: boolean; channels: PaymentChannel[] }>('/payment/channels'),

  createOrder: (packageId: number, channel: string) =>
    apiClient.post<PrepayResponse>('/payment/prepay', { packageId, channel }),

  getOrderStatus: (orderNo: string) =>
    apiClient.get<{ success: boolean; status: string; balance: number }>(`/payment/orders/${orderNo}`),

  cancelOrder: (orderNo: string) =>
    apiClient.post<{ success: boolean; message: string }>(`/payment/orders/${orderNo}/cancel`),

  confirmMock: (orderNo: string) =>
    apiClient.post<{ success: boolean; message: string; balance: number }>('/payment/mock/confirm', null, {
      params: { orderNo },
    }),
}

// ---------- APK 版本（登录页扫码下载） ----------

export interface AppVersionInfo {
  versionCode: number
  versionName: string
  fileSize: number
  md5: string
  changelog: string | null
  forceUpdate: boolean
  releasedAt: string | null
}

export interface AppVersionLatestResponse {
  success: boolean
  hasUpdate: boolean
  /** 相对路径（不带前导斜杠），需自行拼上站点根 */
  downloadUrl?: string
  latest?: AppVersionInfo
  message?: string
}

/**
 * APK 下载地址（绝对 URL）。
 *
 * 服务端返回的 `downloadUrl` 是相对路径 `api/app/version/latest/apk?platform=android`，
 * 不能直接拿去编码成二维码 —— 二维码必须携带绝对地址，否则手机扫码后无从解析。
 * 这里强制使用「当前站点来源 + 该相对路径」，保证扫码与当前访问的域名一致
 * （换域名、换端口、上 HTTPS 都不用改代码）。
 */
export function resolveApkDownloadUrl(rel: string): string {
  const origin =
    typeof window !== 'undefined' && window.location?.origin
      ? window.location.origin
      : ''
  return `${origin}/${rel.replace(/^\/+/, '')}`
}

export const appVersionApi = {
  /** 查询当前发布的 Android 版本（公开接口，无需登录） */
  getLatest: (platform = 'android') =>
    apiClient.get<AppVersionLatestResponse>('/app/version/latest', { params: { platform } }),
}

/** 兜底下载地址：版本接口不可用时仍能让二维码指向一个稳定地址 */
export const APK_FALLBACK_PATH = 'api/app/version/latest/apk?platform=android'
