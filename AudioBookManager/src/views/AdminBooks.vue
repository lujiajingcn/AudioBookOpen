<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, h } from 'vue'
import {
  Table, Button, Space, Modal, Input, AutoComplete, Upload, Progress,
  Select, message, Card, Typography, Tag, Tooltip,
} from 'ant-design-vue'
import {
  BookOutlined, DeleteOutlined, ExclamationCircleOutlined,
  ReloadOutlined, UploadOutlined, SoundOutlined, ToolOutlined,
  CheckCircleOutlined, ClockCircleOutlined,
  PauseCircleOutlined, PlayCircleOutlined, RollbackOutlined,
  WarningOutlined, CloseCircleOutlined, TagOutlined,
} from '@ant-design/icons-vue'
import type { UploadFile } from 'ant-design-vue/es/upload/interface'
import type { ColumnsType } from 'ant-design-vue/es/table'
import { adminApi, storeApi, type StoreBook, type AdminVoiceOption } from '@/services/api'
import { isHandledByInterceptor } from '@/services/apiClient'
import { formatDateTime, formatFileSize } from '@audiobook/shared'
import { isAxiosError, isCancel } from 'axios'

const { Title } = Typography

/** 与服务端 AdminService.SUPPORTED_BOOK_EXTENSIONS 保持一致 */
const ALLOWED_BOOK_EXTENSIONS = ['txt', 'pdf', 'epub', 'mobi', 'md']
/** 与服务端 spring.servlet.multipart.max-file-size 保持一致 */
const MAX_BOOK_FILE_SIZE = 50 * 1024 * 1024

/** 校验封面底色格式，非 #RRGGBB 时回退灰色，避免外部字符串直接拼入 style 造成注入/样式错乱 */
const safeCoverColor = (color?: string | null): string =>
  /^#[0-9a-fA-F]{6}$/.test(color || '') ? color! : '#ccc'

const books = ref<StoreBook[]>([])
const loading = ref(false)
const totalElements = ref(0)
const currentPage = ref(0)
const pageSize = ref(20)
const keyword = ref('')

// ===================== 封面 =====================
// 封面必须带登录态拉字节后转 objectURL（`<img>` 不带 X-Client-App，直连该端点必然 401），
// 也不能用 store_books.cover_image —— 那是 covers 目录下的裸文件名而非 URL。详见 services/api.ts。
/** 已就绪的封面 objectURL，key = 书籍 id */
const coverUrls = ref<Map<number, string>>(new Map())
/** 已在途的封面请求，避免搜索/刷新连点造成同书重复拉取（重复只会多造一个无人持有的 objectURL） */
const coverPending = new Set<number>()
/** 组件卸载后到达的封面响应要立即释放，避免泄漏 */
let coversDisposed = false

const releaseCovers = () => {
  coverUrls.value.forEach((url) => {
    try {
      URL.revokeObjectURL(url)
    } catch {
      // 已释放过的 URL 再 revoke 会抛，忽略即可
    }
  })
  coverUrls.value = new Map()
}

/**
 * 拉取列表里缺封面的书。
 * 下架（offshelf）的书跳过：封面端点为防探测对它们按设计返回 404，白跑一次请求还会污染控制台。
 */
const loadCovers = (list: StoreBook[]) => {
  for (const book of list) {
    if (book.status === 'offshelf') continue
    if (coverUrls.value.has(book.id) || coverPending.has(book.id)) continue
    coverPending.add(book.id)
    storeApi
      .getCoverBlob(book.id)
      .then((res) => {
        const url = URL.createObjectURL(res.data)
        // 组件卸载后到达：不经 Map，直接释放
        if (coversDisposed) {
          URL.revokeObjectURL(url)
          return
        }
        // 批量更新，避免每次 set 后整体复制 Map
        const next = new Map(coverUrls.value)
        next.set(book.id, url)
        coverUrls.value = next
      })
      .catch(() => {
        // 封面是附加信息，失败静默退回底色占位（网络抖动/权限异常都不该弹错打断管理操作）
      })
      .finally(() => {
        coverPending.delete(book.id)
      })
  }

  // 释放已不在本页列表里的封面，避免翻页/搜索反复累积 objectURL
  // ⚠️ revoke 后必须同步从 Map 删除：否则再次回到该页时 has(id) 命中已失效 URL，整页封面裂图
  const kept = new Map(coverUrls.value)
  for (const id of [...kept.keys()]) {
    if (!list.some((b) => b.id === id)) {
      const url = kept.get(id)
      if (url) URL.revokeObjectURL(url)
      kept.delete(id)
    }
  }
  coverUrls.value = kept
}

const columns: ColumnsType<StoreBook> = [
  {
    title: 'ID',
    dataIndex: 'id',
    key: 'id',
    width: 70,
  },
  {
    title: '书名',
    dataIndex: 'title',
    key: 'title',
    // 有原书名时在书名下方以次要字号补一行，避免为它单开一列把表格撑宽
    customRender: ({ text, record }: { text: string; record: StoreBook }) =>
      record.originalTitle
        ? h('div', [
            h('div', text),
            h(
              'div',
              { style: 'font-size: 12px; line-height: 1.4; margin-top: 2px; color: rgba(0, 0, 0, 0.45);' },
              `原名：${record.originalTitle}`,
            ),
          ])
        : text,
  },
  {
    title: '作者',
    dataIndex: 'author',
    key: 'author',
    customRender: ({ text }) => text || '-',
  },
  {
    title: '分类',
    dataIndex: 'category',
    key: 'category',
    width: 120,
    customRender: ({ text }) => text || '-',
  },
  {
    title: '标签',
    dataIndex: 'tags',
    key: 'tags',
    width: 180,
    // 标签是多个，列宽有限：最多铺 3 个，其余折成 +N（点右侧「标签」按钮看全量并编辑）
    customRender: ({ record }: { record: StoreBook }) => {
      const list = record.tags ?? []
      if (list.length === 0) {
        return h('span', { style: 'color: rgba(0,0,0,0.35);' }, '无')
      }
      const shown = list.slice(0, 3)
      const rest = list.length - shown.length
      return h('div', { style: 'display: flex; flex-wrap: wrap; gap: 2px;' }, [
        ...shown.map((tag) =>
          h(
            Tag,
            { key: tag, style: 'margin: 0; font-size: 11px; line-height: 18px; padding: 0 6px;' },
            () => tag,
          ),
        ),
        rest > 0
          ? h('span', { style: 'font-size: 11px; color: rgba(0,0,0,0.45);' }, `+${rest}`)
          : null,
      ])
    },
  },
  {
    title: '封面',
    // 不再用 dataIndex: 'coverImage' —— 该字段是 covers 目录下的裸文件名（不是 URL），
    // 直接当 src 会解析到管理后台自己的源上导致裂图；封面统一走 storeApi 带凭据拉 blob。
    key: 'cover',
    width: 90,
    customRender: ({ record }) => {
      const objectUrl = coverUrls.value.get(record.id)
      if (objectUrl) {
        return h('img', {
          src: objectUrl,
          style: 'width: 48px; height: 64px; object-fit: cover; border-radius: 4px;',
          alt: record.title,
        })
      }
      // 尚未就绪（加载中 / 下架 / 拉取失败）时显示底色占位。
      // 注：所有 onshelf 书都能拿到封面（服务端无自定义封面时现画「书名+作者」文字封面），
      // 所以这里通常只在请求途中短暂出现。
      return h('div', {
        style: `width: 48px; height: 64px; border-radius: 4px; background: ${safeCoverColor(record.coverColor)};`,
      })
    },
  },
  {
    title: '大小',
    dataIndex: 'fileSize',
    key: 'fileSize',
    width: 120,
    customRender: ({ text }) => formatFileSize(text),
  },
  {
    title: '下载次数',
    dataIndex: 'downloadCount',
    key: 'downloadCount',
    width: 100,
    customRender: ({ text }) => text ?? 0,
  },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    width: 140,
    customRender: ({ text }) => {
      // 后端下架（offshelf）= 磁盘文件已删，不可下载；onshelf=正常在售
      if (text === 'onshelf') return h('span', { style: 'color: #52c41a;' }, '正常')
      if (text === 'offshelf') return h('span', { style: 'color: #ff4d4f;' }, '已下架/文件缺失')
      return h('span', { style: 'color: #999;' }, text || '-')
    },
  },
  {
    title: '音频',
    key: 'audio',
    width: 168,
    customRender: ({ record }) => {
      const phase = synthPhase(record)
      const tag = h(
        Tag,
        { color: PHASE_COLOR[phase], icon: PHASE_ICON[phase] },
        () => PHASE_TEXT[phase],
      )
      if (phase === 'none') {
        return h(Tooltip, { title: '尚未合成音频，点击右侧「合成语音」发起', placement: 'top' }, {
          default: () => tag,
        })
      }
      const who = `操作人：${record.synthesizedBy || '未知'}`
      const when = `合成时间：${formatDateTime(record.synthesizedAt)}`
      // 进行中 / 暂停 / **未跑全**：徽标下都再挂一行「已完成 / 总数」。
      //
      // partial / failed 必须挂：只看到「部分完成」四个字时，管理员判断不出是差 4 段
      // 还是差 4000 段 —— 而这恰好决定该不该补、值不值得补。线上那本书是
      // 6513 / 6517，只差 4 段，但界面上完全看不出来，只能当成「废了」去删语音重来。
      const showProgress = ACTIVE_PHASES.has(phase)
        || phase === 'paused' || phase === 'partial' || phase === 'failed'
      // 失败原因只在 partial / failed 时有意义（服务端也只在失败路径写这个字段）
      const reason = phase === 'partial' || phase === 'failed'
        ? record.taskErrorMessage?.trim()
        : ''
      const tip = h('div', {}, [
        PHASE_HINT[phase] ? h('div', {}, PHASE_HINT[phase]) : null,
        reason ? h('div', { style: 'margin-top: 4px; max-width: 340px' }, `失败原因：${reason}`) : null,
        h('div', { style: 'margin-top: 4px; opacity: 0.75' }, `${who} · ${when}`),
      ])
      return h(Tooltip, { title: tip, placement: 'top' }, {
        default: () => h('div', {}, [
          tag,
          showProgress
            ? h('div', { style: 'font-size: 12px; color: #999; margin-top: 2px' }, () => progressText(record))
            : null,
        ]),
      })
    },
  },
  {
    title: '上传时间',
    dataIndex: 'createdAt',
    key: 'createdAt',
    width: 200,
    customRender: ({ text }) => formatDateTime(text),
  },
  {
    title: '操作',
    key: 'action',
    // 加了「标签」按钮后 320 会把按钮挤到换行，一起给到 380
    width: 380,
    fixed: 'right',
    customRender: ({ record }) => {
      const phase = synthPhase(record)
      const busy = isBookBusy(record.id)
      const buttons: unknown[] = []

      if (ACTIVE_PHASES.has(phase)) {
        // 进行中：主操作从「合成语音」换成「暂停」，避免同一行摆两个互斥按钮
        buttons.push(h(Button, {
          size: 'small',
          type: 'default',
          loading: busy,
          icon: h(PauseCircleOutlined),
          title: '在当前段落结束后暂停，已完成的段落保留',
          onClick: () => handlePauseSynth(record),
        }, () => '暂停'))
      } else if (phase === 'paused') {
        buttons.push(h(Button, {
          size: 'small',
          type: 'primary',
          loading: busy,
          icon: h(PlayCircleOutlined),
          title: '从已完成段落处继续合成',
          onClick: () => handleResumeSynth(record),
        }, () => '继续'))
      } else if (phase === 'partial' || phase === 'failed') {
        // 跑到终态但没跑全：只补缺的段（断点续跑）。
        //
        // 这里之前落进了下面的 else，被 `!!record.taskId` 判成「已合成」并**禁用** ——
        // 于是 partial 的书成了死局：合成语音点不动、没有继续、只剩删语音重来。
        // 线上那本 6513 / 6517 段的书就这么卡着，代价是把 6513 段白烧一遍。
        buttons.push(h(Button, {
          size: 'small',
          type: 'primary',
          loading: busy,
          icon: h(ReloadOutlined),
          title: '只重新合成未成功的段落，已完成的原样保留',
          onClick: () => handleRetrySynth(record),
        }, () => '继续合成'))
      } else if (phase === 'broken') {
        // 任务行已不存在 / 状态无法识别（悬挂 task_id）：合成端点会以「已合成过、暂不支持重新合成」
        // 拒绝，补齐端点也会以「不是 partial/failed」拒绝。强行给按钮只会让管理员点出一串 400，
        // 所以这里如实说明，唯一出路是右侧的「删除语音」——它会顺手清掉这条失效绑定。
        buttons.push(h(Button, {
          size: 'small',
          type: 'default',
          disabled: true,
          icon: h(WarningOutlined),
          title: '任务记录已失效：点右侧「删除语音」清理绑定后，即可重新发起合成',
        }, () => '绑定失效'))
      } else {
        // 走到这里只剩 none（从未合成）与 completed（真·已合成）两种，直接按 phase 判，
        // 不再用 !!record.taskId 这种代理判据
        const synthDisabled = phase === 'completed'
        buttons.push(h(Button, {
          size: 'small',
          type: synthDisabled ? 'default' : 'primary',
          icon: h(SoundOutlined),
          disabled: synthDisabled,
          title: synthDisabled ? '本书已合成（可删除语音后重新合成）' : '为本书发起 TTS 合成',
          onClick: () => openSynthesizeModal(record),
        }, () => synthDisabled ? '已合成' : '合成语音'))
      }

      // 删除语音：只要绑定了合成任务就给（未合成的书无语音可删）
      if (record.taskId) {
        buttons.push(h(Button, {
          size: 'small',
          danger: true,
          disabled: busy || ACTIVE_PHASES.has(phase),
          icon: h(RollbackOutlined),
          title: ACTIVE_PHASES.has(phase)
            ? '合成进行中，请先暂停再删除语音'
            : '删除已合成的语音（书籍保留，可重新合成）',
          onClick: () => handleDeleteAudio(record),
        }, () => '删除语音'))
      }

      buttons.push(h(Button, {
        size: 'small',
        icon: h(TagOutlined),
        disabled: busy,
        title: '编辑这本书的标签（一本书可以有多个标签）',
        onClick: () => openTagModal(record),
      }, () => '标签'))

      buttons.push(h(Button, {
        size: 'small',
        danger: true,
        disabled: busy,
        icon: h(DeleteOutlined),
        title: '从书城删除这本书（连同电子文件）',
        onClick: () => confirmDelete(record),
      }, () => '删除'))

      return h(Space, { size: 'small' }, { default: () => buttons })
    },
  },
]

/**
 * 列表请求序号：只允许**最后一次**发出的请求写回 books / totalElements。
 *
 * <p>翻页、搜索、6s 轮询、操作后刷新这四条路都会调 loadBooks，`loading` 只挡住
 * 「上一拍没回来就跳过这一拍」，挡不住「手动刷新与轮询同时在飞、旧的后回来」——
 * 表现为刚点完「继续合成」状态又闪回「已暂停」，或者列表里出现上一页的书。
 */
let loadSeq = 0

const loadBooks = async () => {
  const seq = ++loadSeq
  try {
    loading.value = true
    const response = await adminApi.getBooks(currentPage.value, pageSize.value, keyword.value.trim() || undefined)
    // 已有更新的请求发出：这份结果是旧页/旧关键字的，写回去就是状态回退
    if (seq !== loadSeq) return
    if (response.data.success) {
      books.value = response.data.content ?? []
      totalElements.value = response.data.totalElements ?? books.value.length
      // 列表就绪后再拉封面（封面失败不影响列表可用性）
      loadCovers(books.value)
    }
  } catch (error: unknown) {
    if (seq !== loadSeq) return
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '加载书籍列表失败')
    }
  } finally {
    // 只有最新那次请求才有资格结束 loading / 重估轮询：
    // 旧请求提前把 loading 置 false，会让下一次轮询立刻再发一个，请求叠加
    if (seq === loadSeq) {
      loading.value = false
      syncAutoRefresh()
    }
  }
}

/**
 * 有书正在合成时，每 6 秒静默刷新一次列表。
 *
 * <p>为什么必须自动刷新：暂停 / 继续 / 删除语音三个按钮的可用性完全取决于
 * taskStatus 与 completedSegments —— 不刷新就既看不到「跑到第几段了」，
 * 也无法在合成刚开始时立刻出现「暂停」按钮。
 *
 * <p>只在有任务处于进行中时挂定时器；全部合成完（或都暂停）后自动停，
 * 空闲页面不做无谓请求。每次 loadBooks 末尾都会重估一次，
 * 因此最后一次刷新看到「已全部完成」就会把定时器摘掉。
 */
const AUTO_REFRESH_MS = 6000
let autoRefreshTimer: ReturnType<typeof setInterval> | null = null

const stopAutoRefresh = () => {
  if (autoRefreshTimer !== null) {
    clearInterval(autoRefreshTimer)
    autoRefreshTimer = null
  }
}

const syncAutoRefresh = () => {
  const hasActive = books.value.some(b => ACTIVE_PHASES.has(synthPhase(b)))
  if (!hasActive || autoRefreshTimer !== null) {
    if (!hasActive) stopAutoRefresh()
    return
  }
  autoRefreshTimer = setInterval(() => {
    // 上一次请求还没回来就跳过这一拍，避免请求叠加
    if (!loading.value) void loadBooks()
  }, AUTO_REFRESH_MS)
}

// antd 的 TablePaginationConfig 里 current/pageSize 都是可选的，
// 直接 pagination.current - 1 在字段缺失时会算出 NaN 并被带进请求参数
const handleTableChange = (pagination: { current?: number; pageSize?: number }) => {
  const newSize = pagination.pageSize ?? pageSize.value
  const newCurrent = pagination.current ?? 1
  // 页大小变化时重置到第 1 页（0 基），否则按当前页切换
  if (newSize !== pageSize.value) {
    currentPage.value = 0
  } else {
    currentPage.value = Math.max(0, newCurrent - 1)
  }
  pageSize.value = newSize
  loadBooks()
}

const handleSearch = () => {
  currentPage.value = 0
  loadBooks()
}

// ===================== 上传电子书 =====================

/** 服务器上已有的分类（磁盘目录 ∪ 数据库已用分类），用于联想与判断是否新建目录 */
const categories = ref<string[]>([])
const categoryOptions = computed(() => categories.value.map((name) => ({ value: name, label: name })))

const showUploadModal = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const uploadFileList = ref<UploadFile[]>([])
const bookTitle = ref('')
/** 原书名（选填）：单纯存档，选择文件时不自动填充，也不参与任何落盘/去重逻辑 */
const bookOriginalTitle = ref('')
const bookAuthor = ref('')
const bookCategory = ref('')
const bookDescription = ref('')
/**
 * 上传时同时打的标签（可多个，可自由输入新标签）。
 * 提交时拼成逗号串交给 multipart 的单个 `tags` 字段 —— 服务端只解析这一个字段。
 */
const bookTags = ref<string[]>([])
// 用于在取消时中断正在上传的请求
const uploadAbort = ref<AbortController | null>(null)

const loadCategories = async () => {
  try {
    const response = await adminApi.getBookCategories()
    if (response.data.success) {
      const list = response.data.categories ?? []
      categories.value = list.filter((name) => typeof name === 'string' && name.length > 0)
    }
  } catch (error: unknown) {
    // 联想失败不影响上传（用户仍可手填分类），仅提示一次
    if (!isHandledByInterceptor(error)) {
      message.warning('获取已有分类失败，可手动填写分类')
    }
  }
}

/** 去掉扩展名后的文件名，用作书名默认值 */
const stripExtension = (filename: string): string => {
  const dotIndex = filename.lastIndexOf('.')
  return dotIndex > 0 ? filename.substring(0, dotIndex) : filename
}

const handleUploadFileChange = (info: { file: UploadFile; fileList: UploadFile[] }) => {
  const picked = info.fileList.slice(-1)
  uploadFileList.value = picked
  // 选择文件时用文件名预填书名，用户改过就保留用户输入
  const name = picked[0]?.name
  if (name && !bookTitle.value) {
    bookTitle.value = stripExtension(name)
  }
}

/** 打开发上传弹窗时顺手刷新标签联想池（刚在别处打过的新标签要能选到） */
const openUploadModal = () => {
  showUploadModal.value = true
  loadTagOptions()
}

/** 上传前校验：格式与大小提前拦截（与模板 accept 列表、服务端限制一致） */
const validateBookFile = (file: File): boolean => {
  const ext = file.name.split('.').pop()?.toLowerCase() || ''
  if (!ALLOWED_BOOK_EXTENSIONS.includes(ext)) {
    message.warning(`不支持的格式 .${ext}，仅支持 ${ALLOWED_BOOK_EXTENSIONS.join(' / ')}`)
    return false
  }
  if (file.size > MAX_BOOK_FILE_SIZE) {
    message.warning('文件大小不能超过 50MB')
    return false
  }
  if (file.size === 0) {
    message.warning('文件内容为空')
    return false
  }
  return true
}

/** 上传弹窗关闭或取消时复位表单 */
const resetUploadForm = () => {
  uploadFileList.value = []
  bookTitle.value = ''
  bookOriginalTitle.value = ''
  bookAuthor.value = ''
  bookCategory.value = ''
  bookDescription.value = ''
  bookTags.value = []
  uploadProgress.value = 0
}

const handleUploadCancel = () => {
  if (uploadAbort.value) {
    uploadAbort.value.abort()
    uploadAbort.value = null
  }
  resetUploadForm()
}

const confirmUpload = async () => {
  // before-upload 返回 false 时，不同版本的 ant-design-vue 可能把原始 File 放在
  // originFileObj，也可能直接把 File 自身塞进 fileList，这里两种形态都兼容
  const picked = uploadFileList.value[0] as (UploadFile & { originFileObj?: File }) | undefined
  const file = (picked?.originFileObj ?? (picked as unknown as File | undefined)) as File | undefined
  if (!file || typeof file.size !== 'number') {
    message.warning('请选择电子书文件')
    return
  }
  if (!validateBookFile(file)) return

  // 分类为空时服务端兜底为「其他」；这里本地也兜底，保证提示文案与实际落盘一致
  const category = bookCategory.value.trim() || '其他'

  const controller = new AbortController()
  uploadAbort.value = controller

  uploading.value = true
  uploadProgress.value = 0
  try {
    const response = await adminApi.uploadBook(
      file,
      {
        title: bookTitle.value.trim() || undefined,
        originalTitle: bookOriginalTitle.value.trim() || undefined,
        author: bookAuthor.value.trim() || undefined,
        category,
        description: bookDescription.value.trim() || undefined,
        // 逗号分隔（服务端按 [,，;；换行] 切分，这里用半角逗号统一拼法）
        tags: bookTags.value.length ? bookTags.value.join(',') : undefined,
      },
      (p) => (uploadProgress.value = p),
      controller.signal,
    )
    if (response.data.success) {
      const saved = response.data.book
      // 服务端会对分类名做安全清洗，以落库后的分类名为准判断是否为新建目录
      const finalCategory = saved?.category || category
      const isNewCategory = !categories.value.includes(finalCategory)
      message.success(
        isNewCategory
          ? `上传成功，已在服务器新建分类「${finalCategory}」`
          : `上传成功，已归入分类「${finalCategory}」`,
      )
      showUploadModal.value = false
      resetUploadForm()
      loadCategories()
      currentPage.value = 0
      loadBooks()
    } else {
      message.error(response.data.message || '上传失败')
    }
  } catch (error: unknown) {
    // 主动取消上传：不要弹出"上传成功"，也不要当作失败报错
    if (isCancel(error)) {
      return
    }
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '上传失败')
    }
  } finally {
    uploading.value = false
    uploadProgress.value = 0
    uploadAbort.value = null
  }
}

// ===================== 标签编辑 =====================

/**
 * 已用过的标签名（联想词）。
 *
 * 每次打开弹窗都重新拉一次而不是启动时缓存一次：管理员可能刚在另一本书上打了新标签，
 * 用旧列表联想会让他以为那个标签"不存在"而重新发明一个同义写法。
 * 失败不阻断编辑（Select 的 mode="tags" 支持自由输入），仅静默降级为空联想。
 */
const tagOptions = ref<string[]>([])

const loadTagOptions = async () => {
  try {
    const response = await adminApi.getBookTags()
    if (response.data.success) {
      tagOptions.value = (response.data.tags ?? []).filter(
        (name): name is string => typeof name === 'string' && name.length > 0,
      )
    }
  } catch {
    // 联想失败不影响保存，用户仍可手输标签
  }
}

const showTagModal = ref(false)
/** 正在编辑标签的书（弹窗打开期间被表格刷新替换掉也无妨，保存时只认 id） */
const tagModalBook = ref<StoreBook | null>(null)
const tagModalValue = ref<string[]>([])
const tagSaving = ref(false)

const openTagModal = (book: StoreBook) => {
  tagModalBook.value = book
  // 复制一份再绑到 Select：直接引用 record.tags 会让输入框边打字边改列表里的数据，
  // 取消时列表已经被改脏了
  tagModalValue.value = [...(book.tags ?? [])]
  showTagModal.value = true
  loadTagOptions()
}

const confirmSaveTags = async () => {
  const book = tagModalBook.value
  if (!book) return
  tagSaving.value = true
  try {
    // 服务端是全量替换语义：提交什么就是最终集合（去重/截断也在服务端做）。
    // 所以这里原样提交 Select 的当前值，不在前端自己清洗，避免两套规则不一致。
    const response = await adminApi.setBookTags(book.id, tagModalValue.value)
    if (response.data.success) {
      const applied = response.data.book?.tags ?? []
      // 就地更新本行而不是整页 loadBooks()：翻页/搜索/6s 轮询任一在路上时，
      // 整页刷新会把用户刚看到的行顺序/页码冲掉
      const index = books.value.findIndex((b) => b.id === book.id)
      if (index >= 0) {
        books.value[index] = { ...books.value[index], tags: applied }
      }
      message.success(applied.length ? `标签已更新：${applied.join('、')}` : '已清空标签')
      showTagModal.value = false
      // 新标签要立刻进入联想池（下次打开别的书就能选到）
      loadTagOptions()
    } else {
      message.error(response.data.message || '保存标签失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '保存标签失败')
    }
  } finally {
    tagSaving.value = false
  }
}

const confirmDelete = (book: StoreBook) => {
  Modal.confirm({
    title: '确认删除',
    icon: () => h(ExclamationCircleOutlined),
    content: `确定要删除书籍「${book.title}」吗？此操作不可恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const response = await adminApi.deleteBook(book.id)
        if (response.data.success) {
          message.success('书籍已删除')
          // 删除当前页最后一条时，依据服务端返回的剩余总量回退页码
          const newTotal = totalElements.value - 1
          const pages = Math.max(1, Math.ceil(newTotal / pageSize.value))
          if (currentPage.value >= pages) {
            currentPage.value = pages - 1
          }
          loadBooks()
          // 删掉某分类最后一本书后，该分类是否仍存在取决于磁盘目录，刷新一次联想列表
          loadCategories()
        } else {
          message.error(response.data.message || '删除失败')
        }
      } catch (error: unknown) {
        if (!isHandledByInterceptor(error)) {
          const msg = isAxiosError(error) ? error.response?.data?.message : null
          message.error(msg || '删除失败')
        }
      }
    },
  })
}

// ===================== 书城合成语音：状态与操作 =====================

/**
 * 一本书的合成阶段。与服务端 TaskStatus 对齐，但收敛成本地枚举：
 * - none      未合成（task_id 为空）
 * - queued    已建任务未开跑（pending / queued）
 * - processing 合成中
 * - paused    管理员已暂停（可继续，断点续跑）
 * - completed 已全部合成
 * - partial   部分段落失败，可重试
 * - failed    合成失败
 */
type SynthPhase = 'none' | 'queued' | 'processing' | 'paused' | 'completed' | 'partial' | 'failed' | 'broken'

/** 仍在跑（未开始/排队/合成中）的阶段：这三种才能点「暂停」 */
const ACTIVE_PHASES: ReadonlySet<SynthPhase> = new Set<SynthPhase>(['queued', 'processing'])

/**
 * 由 taskId + taskStatus 推导展示阶段。
 *
 * `default → broken`：taskId 非空但取不到状态，说明任务行已经不在了
 * （悬挂 task_id，例如任务被单独删除、库被手工清理），或服务端给了不认识的状态。
 * 早先这里 `default → completed`，理由是「宁可显示已合成也不要谎报未合成」——
 * 但 task_id 只是「派发过任务」的凭证，不保证有任何产物：
 * 一本任务已删的书会被显示成「已合成」并把按钮禁用，管理员只能去点「删除语音」才能自愈，
 * 这与 partial 那次的死局是同一个错。现在如实标明「状态未知」，并在提示里指明出路。
 */
const synthPhase = (book: StoreBook): SynthPhase => {
  if (!book.taskId) return 'none'
  switch (book.taskStatus) {
    case 'pending':
    case 'queued': return 'queued'
    case 'processing': return 'processing'
    case 'paused': return 'paused'
    case 'partial': return 'partial'
    case 'failed': return 'failed'
    case 'completed': return 'completed'
    default: return 'broken'
  }
}

const PHASE_TEXT: Record<SynthPhase, string> = {
  none: '未合成',
  queued: '排队中',
  processing: '合成中',
  paused: '已暂停',
  completed: '已合成',
  partial: '部分完成',
  failed: '合成失败',
  broken: '状态未知',
}

const PHASE_COLOR: Record<SynthPhase, string> = {
  none: 'default',
  queued: 'gold',
  processing: 'processing',
  paused: 'warning',
  completed: 'blue',
  partial: 'orange',
  failed: 'red',
  broken: 'red',
}

/** 各阶段徽标图标（模块级常量：只建一次，不在每次渲染时重建 VNode） */
const PHASE_ICON: Record<SynthPhase, unknown> = {
  none: h(ClockCircleOutlined),
  queued: h(ClockCircleOutlined),
  processing: h(SoundOutlined),
  paused: h(PauseCircleOutlined),
  completed: h(CheckCircleOutlined),
  partial: h(WarningOutlined),
  failed: h(CloseCircleOutlined),
  broken: h(WarningOutlined),
}

/**
 * 悬停提示：说清**这个状态下下一步该点哪个按钮**。
 *
 * <p>管理端最容易踩的坑不是「看不懂状态」，而是「看懂了也不知道能做什么」——
 * 尤其是 partial：徽标写着「部分完成」，按钮区却全是灰的，只能怀疑要重来。
 * 提示里直接给出动作名，与右侧按钮文案一一对应。
 */
const PHASE_HINT: Partial<Record<SynthPhase, string>> = {
  queued: '已排队，可点「暂停」在开跑前停下',
  processing: '合成中，可点「暂停」在当前段落结束后停下',
  paused: '已暂停，点「继续」从断点接着跑',
  partial: '部分段落没合成成功，点「继续合成」只补缺的段落（已完成的不会重做）',
  failed: '全部段落都没合成成功，点「继续合成」重试；若要换音色从头来，请先「删除语音」',
  completed: '合成完成，可直接下载音频',
  broken: '任务记录已失效（状态未知）。点「删除语音」清理绑定后，即可重新发起合成',
}

/** 进行中/暂停时的进度文案「12 / 2500 段」；总段数未知（老任务 total 为 0）时只报已完成数 */
const progressText = (book: StoreBook): string => {
  const done = book.taskCompletedSegments ?? 0
  const total = book.taskTotalSegments ?? 0
  return total > 0 ? `${done} / ${total} 段` : `已完成 ${done} 段`
}

/**
 * 正在操作中的书籍 id 集合：只禁用**对应那一行**的按钮。
 *
 * <p>原先是单个 `ref<number|null>`，两行同时操作时会出现两个错：
 *  - A 行开始时把 B 行的锁顶掉 → B 行的 loading 消失，可以再点一次；
 *  - A 行结束时 `= null` → 把 B 行的锁也清了，B 还在跑却显示可用。
 * 用集合后同 id 仍然互斥（同一行不能连点），不同行互不干扰。
 */
const busyBookIds = ref<Set<number>>(new Set())
const isBookBusy = (id: number): boolean => busyBookIds.value.has(id)
const markBookBusy = (id: number): void => {
  const next = new Set(busyBookIds.value)
  next.add(id)
  busyBookIds.value = next
}
const unmarkBookBusy = (id: number): void => {
  const next = new Set(busyBookIds.value)
  next.delete(id)
  busyBookIds.value = next
}

const handlePauseSynth = (book: StoreBook) => {
  Modal.confirm({
    title: '暂停语音合成',
    content: `确定暂停《${book.title}》的语音合成吗？已完成并保存的段落不会丢失，可随时点「继续合成」从断点接着跑。`,
    okText: '暂停合成',
    cancelText: '取消',
    onOk: async () => {
      markBookBusy(book.id)
      try {
        const res = await adminApi.pauseBookSynthesis(book.id)
        if (res.data.success) {
          message.success('已发送暂停指令，正在合成的那一段完成后即停止')
        } else {
          message.error(res.data.message || '暂停失败')
        }
      } catch (error: unknown) {
        if (!isHandledByInterceptor(error)) {
          message.error(isAxiosError(error) ? error.response?.data?.message || '暂停失败' : '暂停失败')
        }
      } finally {
        unmarkBookBusy(book.id)
        loadBooks()
      }
    },
  })
}

/** 继续合成是幂等且无损的动作（断点续跑），不需要二次确认 */
const handleResumeSynth = async (book: StoreBook) => {
  markBookBusy(book.id)
  try {
    const res = await adminApi.resumeBookSynthesis(book.id)
    if (res.data.success) {
      message.success('已继续合成')
    } else {
      message.error(res.data.message || '继续失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      message.error(isAxiosError(error) ? error.response?.data?.message || '继续失败' : '继续失败')
    }
  } finally {
    unmarkBookBusy(book.id)
    loadBooks()
  }
}

/**
 * 继续合成（补齐缺失段）：只重跑未成功的段落，已完成的原样保留。
 *
 * <p>幂等且无损（断点续跑），不需要二次确认 —— 与「暂停」不同，它不会丢任何已完成的工作。
 * 失败原因若仍存在（节点仍然不可用），任务会再次停在 partial，届时提示里会带上新原因。
 */
const handleRetrySynth = async (book: StoreBook) => {
  markBookBusy(book.id)
  try {
    const res = await adminApi.retryBookSynthesis(book.id)
    if (res.data.success) {
      message.success(res.data.message || '已继续合成，只补未完成的段落')
    } else {
      message.error(res.data.message || '继续合成失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      message.error(isAxiosError(error) ? error.response?.data?.message || '继续合成失败' : '继续合成失败')
    }
  } finally {
    unmarkBookBusy(book.id)
    loadBooks()
  }
}

/** 只删语音（音频文件 + 段落记录 + 任务行），书本身保留，删完可重新发起合成 */
const handleDeleteAudio = (book: StoreBook) => {
  Modal.confirm({
    title: '删除已合成的语音',
    content: `确定删除《${book.title}》已合成的语音吗？音频文件与段落记录将被永久删除，书籍本身保留，删除后可重新发起合成。`,
    okText: '删除语音',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      markBookBusy(book.id)
      try {
        const res = await adminApi.deleteBookAudio(book.id)
        if (res.data.success) {
          message.success('语音已删除')
        } else {
          message.error(res.data.message || '删除语音失败')
        }
      } catch (error: unknown) {
        if (!isHandledByInterceptor(error)) {
          message.error(isAxiosError(error) ? error.response?.data?.message || '删除语音失败' : '删除语音失败')
        }
      } finally {
        unmarkBookBusy(book.id)
        loadBooks()
      }
    },
  })
}

// ===================== 书城合成语音 =====================

/** 服务器参考音频列表（来自 /admin/reference-audios） */
interface AdminReferenceAudio {
  id: number
  name: string
  description?: string | null
  fileName?: string | null
}

const referencesLoading = ref(false)
const referenceAudios = ref<AdminReferenceAudio[]>([])

/**
 * 服务器的"音色源"选项：单本和批量弹窗共用，数据形态为
 * { id, name }（id 用作传回后端的 referenceId 或 voiceId；label 是中文展示名）。
 * 单本弹窗把它当作参考音频候选项；
 * 批量弹窗仅展示其中一种类型（按用户选定的 sourceKind 切换）。
 */
interface SynthSourceOption {
  id: string
  label: string
  description?: string
}

const referenceOptions = computed<SynthSourceOption[]>(() =>
  // 必须过滤掉 fileName 为空的项：后端 /synthesize 的 referenceId 是「服务器共享库里的文件名」，
  // 不是记录 id。若退化成 String(a.id)，后端拿到一个库里不存在的名字，
  // 轻则校验失败报错、重则建出必然合成失败的任务——宁可不显示该选项也不要静默传错值。
  referenceAudios.value
    .filter((a): a is AdminReferenceAudio & { fileName: string } => !!a.fileName)
    .map((a) => ({
      id: a.fileName,
      label: a.name,
      description: a.description || undefined,
    })),
)

/** 加载参考音频（弹窗打开时按需拉，避免首屏就发请求） */
const loadReferenceAudios = async () => {
  if (referenceAudios.value.length > 0) return
  referencesLoading.value = true
  try {
    const res = await adminApi.getReferenceAudios()
    if (res.data.success) {
      referenceAudios.value = res.data.content ?? []
    }
  } catch (error: unknown) {
    // 401 已由拦截器弹过「登录已过期」模态，这里再弹一条 warning 会变成双提示
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.warning(msg || '参考音频列表加载失败，可在弹窗内重试')
    }
  } finally {
    referencesLoading.value = false
  }
}

/**
 * 预设音色清单（GET /api/tts/voices），与参考音频同样按需拉取。
 * 之前这里是手输 Input，placeholder 写着"参见后端列表"却没有校验——
 * 输错等于建出一个必然合成失败的任务，所以改成受控下拉。
 */
const voices = ref<AdminVoiceOption[]>([])
const voicesLoading = ref(false)

const loadVoices = async () => {
  if (voices.value.length > 0) return
  voicesLoading.value = true
  try {
    const res = await adminApi.getVoices()
    if (res.data.success) {
      voices.value = res.data.voices ?? []
    }
  } catch (error: unknown) {
    // 同上：登录过期由拦截器统一提示，这里只处理真正的加载失败
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.warning(msg || '预设音色列表加载失败，可关闭弹窗后重试')
    }
  } finally {
    voicesLoading.value = false
  }
}

/** 下拉选项：label 同时带上中文名与音色 id，避免"看到的名字"和"提交的 id"对不上 */
const voiceOptions = computed(() =>
  voices.value.map((v) => ({ value: v.id, label: `${v.name}（${v.id}）` })),
)

// ----- 单本合成弹窗 -----
const showSynthModal = ref(false)
const synthTarget = ref<StoreBook | null>(null)
const synthSubmitting = ref(false)
const synthKind = ref<'reference' | 'voice'>('reference')
const synthReferenceId = ref<string | undefined>(undefined)
const synthVoiceId = ref<string | undefined>(undefined)

const resetSynthModal = () => {
  synthKind.value = 'reference'
  synthReferenceId.value = undefined
  synthVoiceId.value = undefined
}

const openSynthesizeModal = (book: StoreBook) => {
  synthTarget.value = book
  resetSynthModal()
  showSynthModal.value = true
  loadReferenceAudios()
  loadVoices()
}

const synthOkDisabled = computed(() => {
  if (synthKind.value === 'reference') return !synthReferenceId.value
  return !synthVoiceId.value
})

const confirmSynthesize = async () => {
  if (!synthTarget.value || synthOkDisabled.value) return
  const body = synthKind.value === 'reference'
    ? { referenceId: synthReferenceId.value ?? null, voiceId: null as string | null }
    : { referenceId: null as string | null, voiceId: synthVoiceId.value ?? null }
  synthSubmitting.value = true
  try {
    const res = await adminApi.synthesizeBook(synthTarget.value.id, body)
    if (res.data.success) {
      message.success(`已为《${synthTarget.value.title}》发起合成（taskId=${res.data.taskId}）`)
      showSynthModal.value = false
      loadBooks()
    } else {
      message.error(res.data.message || '发起合成失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '发起合成失败，请稍后重试')
    }
  } finally {
    synthSubmitting.value = false
  }
}

// ----- 批量合成弹窗 -----
const showBatchModal = ref(false)
const batchSubmitting = ref(false)
const batchKind = ref<'reference' | 'voice'>('reference')
const batchReferenceId = ref<string | undefined>(undefined)
const batchVoiceId = ref<string | undefined>(undefined)

const resetBatchModal = () => {
  batchKind.value = 'reference'
  batchReferenceId.value = undefined
  batchVoiceId.value = undefined
}

const openBatchModal = () => {
  resetBatchModal()
  showBatchModal.value = true
  loadReferenceAudios()
  loadVoices()
}

const batchOkDisabled = computed(() => {
  if (batchKind.value === 'reference') return !batchReferenceId.value
  return !batchVoiceId.value
})

const confirmBatchSynthesize = async () => {
  if (batchOkDisabled.value) return
  const body = batchKind.value === 'reference'
    ? { referenceId: batchReferenceId.value ?? null, voiceId: null as string | null }
    : { referenceId: null as string | null, voiceId: batchVoiceId.value ?? null }
  batchSubmitting.value = true
  try {
    const res = await adminApi.batchSynthesize(body)
    if (res.data.success) {
      const r = res.data
      message.success(`已发起 ${r.submitted} 个合成任务${r.skipped > 0 ? `，跳过 ${r.skipped} 本` : ''}`)
      showBatchModal.value = false
      loadBooks()
    } else {
      message.error(res.data.message || '批量合成失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '批量合成失败，请稍后重试')
    }
  } finally {
    batchSubmitting.value = false
  }
}

// ----- 批量补齐（只补 partial / failed 的缺段） -----
const gapRetrying = ref(false)

/**
 * 批量补齐：与「批量合成」是两个动作。
 *
 * <p>批量合成是「从头做一版」（会换参考音频/音色，整本重烧）；
 * 批量补齐只重跑没成功的段，已完成的段与 mp3 原样保留。
 * 一本 6500 段的书缺 4 段时，这两个动作的代价差三个数量级，
 * 所以必须是两个按钮、两次确认，而不是一个带参数的按钮。
 */
const confirmBatchGapRetry = () => {
  Modal.confirm({
    title: '批量补齐未完成的合成',
    content: '将扫描所有「部分完成 / 合成失败」的书，只重新合成没成功的段落；'
      + '已完成的段落、音频文件与音色都原样保留。这个操作不会重烧整本书，也不会扣积分。',
    okText: '开始补齐',
    cancelText: '取消',
    onOk: async () => {
      gapRetrying.value = true
      try {
        const res = await adminApi.batchGapRetry({})
        if (res.data.success) {
          const r = res.data
          if (r.submitted > 0) {
            message.success(`已重新派发 ${r.submitted} 本书的补齐任务${r.skipped > 0 ? `，跳过 ${r.skipped} 本` : ''}`)
          } else {
            // 一本都没派发：多半是没有 partial/failed 的书，或全被状态判据拒绝。
            // 这种情况弹绿色「成功」会让人以为补了，必须如实说。
            message.warning(r.message || '没有需要补齐的书')
          }
          if (r.skipped > 0) {
            // 跳过原因是逐本不同的（绑定刚被改、播发被队列拒绝…），逐条列出来，
            // 只报一个数字管理员无从判断该重试还是该放弃
            Modal.warning({
              title: `${r.skipped} 本书未能补齐`,
              content: (r.results.skipped ?? [])
                .map(s => `《书 #${s.bookId}》：${s.message || '未知原因'}`)
                .join('\n'),
            })
          }
          loadBooks()
        } else {
          message.error(res.data.message || '批量补齐失败')
        }
      } catch (error: unknown) {
        if (!isHandledByInterceptor(error)) {
          const msg = isAxiosError(error) ? error.response?.data?.message : null
          message.error(msg || '批量补齐失败，请稍后重试')
        }
      } finally {
        gapRetrying.value = false
      }
    },
  })
}

onMounted(() => {
  loadBooks()
  loadCategories()
})

// 切换路由（组件卸载）时若有正在进行的上传请求，主动取消，避免游离请求
onUnmounted(() => {
  uploadAbort.value?.abort()
  // 释放全部封面 objectURL：置位后到达的响应也会在回调里自行释放
  coversDisposed = true
  releaseCovers()
  stopAutoRefresh()
})
</script>

<template>
  <Card>
    <div class="header">
      <Title :level="4">
        <BookOutlined /> 书城管理
      </Title>
      <Space>
        <Input
          v-model:value="keyword"
          placeholder="按书名/作者搜索"
          allow-clear
          style="width: 220px"
          @press-enter="handleSearch"
        />
        <Button @click="handleSearch">
          搜索
        </Button>
        <Button type="primary" @click="loadBooks">
          <ReloadOutlined /> 刷新
        </Button>
        <Button @click="openBatchModal">
          <SoundOutlined /> 批量合成
        </Button>
        <Button :loading="gapRetrying" @click="confirmBatchGapRetry">
          <ToolOutlined /> 批量补齐
        </Button>
        <Button type="primary" @click="openUploadModal">
          <UploadOutlined /> 上传电子书
        </Button>
      </Space>
    </div>

    <Table
      :columns="columns"
      :data-source="books"
      row-key="id"
      :loading="loading"
      :pagination="{
        current: currentPage + 1,
        pageSize: pageSize,
        total: totalElements,
        showSizeChanger: true,
        showTotal: (total: number) => `共 ${total} 条`,
      }"
      @change="handleTableChange"
    />

    <Modal
      v-model:open="showUploadModal"
      title="上传电子书"
      :ok-text="'上传'"
      :cancel-text="'取消'"
      :confirm-loading="uploading"
      :mask-closable="!uploading"
      @ok="confirmUpload"
      @cancel="handleUploadCancel"
      width="600px"
    >
      <div class="upload-form">
        <div class="form-item">
          <label>电子书文件 *</label>
          <Upload
            :file-list="uploadFileList"
            :max-count="1"
            accept=".txt,.pdf,.epub,.mobi,.md"
            :show-upload-list="true"
            :before-upload="() => false"
            @change="handleUploadFileChange"
          >
            <Button :disabled="uploading">
              <UploadOutlined /> 选择文件
            </Button>
          </Upload>
          <span class="hint">支持 TXT / PDF / EPUB / MOBI，最大 50MB</span>
        </div>

        <div v-if="uploading" class="form-item">
          <Progress :percent="uploadProgress" :status="uploadProgress >= 100 ? 'success' : 'active'" />
          <span v-if="uploadProgress >= 100" class="hint">文件已传完，服务器正在保存，请稍候…</span>
        </div>

        <div class="form-item">
          <label>书名 *</label>
          <Input v-model:value="bookTitle" placeholder="选择文件后自动填充，可修改" :maxlength="200" />
        </div>

        <div class="form-item">
          <label>原书名</label>
          <Input
            v-model:value="bookOriginalTitle"
            placeholder="选填，仅作存档（如译名对照、版权页原始名称）"
            :maxlength="200"
            allow-clear
          />
        </div>

        <div class="form-item">
          <label>作者</label>
          <Input v-model:value="bookAuthor" placeholder="留空则记为「佚名」" :maxlength="100" />
        </div>

        <div class="form-item">
          <label>分类</label>
          <AutoComplete
            v-model:value="bookCategory"
            :options="categoryOptions"
            placeholder="可下拉选择已有分类，或直接输入新分类"
            :filter-option="true"
            allow-clear
          />
          <span class="hint">
            已有分类 {{ categories.length }} 个；填写不存在的分类时，服务器会新建同名文件夹
          </span>
        </div>

        <div class="form-item">
          <label>标签</label>
          <Select
            v-model:value="bookTags"
            mode="tags"
            style="width: 100%"
            placeholder="选填，可从已有标签里挑，也可直接输入新标签后回车"
            :options="tagOptions.map((name) => ({ value: name, label: name }))"
            :token-separators="[',', '，']"
          />
          <span class="hint">
            一本书可以有多个标签（最多 10 个，单个不超过 20 字）；标签与分类并存，不是替代关系
          </span>
        </div>

        <div class="form-item">
          <label>简介</label>
          <Input.TextArea
            v-model:value="bookDescription"
            :rows="3"
            placeholder="书籍简介（可选）"
            :maxlength="2000"
          />
        </div>
      </div>
    </Modal>

    <!-- ===== 标签编辑（一本书多个标签，全量替换语义） ===== -->
    <Modal
      v-model:open="showTagModal"
      :title="tagModalBook ? `《${tagModalBook.title}》的标签` : '标签'"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="tagSaving"
      :mask-closable="!tagSaving"
      width="520px"
      @ok="confirmSaveTags"
    >
      <div class="synth-form">
        <div class="form-item">
          <label>标签</label>
          <Select
            v-model:value="tagModalValue"
            mode="tags"
            style="width: 100%"
            placeholder="从已有标签里挑，或输入新标签后回车"
            :options="tagOptions.map((name) => ({ value: name, label: name }))"
            :token-separators="[',', '，']"
          />
          <span class="hint">
            保存时以这里的全部内容为准（清空即删除该书所有标签）；最多 10 个，单个不超过 20 字，
            重复项与首尾空格由服务端统一处理
          </span>
        </div>
      </div>
    </Modal>

    <!-- ===== 单本书合成语音 ===== -->
    <Modal
      v-model:open="showSynthModal"
      :title="synthTarget ? `为《${synthTarget.title}》合成语音` : '合成语音'"
      ok-text="开始合成"
      cancel-text="取消"
      :confirm-loading="synthSubmitting"
      :ok-button-props="{ disabled: synthOkDisabled }"
      :mask-closable="!synthSubmitting"
      width="520px"
      @ok="confirmSynthesize"
    >
      <div class="synth-form">
        <div class="form-item">
          <label>音色源</label>
          <Select
            v-model:value="synthKind"
            style="width: 100%"
            :options="[
              { value: 'reference', label: '参考音频（来自「参考音频管理」）' },
              { value: 'voice', label: '预设音色' },
            ]"
          />
          <span class="hint">同一次合成两者取其一。管理员账户积分规则会自动免扣。</span>
        </div>
        <div v-if="synthKind === 'reference'" class="form-item">
          <label>选择参考音频 *</label>
          <Select
            v-model:value="synthReferenceId"
            :loading="referencesLoading"
            style="width: 100%"
            placeholder="选择参考音频"
            show-search
            :filter-option="(input: string, opt: any) => (opt.label as string).includes(input)"
          >
            <Select.Option
              v-for="opt in referenceOptions"
              :key="opt.id"
              :value="opt.id"
              :label="opt.label"
            >
              {{ opt.label }}
              <span v-if="opt.description" class="hint" style="margin-left: 6px">
                {{ opt.description }}
              </span>
            </Select.Option>
          </Select>
        </div>
        <div v-else class="form-item">
          <label>预设音色 *</label>
          <Select
            v-model:value="synthVoiceId"
            :loading="voicesLoading"
            style="width: 100%"
            placeholder="选择预设音色"
            show-search
            :filter-option="(input: string, opt: any) => String(opt.label).includes(input)"
            :options="voiceOptions"
          />
          <span class="hint">
            清单来自服务端 /api/tts/voices；如需自定义音色，请改用「参考音频」方式合成。
          </span>
        </div>
      </div>
    </Modal>

    <!-- ===== 批量合成所有未合成的书 ===== -->
    <Modal
      v-model:open="showBatchModal"
      title="批量合成未合成书"
      ok-text="开始批量合成"
      cancel-text="取消"
      :confirm-loading="batchSubmitting"
      :ok-button-props="{ disabled: batchOkDisabled }"
      :mask-closable="!batchSubmitting"
      width="520px"
      @ok="confirmBatchSynthesize"
    >
      <div class="synth-form">
        <div class="form-item">
          <label>音色源</label>
          <Select
            v-model:value="batchKind"
            style="width: 100%"
            :options="[
              { value: 'reference', label: '参考音频（来自「参考音频管理」）' },
              { value: 'voice', label: '预设音色' },
            ]"
          />
          <span class="hint">
            将扫描所有 task_id 为空的书籍挨个发起合成；已有音频的书会被自动跳过。
          </span>
        </div>
        <div v-if="batchKind === 'reference'" class="form-item">
          <label>选择参考音频 *</label>
          <Select
            v-model:value="batchReferenceId"
            :loading="referencesLoading"
            style="width: 100%"
            placeholder="选择参考音频"
            show-search
            :filter-option="(input: string, opt: any) => (opt.label as string).includes(input)"
          >
            <Select.Option
              v-for="opt in referenceOptions"
              :key="opt.id"
              :value="opt.id"
              :label="opt.label"
            >
              {{ opt.label }}
              <span v-if="opt.description" class="hint" style="margin-left: 6px">
                {{ opt.description }}
              </span>
            </Select.Option>
          </Select>
        </div>
        <div v-else class="form-item">
          <label>预设音色 *</label>
          <Select
            v-model:value="batchVoiceId"
            :loading="voicesLoading"
            style="width: 100%"
            placeholder="选择预设音色"
            show-search
            :filter-option="(input: string, opt: any) => String(opt.label).includes(input)"
            :options="voiceOptions"
          />
        </div>
      </div>
    </Modal>
  </Card>
</template>

<style scoped>
.header {
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.upload-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/** 与 .upload-form 同口径（合成弹窗复用） */
.synth-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-item label {
  font-weight: 500;
}

.hint {
  font-size: 12px;
  color: #999;
}
</style>
