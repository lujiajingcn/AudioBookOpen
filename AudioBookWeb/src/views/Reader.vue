<script setup lang="ts">
/**
 * 书籍阅读页（书城 → 左键点击书籍进入）。
 *
 * 两条数据来源，取决于这本书有没有合成音频：
 *  - 有音频（store_books.task_id 非空）：正文取 TTS 任务的片段列表，
 *    每段都能直接播放，段落与音频严格一一对应 —— 真正意义上的「边读边播」。
 *  - 无音频：正文取 GET /api/store/book/{id}/content（服务端按 id 提取），
 *    纯文本阅读，不显示播放控件。
 *
 * **显示用原文行，播/定位用片段**（两条路径都是）：
 * `paragraphs` / `segments` 是「切给 TTS 引擎的完整句子」（≤30 字是引擎单次上限，
 * 句子本身不限长），不是给人读的 —— 拿它当正文，用户看到的是一堵没有缩进、没有换行的
 * 碎片墙，PDF 的印刷目录页（点线引导）更会被切成一堆孤立的「.」。
 * 所以正文一律渲染服务端的 `rows`（原文行，保留行首缩进），
 * 再用 `rowParagraphStart/End` 把行映射回段落下标：正在播的段亮哪几行、
 * 目录给出段落下标后滚到该段的起始行。
 * 悬停/点击为**句级**定位：按鼠标所在 `.row-part`（服务端 rowParas 切出的句内片段）
 * 取所在句，高亮该句、从该句起播（详见 hoveredParagraph / onRowClick）。
 *
 * 服务端不返回 `rows` 时（TXT 由客户端本地解析 / 老服务端）自动退回旧口径
 * （渲染 paragraphs / segments），行为与改造前完全一致。
 */
import { ref, computed, watch, onMounted, onUnmounted, nextTick, h } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Card,
  Button,
  Tag,
  Space,
  message,
  Alert,
  Spin,
  Empty,
  Typography,
  Modal,
  List,
  Slider,
  Select,
} from 'ant-design-vue'
import {
  ArrowLeftOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  StepBackwardOutlined,
  StepForwardOutlined,
  UnorderedListOutlined,
  SoundOutlined,
  FontSizeOutlined,
  BackwardOutlined,
  ForwardOutlined,
  AimOutlined,
} from '@ant-design/icons-vue'
import {
  assertDownloadable,
  storeApi,
  type SegmentInfo,
  type BookParagraph,
  type BookTocEntry,
  type TocEntry,
} from '@/services/api'
import { useAudioEngine } from '@/composables/useAudioEngine'
import { usePlayProgress } from '@/composables/usePlayProgress'
import { useTextSelection, type TextActionKey, type TextSelectionInfo } from '@/composables/useTextSelection'
import { useSettingsStore } from '@/stores/settings'
import { setActivePauser } from '@/services/playbackControl'
import { favorites } from '@/services/favorites'
import { wordBook, wordBookVersion, stripWord, type WordBookSource } from '@/services/wordBook'
import {
  dictApi,
  isEnglishWord,
  singleCjkChar,
  type ChineseEntry,
  type DictEntry,
} from '@/services/dict'
import TextActionMenu from '@/components/TextActionMenu.vue'
import DictPanel from '@/components/DictPanel.vue'

const { Title, Text } = Typography

const route = useRoute()
const router = useRouter()
const settingsStore = useSettingsStore()

/** 校验路由参数是否为合法书籍 ID（纯数字，拒 '0x10' / '1e3' / ' 12 '） */
function parseId(raw: unknown): number | null {
  if (typeof raw !== 'string' || !/^\d+$/.test(raw)) return null
  const id = Number(raw)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

// 做成响应式：/reader/1 → /reader/2 参数跳转复用组件实例，
// const 捕获一次会导致页面永远显示旧书
const bookId = computed(() => parseId(route.params.bookId))
const queryName = computed(() => (route.query.name as string) || '')

/**
 * 单词本「跳转到原书」的定位 query（WordBook.vue 传入，由 applyWordJump 消费）：
 *  - `segment`：段号（1-based），兜底定位；
 *  - `wordJump`：'1' 表示本次是跳词进入，需跳过「继续上次阅读」确认，别让进度恢复抢走定位；
 *  - `sentence`：收藏时所在句子/段落原文，**定位第一判据**（段号会随切分粒度漂移）。
 * query 值都可能是 undefined / 数组 / 空串，逐项做类型收窄，绝不假设一定存在。
 */
const wordJump = computed(() => route.query.wordJump === '1')
const jumpSegment = computed(() => {
  const raw = route.query.segment
  const n = Number(typeof raw === 'string' ? raw : '')
  return Number.isInteger(n) && n > 0 ? n : null
})
const jumpSentence = computed(() =>
  typeof route.query.sentence === 'string' ? route.query.sentence.trim() : '',
)

const loading = ref(false)
const bookTitle = ref('')
const tocVisible = ref(false)

/** 无音频路径的正文段落（仅在服务端没有 rows 时用于兜底渲染） */
const paragraphs = ref<BookParagraph[]>([])
/** 无音频路径的目录 */
const plainToc = ref<BookTocEntry[]>([])
/** 有音频路径的片段列表（也是 useAudioEngine 的数据源） */
const segments = ref<SegmentInfo[]>([])
/**
 * 书城阅读进度：走**书口径**端点 `/api/book-progress`，身份是跨端统一的 `bookKey`。
 *
 * 为什么不用 `/api/progress?taskId=`：那是 TTS **私有任务**口径，服务端按任务归属校验
 * （`getOwnedTask`）。而书城书籍的 `task_id` 通常指向管理员的合成任务 —— 普通用户调私有
 * 端点必然 403，且 403 在 usePlayProgress.load 里被静默吞掉，表现为「断点续播莫名失效」。
 *
 * 为什么不用 `/api/store/book/{id}/progress`：那个端点的服务端落库键挂在
 * `store_books.task_id` 上，而**未合成的书 task_id 为空** ⇒ 纯阅读的书在所有端
 * 都记不住位置。改用 bookKey 后与合成状态无关，三端都能记。
 *
 * bookKey 的算法在 `@audiobook/shared`（与 Android/PC/服务端逐字符一致），
 * Web 端只有书城书 ⇒ 恒为 `s:{bookId}`。
 */
const storeProgress = usePlayProgress({
  loadUrl: (id) => `/book-progress`,
  loadParams: (id) => ({ bookKey: `s:${id}` }),
  saveUrl: (id) => `/book-progress`,
  saveBody: (id, segment, offsetSec) => ({
    bookKey: `s:${id}`,
    segment,
    offsetMs: offsetSec * 1000,
  }),
})
/** 有音频路径的目录 */
const segmentToc = ref<TocEntry[]>([])

/**
 * 本书合成任务 id（书城详情里的 task_id）。
 *
 * 只给收藏用：收藏的唯一身份是「书名 + 段号」，taskId 不参与去重，仅作「去听」深链辅助。
 * 书城书的 task 通常属于管理员，普通用户无权访问该任务接口 —— 所以它**只写不读**。
 */
const taskId = ref<number | undefined>(undefined)

/** 这本书是否有合成音频（决定走哪条渲染分支） */
const hasAudio = ref(false)

// ---------- 原文行（显示层）与 行→段 映射 ----------

/** 正文原文行（保留行首缩进）。有它就用它渲染，没有才退回片段/段落 */
const rows = ref<string[]>([])
/** 正文来源（W1）：snapshot = 与音频同源；live = 按当前文件实时提取 */
const contentSource = ref<'snapshot' | 'live' | ''>('')
/** 磁盘文件已与合成时的快照不同（W1）：正文仍是快照，需提示用户 */
const staleFile = ref(false)
/** rows[i] 首/末实字所属的段落下标；与 rows 等长、各自**单调不减** */
const rowParaStart = ref<number[]>([])
const rowParaEnd = ref<number[]>([])
/**
 * 每行内各段的字符区间：rowParas[i] = [[段落下标, 行内起始(含), 行内结束(含)], ...]。
 * 由服务端按「原文坐标」切开，使高亮的水平起点落在音频真实播放的句子起点、而非整行左缘
 * （否则整行铺满会盖到上一句的残字）。老服务端不返回时为空 → 回退整行块高亮。
 */
const rowParas = ref<number[][][]>([])

/** 服务端给了可用的行映射（长度必须对得上，宁可不用也不能错位） */
const usingRows = computed(
  () =>
    rows.value.length > 0 &&
    rowParaStart.value.length === rows.value.length &&
    rowParaEnd.value.length === rows.value.length,
)

/** 第一个「末字段 >= p」的行 = 第 p 段的第一行（下标数组单调，二分即可） */
const lowerBound = (arr: number[], value: number) => {
  let lo = 0
  let hi = arr.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (arr[mid] < value) lo = mid + 1
    else hi = mid
  }
  return lo
}

/** 第一个「首字段 > p」的行 = 第 p 段最后一行的**下一行**（开区间右端） */
const upperBound = (arr: number[], value: number) => {
  let lo = 0
  let hi = arr.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (arr[mid] <= value) lo = mid + 1
    else hi = mid
  }
  return lo
}

/** 第 p 段的第一行（目录跳转 / 高亮滚动的落点） */
const firstRowOfParagraph = (p: number) => {
  if (!usingRows.value || p < 0) return -1
  return lowerBound(rowParaEnd.value, p)
}

/**
 * 第 i 行**行首**所在的段落下标。
 *
 * 悬停/点击实际用的是「鼠标所在句子」（见 hoveredParagraph / onRowClick），
 * 这里只作为鼠标落在句间空白、或老服务端不返回 rowParas（整行一段）时的退回值。
 */
const paragraphOfRow = (i: number) => rowParaStart.value[i] ?? -1

/** 该行是否是一段的**第一行**：段号角标与合成状态标签只在这一行显示一次 */
const isParagraphHead = (i: number) =>
  usingRows.value && (i === 0 || rowParaStart.value[i] !== rowParaStart.value[i - 1])

/**
 * 段落号 → segments 下标的预索引。
 *
 * 原先是 `segments.value.findIndex(...)`，而它在 `rowStatus` / `isRowActive` 里
 * **每行调一次**、且 `currentIndex` 每变一次就整体重算一遍：
 * 3000 行 × 3000 段 ≈ 900 万次比较，播放时每次切段再付一次 ⇒ 主线程长任务、页面假死。
 * 段落号与下标都是稳定不变的，所以建一次 Map 即可把 O(n) 降成 O(1)。
 */
const segmentIndexByNumber = computed(() => {
  const m = new Map<number, number>()
  const list = segments.value
  for (let i = 0; i < list.length; i++) m.set(list[i].segmentNumber, i)
  return m
})

/** paragraphIndex（= 段号 - 1）→ segments 数组下标；对不上返回 -1 */
const segmentIndexOfParagraph = (p: number) => segmentIndexByNumber.value.get(p + 1) ?? -1

// 当前正在「读」的段落下标（无音频路径自行管理；有音频路径由播放引擎驱动）
const activeIndex = ref(-1)
/** 行/段落 DOM 引用：用于「跳到当前段」时滚动定位（有行时下标是行号，否则是段落号） */
const itemRefs = ref<HTMLElement[]>([])

const setItemRef = (el: Element | null, i: number) => {
  if (el instanceof HTMLElement) itemRefs.value[i] = el
}

// ---------- 播放引擎（仅在有音频时启用） ----------

const {
  currentIndex,
  isPlaying,
  buffering,
  currentTime,
  duration,
  playbackRate,
  volume,
  RATE_OPTIONS,
  currentSegment,
  playSegment,
  nextSegment,
  prevSegment,
  onSeek,
  skip,
  onRateChange,
  onVolumeChange,
  togglePlay,
  pause,
  teardown,
  loadProgress,
  sleepTimerMode,
  sleepTimerRemaining,
  sleepTimerSegmentsLeft,
  setSleepTimer,
  setSleepTimerSegments,
  SLEEP_TIMER_OFF,
  SLEEP_TIMER_END_OF_SEGMENT,
  SLEEP_TIMER_SEGMENTS,
} = useAudioEngine(
  segments,
  // 第二个参数是「进度键」：书城阅读页用 bookId —— 引擎内部 progress.save(bookId, …)
  // 与下方 loadProgress(bookId) 同源，并与书级进度端点一一对应（见 storeProgress 说明）
  computed(() => bookId.value),
  {
    // 书的音频是公共资源：段音频与进度都走书级端点。
    // 私有任务接口（/api/tts/**）会因任务归属校验对普通用户 403 ——
    // 表现就是「加载书籍内容失败」与「点击段落放不出声」。
    fetchSegment: async (seg) => {
      const id = bookId.value
      if (id == null) throw new Error('书籍 id 无效，请返回书城重新进入')
      const res = await storeApi.downloadBookSegment(id, seg.id)
      const blob = await assertDownloadable(res)
      if (!blob || blob.size === 0) {
        throw new Error('该片段音频内容为空，可能合成未完成或文件缺失')
      }
      return await blob.arrayBuffer()
    },
    progressStore: storeProgress,
  },
)

// 登录过期时由 401 拦截器调用，避免过期期间音频继续空放
setActivePauser(pause)

const completedCount = computed(() => segments.value.filter((s) => s.status === 'completed').length)

/**
 * 当前高亮的下标。
 *
 * 有音频时以播放引擎的 currentIndex 为准（它才是「正在出声的那一段」），
 * 但引擎在用户点播放前恒为 -1 —— 此时回退到 activeIndex，
 * 让正文仍有一个可见起点，否则整页素色、用户不知道从哪看起。
 * 无音频时引擎从未启动，直接用 activeIndex。
 */
const highlightIndex = computed(() => {
  if (hasAudio.value && currentIndex.value >= 0) return currentIndex.value
  return activeIndex.value
})

/**
 * 当前段占的行区间 [from, to) —— 正文渲染原文行时的「高亮哪几行」。
 *
 * 用两端下标各二分一次：`from` = 第一个「末字段 >= p」的行（该段**从这一行开始**，
 * 因为上一行的末字还在上一段），`to` = 第一个「首字段 > p」的行。
 *
 * 为什么右端要兜一个 `from + 1`：一行可以含两句（如「他说。他没说。」），
 * 那时该段不拥有任何「以它为行首」的行 ⇒ to == from ⇒ 整段不亮，
 * 播放时高亮会一闪一灭。行是显示的最小单位，往后续一行即可保证「正在播的段永远亮着」。
 */
/**
 * 第 p 段占的行区间 [from, to) —— 正文行「高亮」与「悬停预览」共用。
 *
 * 用两端下标各二分一次（见 isRowActive / firstRowOfParagraph 上方注释）。
 * 右端兜一个 from+1：一行可含两句，该段不拥有任何「以它为行首」的行时 to==from，
 * 续一行保证正播段永远亮着。
 */
const rowRangeOf = (p: number): [number, number] => {
  if (!usingRows.value || p < 0) return [-1, -1]
  const from = lowerBound(rowParaEnd.value, p)
  const to = upperBound(rowParaStart.value, p)
  return [from, to > from ? to : Math.min(rows.value.length, from + 1)]
}

const activeRowRange = computed<[number, number]>(() => rowRangeOf(highlightIndex.value))

/**
 * 悬停预览：鼠标移到某行时，高亮「鼠标所在的那一句」占的全部行（与点击后实色高亮同一块，只是更淡），
 * 让用户悬停时就看到「点了会播这一整句」，消除「单行 = 单行播放」的错觉。
 * 与当前正在播放的段重合时不重复描边。
 *
 * 句级定位：一行可含多句，按 mousemove 事件目标向上找 `.row-part`（服务端 rowParas 切出的
 * 句内片段，见 rowSegments），其 `data-p` 即所在句的段落下标；鼠标落在句间空白/角标上时为 -1。
 */
const hoveredParagraph = ref(-1)

/** 从事件目标解析所在句子：`.row-part` 上的 data-p 即段落下标，解析不到返回 -1 */
const paragraphFromEvent = (e: MouseEvent): number => {
  if (!(e.target instanceof HTMLElement)) return -1
  const part = e.target.closest<HTMLElement>('.row-part')
  if (!part) return -1
  const p = Number(part.dataset.p)
  return Number.isInteger(p) ? p : -1
}

/** 悬停即刷新所在句子：目标变了才写 ref，避免每次 mousemove 都触发重渲染 */
const onRowMousemove = (e: MouseEvent) => {
  const p = paragraphFromEvent(e)
  if (p !== hoveredParagraph.value) hoveredParagraph.value = p
}

const hoveredRowRange = computed<[number, number]>(() => rowRangeOf(hoveredParagraph.value))
const isRowHovered = (i: number) => {
  if (hoveredParagraph.value === highlightIndex.value) return false
  const [from, to] = hoveredRowRange.value
  return from >= 0 && i >= from && i < to
}

const isRowActive = (i: number) => {
  const [from, to] = activeRowRange.value
  return from >= 0 && i >= from && i < to
}

/**
 * 把第 i 行切成「按段落」的若干 span，供高亮按真实位置铺色。
 *
 * 服务端 rowParas[i] 已按原文坐标给出本行每段 [段落下标, 起始(含), 结束(含)]：
 * 正在播/悬停的那一段只点亮它占的字符区间 —— 某句从行中间开始时，第一行蓝底从
 * 该句真实起点起，不再盖到上一句残字；整句占满的行仍整行铺满。
 * 老服务端不返回 rowParas 时，整行退化成一段（段号取行首段），保持旧行为。
 */
interface RowSeg {
  text: string
  paragraphIndex: number
  /** 行内字符区间 [start, end]（含端），仅用于生成稳定的 v-for key */
  start: number
  end: number
}

/**
 * 行内分段结果的缓存（按行号）。
 *
 * <p>为什么需要：`rowSegments` 直接写在模板的 v-for 里，滚动/时间刷新/高亮变化
 * 任何一次重渲染都会对**每个可见行**重新把整行按段切开（substring + 新建对象）。
 * 同一份 `rowParagraphSegments` 下行内容是稳定的，缓存即可。
 *
 * <p>失效策略：只在 `applyRows` 里整体清空 —— 那是唯一替换 rows/rowParas 的入口。
 */
const rowSegCache = new Map<number, RowSeg[]>()

const rowSegments = (i: number): RowSeg[] => {
  const hit = rowSegCache.get(i)
  if (hit) return hit
  const segs = computeRowSegments(i)
  rowSegCache.set(i, segs)
  return segs
}

const computeRowSegments = (i: number): RowSeg[] => {
  const rowText = rows.value[i] ?? ''
  const paras = rowParas.value[i]
  if (!paras || paras.length === 0) {
    return [
      {
        text: rowText,
        paragraphIndex: rowParaStart.value[i] ?? -1,
        start: 0,
        end: rowText.length - 1,
      },
    ]
  }
  const segs: RowSeg[] = []
  let cursor = 0
  for (const seg of paras) {
    const p = seg[0]
    const start = seg[1]
    const end = seg[2]
    if (start > cursor) {
      segs.push({
        text: rowText.substring(cursor, start),
        paragraphIndex: -1,
        start: cursor,
        end: start - 1,
      })
    }
    segs.push({ text: rowText.substring(start, end + 1), paragraphIndex: p, start, end })
    cursor = end + 1
  }
  if (cursor < rowText.length) {
    segs.push({
      text: rowText.substring(cursor),
      paragraphIndex: -1,
      start: cursor,
      end: rowText.length - 1,
    })
  }
  return segs
}

/** 高亮项变化时要滚到的**显示单位**下标：有行时是「该段的第一行」，否则退回段落/片段号 */
const scrollTargetIndex = computed(() => {
  const p = highlightIndex.value
  if (p < 0) return -1
  if (usingRows.value) return firstRowOfParagraph(p)
  return p
})

const formatTime = (s: number) => {
  if (!Number.isFinite(s) || s < 0) return '00:00'
  const m = Math.floor(s / 60)
  const sec = Math.floor(s % 60)
  return `${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
}

const statusMeta = (status: string) => {
  switch (status) {
    case 'completed':
      return { color: 'success', text: '可播放' }
    case 'processing':
      return { color: 'processing', text: '合成中' }
    case 'pending':
      return { color: 'default', text: '待合成' }
    case 'failed':
      return { color: 'error', text: '失败' }
    default:
      return { color: 'default', text: status }
  }
}

// ---------- 数据加载 ----------

/**
 * 加载策略：先拉书城详情拿到 taskId，再决定正文来源。
 *
 * 不直接用 query 里的 taskId —— URL 可被手工改写，用书自身落库的 task_id
 * 才能保证「读到的正文」与「点的书」一致。
 */
const loadData = async () => {
  const id = bookId.value
  if (id == null) return
  loading.value = true
  try {
    const detailRes = await storeApi.getBookDetail(id)
    if (bookId.value !== id) return // 请求期间切了书：丢弃过期响应
    if (!detailRes.data.success || !detailRes.data.book) {
      message.error(detailRes.data.message || '书籍不存在')
      router.replace('/store')
      return
    }
    const book = detailRes.data.book
    bookTitle.value = book.title || queryName.value || `书籍 ${id}`
    taskId.value = book.taskId ?? undefined
    // 已合成过的书即视为「有音频」。任务可能仍在合成中（部分段未完成），
    // 此时阅读页照常打开，未完成的段在列表里显示状态、点了给提示。
    hasAudio.value = !!book.taskId

    if (book.taskId) {
      // task_id 只证明「派发过任务」，不证明「有可播的段」：
      // 任务行被单独删除（悬挂绑定）或正文还没提取完时，segments 是空的。
      // 这时若仍按有声书渲染，用户看到的是「有声书」标签 + 空白正文 +
      // 一个点了没反应的播放器 —— 比降级成纯文本糟得多。
      const ready = await loadAudioContent(id)
      if (!ready && bookId.value === id) {
        hasAudio.value = false
        await loadPlainContent(id)
      }
    } else {
      await loadPlainContent(id)
    }
  } catch (e) {
    // loadAudioContent 内部的续播提示等分支可能已给出更具体的提示；
    // 这里只在整条链路抛错（详情拉取失败 / 网络中断）时兜底，
    // 避免把「本书暂不支持在线阅读」再叠一句泛化错误
    message.error('加载书籍内容失败')
  } finally {
    loading.value = false
  }
  // DOM 渲染完成后再判定一次「回到播放处」：
  // watch(scrollTargetIndex) 是 pre-flush，在 DOM 更新**之前**就跑，那时 itemRefs 还是空的
  // （isRowVisible 会按"拿不到元素 ⇒ 视为可见"处理），按钮就永远出不来。
  await nextTick()
  updateBackToPlayVisibility()
  // 单词本跳转：正文加载 + DOM/refs 就绪后再定位（loading 期间 itemRefs 还是空的）
  if (wordJump.value) applyWordJump()
}

/**
 * 有音频：正文 = 本书的 TTS 片段列表（段落与音频一一对应）。
 *
 * 入参是**书城 bookId**，不是 taskId：走书级端点，普通用户无须也不应接触那个
 * 归管理员的私有合成任务（见 storeProgress 说明）。
 *
 * 同时拉 `/content` 取**原文行**（正文显示层）：片段是切给引擎的句子，拿它当正文
 * 就是一堵碎片墙，PDF 的印刷目录页更会被切成一堆孤立的「.」。
 * 该请求失败不影响播放（列在 rows 空时自动退回渲染片段，行为与改造前一致）。
 *
 * @returns 是否真的取到了可用片段。`false` 表示「这本书的 task_id 还在，但没有任何
 *   可播放的段」—— 任务行已被删除（悬挂绑定）或正文尚未提取完成。调用方据此退回纯文本，
 *   否则会渲染出「有声书」标签 + 空正文 + 点不响的播放器。
 */
const loadAudioContent = async (id: number): Promise<boolean> => {
  const [segRes, tocRes, contentRes] = await Promise.all([
    storeApi.getBookSegments(id),
    storeApi.getBookToc(id).catch(() => null),
    storeApi.getBookContent(id).catch(() => null),
  ])
  // v5/W2：请求期间若 bookId 被切换（/reader/1 → /reader/2），丢弃过期响应。
  // 段落/目录/正文行是三个独立请求，迟到的旧响应会把新书页面串成旧书内容；
  // 这种错位不会报错，只会让用户看到「书名是新的、正文是旧的」。
  //
  // 守卫必须放在**所有写入之前**：segments 是播放引擎的数据源，原先先赋值再判断，
  // 旧书的段落仍会覆盖新书（正文是新的、能播的却是旧书），失败提示也会多弹一条。
  if (bookId.value !== id) return true
  if (segRes.data.success) {
    segments.value = segRes.data.segments || []
  } else {
    message.error('加载书籍段落失败')
  }
  if (tocRes && tocRes.data.success) segmentToc.value = tocRes.data.toc || []
  applyRows(contentRes?.data)

  // 续播提示：与 Player.vue 同口径，段内只要有偏移就提示。
  // 此处的进度键是书城 bookId（书级端点），不再是 TTS 任务 id。
  const last = await loadProgress(id)
  // v5/W2：loadProgress 也是异步的，回来时可能又换了书 —— 二次复查。
  if (bookId.value !== id) return true
  // 单词本跳词进入时跳过「继续上次阅读」确认：否则进度恢复会把刚定位的句子抢走
  if (!wordJump.value && last && last.segmentNumber >= 1 && last.offset > 0) {
    const idx = segments.value.findIndex((s) => s.segmentNumber === last.segmentNumber)
    if (idx >= 0) {
      if (segments.value[idx]?.status !== 'completed') {
        message.warning('该段已失效，请重新选择')
        // 段失效不代表整本书没段落：按「有段落」返回，别触发纯文本回退
        return segments.value.length > 0
      }
      const offsetText = last.offset > 5 ? `（约 ${formatTime(last.offset)} 处）` : ''
      Modal.confirm({
        title: '继续上次阅读？',
        content: `上次读到第 ${last.segmentNumber} 段${offsetText}，是否从该处继续？`,
        okText: '继续',
        cancelText: '从头开始',
        // 防御性：不传 last.offset。上次的 offset 在浏览器切换账号 / 设备后可能属于别人，
        // 强行续播会触发段中起播（钟鼓楼段 8 偏移 4 秒 = 只听到最后 0.5 秒）。
        // 用户如要回到上次位置，自己用滚条/快进操作更稳。
        onOk: () => playSegment(idx),
      })
      // 让正文先滚到上次读到的位置，用户一眼就能对上「读到哪了」
      activeIndex.value = last.segmentNumber - 1
    }
  }
  // 从未播过也要给出一个高亮起点，否则正文一片素色、用户不知道从哪看起
  if (highlightIndex.value < 0 && segments.value.length > 0) {
    activeIndex.value = 0
  }
  // 段落来源是「书级端点」，task_id 存在不等于有段：任务行被删（悬挂绑定）、
  // 或正文尚未提取完，都会拿到空数组。交给调用方决定是否退回纯文本。
  return segments.value.length > 0
}

/** 无音频：正文 = 服务端按 bookId 提取的段落（有 rows 时用 rows 渲染） */
const loadPlainContent = async (id: number) => {
  const res = await storeApi.getBookContent(id)
  if (bookId.value !== id) return
  if (!res.data.success) {
    message.warning(res.data.message || '本书暂不支持在线阅读')
    return
  }
  paragraphs.value = res.data.paragraphs || []
  plainToc.value = res.data.toc || []
  applyRows(res.data)
  if (res.data.title) bookTitle.value = res.data.title
  if (res.data.paragraphs?.length || rows.value.length) activeIndex.value = 0
}

/**
 * 接住服务端的原文行与其「行 → 段」映射。
 *
 * 三个数组必须**同时**可用才认（长度全对得上）：错位的下标比没有下标更糟 ——
 * 会把高亮与跳转引到无关位置，而页面看上去一切正常。
 * 任一项缺失（TXT / 老服务端）就整体退回旧口径渲染。
 */
const applyRows = (data?: {
  rows?: string[]
  rowParagraphStart?: number[]
  rowParagraphEnd?: number[]
  rowParagraphSegments?: number[][][]
  source?: 'snapshot' | 'live'
  staleFile?: boolean
}) => {
  // W1：把「正文从哪来」一并记下来，供页面提示（数据本身仍按下面三个数组校验）
  contentSource.value = data?.source || ''
  staleFile.value = data?.staleFile === true
  const r = data?.rows || []
  const s = data?.rowParagraphStart || []
  const e = data?.rowParagraphEnd || []
  if (r.length > 0 && s.length === r.length && e.length === r.length) {
    rows.value = r
    rowParaStart.value = s
    rowParaEnd.value = e
    // 每行内各段字符区间：长度对得上才采用，否则留空回退整行块高亮（老服务端/错位都不用）
    rowParas.value =
      data?.rowParagraphSegments && data.rowParagraphSegments.length === r.length
        ? data.rowParagraphSegments
        : []
    // 行内容换了：分段缓存必须整体失效（这是唯一替换 rows/rowParas 的入口）
    rowSegCache.clear()
  }
}

// ---------- 交互 ----------

/**
 * 按段落下标播放（有音频时）。
 *  - 段未合成完成：提示具体原因，不静默无反应；
 *  - segmentNumber 与段落下标对不上（如合成任务是用旧切分规则跑的）：明确提示，
 *    否则会「点了没声音」且看不出原因。
 */
const playParagraph = (paragraphIndex: number) => {
  const idx = segmentIndexOfParagraph(paragraphIndex)
  if (idx < 0) {
    message.info('该段音频不存在（可能本书正在重新合成），请稍后再试')
    return
  }
  const seg = segments.value[idx]
  if (seg.status !== 'completed') {
    message.info(
      seg.status === 'failed'
        ? `第 ${seg.segmentNumber} 段合成失败，无法播放`
        : `第 ${seg.segmentNumber} 段尚未合成完成，暂时无法播放`,
    )
    return
  }
  // 用户**显式**点了某一段 ⇒ 就是要求画面跟着这段走，恢复自动跟随
  userScrolledAway.value = false
  playSegment(idx)
}

/**
 * 点击正文某一行 —— **从鼠标所在的那一句起播**（句级定位，不再整行行首句）：
 *  - 优先取点击事件目标所在句子（.row-part 的 data-p）；
 *  - 落在句间空白时退回悬停句，再退回该行行首句（老服务端整行一段时等价于旧行为）；
 *  - 无音频时仅移动高亮。
 */
const onRowClick = (i: number, e: MouseEvent) => {
  if (!usingRows.value) return
  // 长按选词后浏览器仍会补一个 click。此时用户要的是「选词」而不是「起播」，
  // 不拦的话每选一次词都会顺带把音频切到那一句（Android 端同类问题由手势判定解决）。
  if (selection.value) return
  const clicked = paragraphFromEvent(e)
  const p =
    clicked >= 0 ? clicked : hoveredParagraph.value >= 0 ? hoveredParagraph.value : paragraphOfRow(i)
  if (p < 0) return
  if (!hasAudio.value) {
    activeIndex.value = p
    return
  }
  playParagraph(p)
}

/**
 * 点击段落（仅在服务端没给原文行时使用，即改造前的旧口径）。
 * 这里入参是**渲染下标**而非段落下标：渲染的是 `paragraphs` / `segments` 数组本身，
 * 所以有音频时直接用数组下标喂播放引擎（`playParagraph` 走的是「段号 → 数组下标」映射，
 * 两者仅在段号连续时等价 —— 旧口径下不要去换汇）。
 */
const onParagraphClick = (i: number) => {
  if (selection.value) return
  if (!hasAudio.value) {
    activeIndex.value = i
    return
  }
  const seg = segments.value[i]
  if (!seg) return
  if (seg.status !== 'completed') {
    message.info(
      seg.status === 'failed'
        ? `第 ${seg.segmentNumber} 段合成失败，无法播放`
        : `第 ${seg.segmentNumber} 段尚未合成完成，暂时无法播放`,
    )
    return
  }
  playSegment(i)
}

/** 段号角标与状态标签：只在一段的第一行显示 */
const rowStatus = (i: number) => {
  if (!hasAudio.value || !isParagraphHead(i)) return null
  const idx = segmentIndexOfParagraph(paragraphOfRow(i))
  if (idx < 0) return { color: 'warning', text: '无音频' }
  const seg = segments.value[idx]
  return seg.status === 'completed' ? null : statusMeta(seg.status)
}

/**
 * 目录跳转：入参统一是**段落下标**（= 段号 - 1）——两条路径的段号口径本来就一致，
 * 只是来源不同（有音频取自片段目录的 segmentNumber，无音频取自正文目录的 paragraphIndex）。
 */
const jumpToToc = (paragraphIndex: number) => {
  tocVisible.value = false
  if (paragraphIndex < 0) return
  // 目录跳转是**显式**要求画面去那儿 ⇒ 恢复自动跟随
  userScrolledAway.value = false
  if (usingRows.value) {
    activeIndex.value = paragraphIndex
    // 目录弹窗关闭后再滚动，避免弹窗焦点抢占导致的滚动失败
    window.setTimeout(() => scrollToItem(firstRowOfParagraph(paragraphIndex)), 0)
    return
  }
  // 没有原文行（TXT / 老服务端）：维持旧口径 —— 有音频直接播该段，无音频移动高亮
  if (hasAudio.value) {
    const idx = segmentIndexOfParagraph(paragraphIndex)
    if (idx >= 0) playSegment(idx)
    return
  }
  activeIndex.value = paragraphIndex
  window.setTimeout(() => scrollToItem(paragraphIndex), 0)
}

/**
 * 正文容器（长按选区与「回到播放处」的视口判定都靠它）。
 *
 * 四套渲染分支（有音频-原文行 / 有音频-片段 / 无音频-原文行 / 无音频-段落）
 * 共用同一个 ref —— 任一时刻只渲染一套，Vue 会保证 ref 指向在渲染的那一个。
 */
const bodyRef = ref<HTMLElement | null>(null)

/**
 * 程序性滚动（我们自己 scrollIntoView）的抑制窗口。
 *
 * 浏览器的 `scroll` 事件分不清"用户滚的"与"我们滚的"，而这两者对"是否停止跟随"的
 * 含义完全相反：我们自己滚过去 ⇒ 正文已在播放位置，不能算用户滚走；
 * 用户滚走 ⇒ 必须停止跟随，否则下一段推进就把正文硬拽回去（正在看的与正在听的打架）。
 * 用一个时间窗把程序性滚动标出来，窗内的 scroll 一律不置"用户滚走"。
 */
const programmaticScrollUntil = ref(0)

/** pointerdown 之后若真的发生滚动 ⇒ 判定为用户拖动（拖滚动条） */
const pointerScrollCandidate = ref(false)
/** 触摸起点（移动端）：位移超过阈值才算翻页，避免把"点一下选词"误判成滚走 */
let touchStartY = 0

const scrollToItem = (index: number, behavior: ScrollBehavior = 'smooth') => {
  const el = itemRefs.value[index]
  if (!el) return
  programmaticScrollUntil.value = Date.now() + (behavior === 'smooth' ? 900 : 300)
  el.scrollIntoView({ behavior, block: 'center' })
}

// ---------- 「回到播放处」（对照 Android：BookContentActivity + BackToPlayTarget） ----------

/**
 * 用户已主动滚离播放位置 ⇒ 停止自动跟随。
 *
 * 语义与 Android 端的 `readAloudUserScrolledAway` / `audioUserScrolledAway` 一致：
 * 用户手动翻走后，**播放/朗读继续推进高亮**，但不再把正文拽回去；
 * 要回去得点「回到播放处」，那一下同时恢复自动跟随。
 */
const userScrolledAway = ref(false)

/** 播放位置已滚出视口时才显示「回到播放处」 */
const showBackToPlay = ref(false)

/**
 * 播放目标所在的**显示单位**下标（与 `scrollTargetIndex` 同口径：有行时是首行，否则是段落下标）。
 *
 * 只认**有音频的播放**：纯文本书的朗读走浏览器 `speechSynthesis`，是整段 utterance、
 * 没有句级进度，拿不到"正在念到哪一行"，因此无从定位（Android 端有逐句队列才做得出来）。
 */
const playbackTargetRow = computed(() => {
  if (!hasAudio.value) return -1
  const p = currentIndex.value
  if (p < 0) return -1
  return usingRows.value ? firstRowOfParagraph(p) : p
})

/**
 * 某一行是否在**可视区域**内。
 *
 * 可视区域 = 正文容器 ∩ 浏览器视口：
 *  - 桌面端 `.reader-body` 是独立滚动容器（max-height 68vh），真正的"翻页"发生在容器内部；
 *  - 移动端容器 `max-height: none`，滚动发生在页面上，容器可能远高于视口。
 * 取交集才能在两种布局下都判对。
 */
const isRowVisible = (row: number): boolean => {
  const el = itemRefs.value[row]
  const container = bodyRef.value
  // 拿不到元素时按"可见"处理：宁可不弹按钮，也不给一个点不动的按钮。
  if (!el || !container) return true
  const r = el.getBoundingClientRect()
  const c = container.getBoundingClientRect()
  const top = Math.max(c.top, 0)
  const bottom = Math.min(c.bottom, window.innerHeight)
  if (bottom <= top) return false
  return r.bottom > top && r.top < bottom
}

/**
 * 「回到播放处」的按需显隐：**有播放位置 且 该行不在视口内**才显示。
 *
 * 两个条件缺一不可：
 *  - 没有播放位置 ⇒ 按钮没有意义；
 *  - 目标行就在眼前 ⇒ "回去"是空操作，纯属噪声（用户自己滚回来后它会自己消失）。
 */
const updateBackToPlayVisibility = () => {
  const row = playbackTargetRow.value
  showBackToPlay.value = row >= 0 && !isRowVisible(row)
}

/**
 * 跟随播放位置：**近距离平滑、远距离瞬跳**（对应 Android 的 `ReaderScrollPolicy`）。
 *
 * 一律平滑滚动的话，打开书时若上次听到第 50 段，正文会从第一行一路"哗哗"滑过去，
 * 用户既不知道发生了什么也点不动；远距离他要的是瞬移，近距离（一屏以内）平滑动画眼睛才跟得上。
 */
const followRow = (index: number) => {
  const el = itemRefs.value[index]
  const container = bodyRef.value
  if (!el) return
  if (!container) {
    scrollToItem(index)
    return
  }
  const r = el.getBoundingClientRect()
  const c = container.getBoundingClientRect()
  if (r.bottom > c.top && r.top < c.bottom) return // 已在视口内，不必动
  const distance = Math.abs(r.top - c.top)
  scrollToItem(index, distance > container.clientHeight ? 'auto' : 'smooth')
}

/**
 * 「回到播放处」：把正文**瞬时**定位回当前播放段的首行，并恢复自动跟随。
 *
 * 用瞬时定位（`block: 'start'`，目标落在容器顶端）而不是平滑滚动 —— 中间隔了几十段时，
 * 平滑动画会滑很久才到（Android 端 `backToReadAloudPosition` 同理用 `jumpToPosition`）。
 */
const backToPlaybackPosition = () => {
  const row = playbackTargetRow.value
  if (row < 0) {
    message.info('还没有开始播放')
    return
  }
  userScrolledAway.value = false // 恢复自动跟随
  const el = itemRefs.value[row]
  if (el) {
    programmaticScrollUntil.value = Date.now() + 300
    el.scrollIntoView({ behavior: 'auto', block: 'start' })
  }
  updateBackToPlayVisibility()
}

/**
 * 上一段 / 下一段：显式跳段 ⇒ 恢复自动跟随（画面要跟着走）。
 *
 * 与 Android 端「有声书跳段前必须复位 `audioUserScrolledAway`」同一语义：
 * 用户主动要求换段，就意味着他要跟着这段听。
 */
const onPrevSegment = () => {
  userScrolledAway.value = false
  prevSegment()
}
const onNextSegment = () => {
  userScrolledAway.value = false
  nextSegment()
}

/** 标记"用户主动滚走了"；没在播放时无所谓，不置位 */
const markUserScrolledAway = () => {
  if (playbackTargetRow.value < 0) return
  userScrolledAway.value = true
}

/** 滚轮：动一下就是真滚动，直接置位 */
const onWheelScroll = () => markUserScrolledAway()

/** 触摸：按下时记起点，移动超过阈值才算翻页（点一下选词不算） */
const onTouchStart = (e: TouchEvent) => {
  touchStartY = e.touches[0]?.clientY ?? 0
}
const onTouchMove = (e: TouchEvent) => {
  const y = e.touches[0]?.clientY ?? touchStartY
  if (Math.abs(y - touchStartY) > 8) markUserScrolledAway()
}

/** 指针按下：拖动滚动条不会触发 wheel，靠"按下后发生滚动"来兜底 */
const onPointerDown = () => {
  pointerScrollCandidate.value = true
}

/**
 * 纯阅读（无音频）时的「读到哪」：滚动停稳后把**视口首行**换算成段号并记进度。
 *
 * 为什么必须有它：进度原先只在**播放**时更新（引擎的 onSegmentChange），
 * 于是「没有音频的书」—— 也就是纯阅读 —— 永远记不住位置：用户读到哪里、
 * 换个终端打开，都只能从头开始。
 *
 * 有音频时位置由播放段决定（更准），这里不覆盖。
 */
let readingPositionTimer: number | undefined

const scheduleReadingPositionSave = () => {
  if (readingPositionTimer !== undefined) window.clearTimeout(readingPositionTimer)
  // 800ms：滚动停下后再记，避免滚轮连发时狂写服务端
  readingPositionTimer = window.setTimeout(saveReadingPosition, 800)
}

const saveReadingPosition = () => {
  readingPositionTimer = undefined
  const id = bookId.value
  if (id == null || hasAudio.value) return
  const p = firstVisibleParagraph()
  if (p < 0) return
  // 段号 = 段落下标 + 1（与服务端 /content 的 paragraphs 下标同源）
  activeIndex.value = p
  storeProgress.save(id, p + 1, 0)
}

/**
 * 视口顶部可见的段落下标（0 起）；取不到返回 -1。
 *
 * 靠模板上的 ``data-p``（= 段落下标）反查：它是**唯一**能同时覆盖
 * 「有原文行」（一行的首段）与「无原文行」（渲染的就是 paragraphs 本身）两种分支的标记。
 * 取视口内第一个仍然可见的片段 ⇒ 它所属的段落下标就是用户读到的位置。
 */
const firstVisibleParagraph = (): number => {
  const el = bodyRef.value
  if (!el) return -1
  const top = el.getBoundingClientRect().top
  const parts = Array.from(el.querySelectorAll<HTMLElement>('[data-p]'))
  for (const part of parts) {
    const r = part.getBoundingClientRect()
    if (r.bottom > top + 1) {
      const idx = Number(part.dataset.p)
      return Number.isFinite(idx) ? idx : -1
    }
  }
  return -1
}

/**
 * 视口滚动（含容器内部滚动与页面滚动）：重新判定按钮显隐 + 记录阅读位置。
 *
 * 只有**非程序性**的滚动才置"用户滚走" —— 我们自己 scrollIntoView 引起的滚动
 * 落在抑制窗口内，不置位，否则自动跟随会被自己打断。
 */
const onViewportScroll = () => {
  updateBackToPlayVisibility()
  if (Date.now() < programmaticScrollUntil.value) return
  if (pointerScrollCandidate.value) {
    pointerScrollCandidate.value = false
    markUserScrolledAway()
  }
  scheduleReadingPositionSave()
}

/** 视口高度变化：可见范围变了，重新判定「回到播放处」的显隐 */
const onViewportResize = () => updateBackToPlayVisibility()

/** 键盘翻页（PageUp/PageDown/方向键/Home/End/空格）同样算用户主动滚动 */
const onKeyDown = (e: KeyboardEvent) => {
  const keys = ['PageUp', 'PageDown', 'ArrowUp', 'ArrowDown', 'Home', 'End', ' ', 'Spacebar']
  if (!keys.includes(e.key)) return
  const t = e.target as HTMLElement | null
  // 输入框/下拉里的按键归它们自己，别把打字当成翻页
  if (t && ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName)) return
  markUserScrolledAway()
}

// 正文容器是 v-if 渲染的（四个分支共用同一个 ref），所以绑定跟着容器走。
// flush: 'post' —— 模板 ref 在 patch 里赋值，pre-flush 的 watcher 可能拿到旧值（null）。
watch(
  bodyRef,
  (el, old) => {
    old?.removeEventListener('scroll', onViewportScroll)
    old?.removeEventListener('wheel', onWheelScroll)
    old?.removeEventListener('touchstart', onTouchStart)
    old?.removeEventListener('touchmove', onTouchMove)
    old?.removeEventListener('pointerdown', onPointerDown)
    if (!el) return
    el.addEventListener('scroll', onViewportScroll, { passive: true })
    el.addEventListener('wheel', onWheelScroll, { passive: true })
    el.addEventListener('touchstart', onTouchStart, { passive: true })
    el.addEventListener('touchmove', onTouchMove, { passive: true })
    el.addEventListener('pointerdown', onPointerDown, { passive: true })
  },
  { immediate: true, flush: 'post' },
)

// 页面级滚动（移动端正文容器不滚动时）与键盘翻页监听挂在 onMounted / onUnmounted 上。

// 高亮项变化即滚入可视区。统一监听 scrollTargetIndex 而不是分别监听 currentIndex /
// activeIndex：后者在「有音频但还没开始播」时（currentIndex 恒 -1、高亮走 activeIndex）
// 会漏掉滚动，且两条 watch 的互斥判断与 highlightIndex 的回退逻辑重复、易漂移。
// 有原文行时滚的是「该段的第一行」，所以监听的是转换后的显示单位下标，不是段落下标本身。
//
// ⚠ 用户滚走后不再跟随（`userScrolledAway`）—— 正在看的内容与正在播的内容不互相绑架；
// 点「回到播放处」才跳回并恢复跟随。
watch(scrollTargetIndex, (i) => {
  if (i >= 0 && !userScrolledAway.value) followRow(i)
  updateBackToPlayVisibility()
})

// ---------- 长按选词（对照 Android 长按菜单：复制 / 收藏 / 翻译 / 查字典 / 从此处读） ----------

/**
 * 把 DOM 节点解析成**段落下标**（= 段号 - 1，播放/收藏/朗读都用这个口径）。
 *
 * 优先级：`.row-part[data-p]`（服务端切出的句内片段，最精确）→ 行下标换算
 * → 旧口径的 `.para` 下标。`.para-no`（段号角标）与 `.para-tag` 不是正文，
 * 由 useTextSelection 先行排除，这里不再重复判断。
 */
const resolveParagraphOfNode = (node: Node): number => {
  const el =
    node.nodeType === Node.TEXT_NODE ? node.parentElement : (node as HTMLElement | null)
  if (!el) return -1
  const part = el.closest<HTMLElement>('.row-part')
  if (part) {
    const p = Number(part.dataset.p)
    if (Number.isInteger(p)) return p
  }
  const row = el.closest<HTMLElement>('.row')
  if (row?.parentElement) {
    const idx = Array.prototype.indexOf.call(row.parentElement.children, row)
    if (idx >= 0) return paragraphOfRow(idx)
  }
  const para = el.closest<HTMLElement>('.para')
  if (para?.parentElement) {
    const idx = Array.prototype.indexOf.call(para.parentElement.children, para)
    if (idx >= 0) {
      // 旧口径（服务端没给原文行）有两套 `.para` 渲染：有音频时渲染 segments，
      // 无音频时渲染 paragraphs。两者的「段落下标」换算方式不同，不能一律用渲染下标。
      if (hasAudio.value) {
        const seg = segments.value[idx]
        if (seg) return seg.segmentNumber - 1
      } else {
        const plain = paragraphs.value[idx]
        if (plain) return plain.paragraphIndex
      }
      return idx
    }
  }
  return -1
}

const { selection, clear: clearSelection } = useTextSelection({
  container: bodyRef,
  resolveParagraph: resolveParagraphOfNode,
})

/**
 * 菜单项按选区内容动态增减 —— 与 Android `onPrepareActionMode` 同一套规则：
 *  - 「翻译」只在选区是英文词时出现（走 ECDICT 英汉词典，中文必然查空）；
 *  - 「查字典」只在选区恰好一个汉字时出现（多字必失败，是「菜单项存在但一定点不通」；
 *    拖动手柄改变选区后也必须跟着消失）；
 *  - 「复制」「收藏」「从此处读」恒定出现。
 */
const menuItems = computed<Array<{ key: TextActionKey; label: string }>>(() => {
  const info = selection.value
  if (!info) return []
  const items: Array<{ key: TextActionKey; label: string }> = [
    { key: 'copy', label: '复制' },
    { key: 'favorite', label: '收藏' },
  ]
  if (isEnglishWord(info.text)) items.push({ key: 'translate', label: '翻译' })
  if (singleCjkChar(info.text)) items.push({ key: 'dict', label: '查字典' })
  items.push({ key: 'readFromHere', label: '从此处读' })
  return items
})

/**
 * 复制到剪贴板。
 *
 * `navigator.clipboard` 只在**安全上下文**（https / localhost）可用：局域网 http 访问
 * （如 api.example.com 直连）时它是 undefined，必须退回 textarea + execCommand，
 * 否则「复制」在非 https 部署下会静默失效。
 */
const copySelectionText = async (text: string) => {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.setAttribute('readonly', '')
      ta.style.position = 'fixed'
      ta.style.top = '-9999px'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      document.body.removeChild(ta)
      if (!ok) throw new Error('execCommand copy failed')
    }
    message.success('已复制到剪贴板')
  } catch {
    message.error('复制失败，请长按手动选择后复制')
  }
}

/**
 * 收藏选中文字。
 *
 * 唯一身份 = 书名 + 段号（与 Android、服务端同口径）；所以先要把「选中的是哪一段」
 * 定位出来 —— 定位不到（选区落在段号角标、句间空白）时明确提示，别静默存到第 1 段。
 */
const favoriteSelection = async (info: TextSelectionInfo) => {
  const p = info.paragraphIndex
  if (p < 0) {
    message.warning('无法定位到具体段落，请重新选择后再收藏')
    return
  }
  if (!bookTitle.value) {
    message.warning('书名未知，无法收藏')
    return
  }
  const added = await favorites.add(bookTitle.value, p + 1, info.text, taskId.value)
  if (added) message.success(`已收藏到第 ${p + 1} 段`)
  else message.info('这一段已经收藏过了')
}

// ----- 词典面板 -----

const dictOpen = ref(false)
const dictMode = ref<'en' | 'zh'>('en')
const dictLoading = ref(false)
const dictError = ref('')
const dictAvailable = ref(true)
const dictWord = ref('')
const enEntries = ref<DictEntry[]>([])
const zhEntries = ref<ChineseEntry[]>([])

/** 把 axios 错误翻成中文：复用的是项目里「不暴露底层英文原文」的一贯口径 */
const dictErrorText = (e: unknown): string => {
  const err = e as {
    response?: { data?: { message?: string } }
    message?: string
  }
  const msg = err?.response?.data?.message
  if (msg) return msg
  if (err?.message?.includes('timeout')) return '查询超时，请稍后重试'
  if (!err?.response) return '网络异常，请检查连接'
  return '查询失败，请稍后重试'
}

const openEnglishDict = async (raw: string) => {
  dictMode.value = 'en'
  dictWord.value = raw
  enEntries.value = []
  dictError.value = ''
  dictOpen.value = true
  dictLoading.value = true
  try {
    const res = await dictApi.lookup(raw)
    dictAvailable.value = res.data.available !== false
    enEntries.value = res.data.entries || []
  } catch (e) {
    dictError.value = dictErrorText(e)
  } finally {
    dictLoading.value = false
  }
}

const openChineseDict = async (ch: string) => {
  dictMode.value = 'zh'
  dictWord.value = ch
  zhEntries.value = []
  dictError.value = ''
  dictOpen.value = true
  dictLoading.value = true
  try {
    const res = await dictApi.lookupChar(ch)
    dictAvailable.value = res.data.available !== false
    zhEntries.value = res.data.entries || []
  } catch (e) {
    dictError.value = dictErrorText(e)
  } finally {
    dictLoading.value = false
  }
}

// ----- 从此处读 -----

const speechSupported = typeof window !== 'undefined' && 'speechSynthesis' in window
/** 是否正在用浏览器 TTS 朗读（无音频的书才有这条路径） */
const speaking = ref(false)

const stopSpeaking = () => {
  if (!speechSupported) return
  window.speechSynthesis.cancel()
  speaking.value = false
}

/** 取第 p 段的纯文本（原文段落优先，其次音频片段文本） */
const paragraphTextOf = (p: number): string => {
  if (p < 0) return ''
  const plain = paragraphs.value.find((x) => x.paragraphIndex === p)
  if (plain?.text) return plain.text
  const idx = segmentIndexOfParagraph(p)
  if (idx >= 0) return segments.value[idx]?.textContent ?? ''
  return ''
}

/**
 * 「从此处读」：
 *  - 有合成音频 ⇒ 从选中句起播合成音频（与 Android `viewModel.playLine` 同一语义）；
 *  - 无合成音频 ⇒ 用浏览器自带 TTS 从选中处念（对应 Android 的系统 TTS `readFromHere`）。
 *    再点一次即停止 —— 浏览器 TTS 没有「进度条」可停，必须留一个显式出口。
 */
const readFromHere = (info: TextSelectionInfo) => {
  if (hasAudio.value) {
    if (info.paragraphIndex < 0) {
      message.info('无法定位到具体段落，请重新选择')
      return
    }
    stopSpeaking()
    playParagraph(info.paragraphIndex)
    return
  }
  if (!speechSupported) {
    message.info('当前浏览器不支持语音朗读')
    return
  }
  if (speaking.value) {
    stopSpeaking()
    message.info('已停止朗读')
    return
  }
  const full = paragraphTextOf(info.paragraphIndex)
  // 从选中处起念：段落文本里找到选区起点，取它到段尾；找不到就整段念（宁可多念一句）
  let text = full || info.text
  if (full) {
    const head = info.displayText.slice(0, 8)
    const idx = head ? full.indexOf(head) : -1
    if (idx > 0) text = full.slice(idx)
  }
  const utter = new SpeechSynthesisUtterance(text)
  utter.lang = /[\u4e00-\u9fff]/.test(text) ? 'zh-CN' : 'en-US'
  utter.onend = () => {
    speaking.value = false
  }
  utter.onerror = () => {
    speaking.value = false
  }
  speaking.value = true
  window.speechSynthesis.speak(utter)
}

// ----- 单词本：收藏出处快照 + 词典面板收藏态 + 跳词定位 -----

/**
 * 组装收藏出处（6 键，全部可空）。
 *
 * 由 onMenuAction 在打开词典**之前**调用并快照到 dictSource —— 那里 info 还在，
 * 而 onMenuAction 随后会 clearSelection()，等用户在 DictPanel 点「加入单词本」时选区已没了。
 *
 * bookKey 恒为 `s:{bookId}`（Web 只有书城书，与 storeProgress 的书口径一致）；
 * storeBookId 是 Web 端唯一能用来打开书的键（本地书为 null ⇒ 单词本里跳转禁用）。
 * sentenceText 是定位第一判据：优先取段落首行原文（能在 rows 里精确匹配），
 * 再退整段文本、最后退选区文本；服务端限制 20000 字，这里自行截断到 2000 更稳。
 */
function buildWordSource(info: TextSelectionInfo): WordBookSource {
  const id = bookId.value
  const p = info.paragraphIndex
  const firstRow = p >= 0 ? firstRowOfParagraph(p) : -1
  const rowText = firstRow >= 0 ? (rows.value[firstRow] ?? '').trim() : ''
  const sentence = (rowText || paragraphTextOf(p) || info.text || '').trim().slice(0, 2000)
  return {
    bookName: queryName.value || bookTitle.value || null,
    bookKey: id != null ? `s:${id}` : null,
    storeBookId: id,
    segmentNumber: p >= 0 ? p + 1 : null,
    lineNumber: firstRow >= 0 ? firstRow + 1 : null,
    sentenceText: sentence || null,
  }
}

/** 打开词典时快照的收藏出处（clearSelection 之后仍可用） */
const dictSource = ref<WordBookSource | null>(null)

/** 收藏态：has() 读的是模块级非响应式 cache，显式依赖 wordBookVersion 触发重算 */
const dictFavorited = computed(() => {
  void wordBookVersion.value
  const sw = stripWord(dictWord.value)
  return !!sw && wordBook.has(sw)
})

/**
 * DictPanel 的「加入/移出单词本」事件。
 *
 * 词条快照：英汉取第一条非空音标 + 所有词形释义；中文单字取全部拼音 + 全部释义
 * （translations 传 string[]，由 wordBook.add 统一 join('\n') 成 wire 的 LF 串）。
 * 出处取 dictSource（异常兜底给书级信息，段号/句子可为 null，绝不臆造）。
 */
const onDictFavorite = async () => {
  const sw = stripWord(dictWord.value)
  if (!sw) {
    message.warning('无法收藏该词')
    return
  }
  if (wordBook.has(sw)) {
    const item = wordBook.find(sw)
    if (item) await wordBook.remove(item)
    message.success('已移出单词本')
    return
  }
  let phonetic = ''
  const translations: string[] = []
  if (dictMode.value === 'en') {
    phonetic = enEntries.value.find((e) => !!e.phonetic)?.phonetic ?? ''
    for (const e of enEntries.value) translations.push(...e.translations)
  } else {
    phonetic = Array.from(new Set(zhEntries.value.map((e) => e.pinyin).filter(Boolean))).join(' / ')
    for (const e of zhEntries.value) translations.push(...e.definitions)
  }
  const id = bookId.value
  const source: WordBookSource = dictSource.value ?? {
    bookName: queryName.value || bookTitle.value || null,
    bookKey: id != null ? `s:${id}` : null,
    storeBookId: id,
    segmentNumber: null,
    lineNumber: null,
    sentenceText: null,
  }
  await wordBook.add({ word: dictWord.value, phonetic, translations, ...source })
  message.success('已加入单词本')
}

/**
 * 单词本「跳转到原书」定位（由 loadData 末尾 / query watch 触发）。
 *
 * 判据优先级（契约）：**句子原文 > 段号**。段号/行号会随切分粒度、版本漂移，
 * 收藏时的句子原文才是用户真正看到的那一行；先在已加载 rows 里按内容找，找不到再退段号。
 * 两者都定位不到就保持默认起点，不打扰用户，绝不白屏。
 *
 * 用 setTimeout(0)：loadData 完成 → nextTick 后 DOM 已更新，但 itemRefs 的 :ref 回调
 * 与布局稳定可能还要一帧（参照 jumpToToc 的写法），延一拍再 scrollIntoView 更稳。
 */
function applyWordJump() {
  if (!wordJump.value) return
  const sentence = jumpSentence.value
  const segment = jumpSegment.value
  let target = -1
  // ① 句子原文优先：rows 单行与句子互相包含即命中（t.length 下限防短行误命中）
  if (sentence && rows.value.length) {
    target = rows.value.findIndex((r) => {
      const t = r.trim()
      return !!t && (t.includes(sentence) || (t.length >= 4 && sentence.includes(t)))
    })
  }
  // ② 段号兜底：1-based 段号 → 段落下标 → 该段首行
  if (target < 0 && segment != null) {
    target = usingRows.value ? firstRowOfParagraph(segment - 1) : segment - 1
  }
  if (target < 0) return
  // 高亮该行所在段（有音频但未播放时 highlightIndex 走 activeIndex）
  const p = usingRows.value
    ? (rowParaStart.value[target] ?? (segment != null ? segment - 1 : -1))
    : target
  if (p >= 0) activeIndex.value = p
  userScrolledAway.value = false // 显式跳转 ⇒ 恢复自动跟随
  window.setTimeout(() => scrollToItem(target), 0)
}

/** 菜单点击分发：先收菜单（选区已无用处），再按 action 执行 */
const onMenuAction = (key: TextActionKey) => {
  const info = selection.value
  if (!info) return
  clearSelection()
  switch (key) {
    case 'copy':
      void copySelectionText(info.text)
      break
    case 'favorite':
      void favoriteSelection(info)
      break
    case 'translate':
      // 快照出处：clearSelection() 已执行，等 DictPanel 收藏时选区已没了
      dictSource.value = buildWordSource(info)
      void openEnglishDict(info.text)
      break
    case 'dict': {
      const ch = singleCjkChar(info.text)
      if (ch) {
        dictSource.value = buildWordSource(info)
        void openChineseDict(ch)
      } else message.info('请只选中一个字')
      break
    }
    case 'readFromHere':
      readFromHere(info)
      break
  }
}

// ---------- 阅读字号（复用全局设置，与 Player.vue 同一套 --reader-font-size） ----------
const FONT_SIZES = [14, 16, 18, 20, 22, 24, 28]
const onFontSizeChange = (v: unknown) => {
  const n = Number(v)
  if (Number.isFinite(n)) settingsStore.setReaderFontSize(n)
}

// ---------- 睡眠定时（有音频时才有意义） ----------
const SLEEP_OPTIONS = [
  { value: 'off', label: '关闭' },
  { value: 'end', label: '播完本段' },
  { value: '3', label: '3 段后' },
  { value: '5', label: '5 段后' },
  { value: '10', label: '10 段后' },
  { value: '15', label: '15 分钟后' },
  { value: '30', label: '30 分钟后' },
  { value: '60', label: '60 分钟后' },
]
const sleepSelectValue = ref('off')

const sleepLabel = computed(() => {
  const mode = sleepTimerMode.value
  if (mode === SLEEP_TIMER_OFF) return '未开启'
  if (mode === SLEEP_TIMER_END_OF_SEGMENT) return '播完本段后暂停'
  if (mode === SLEEP_TIMER_SEGMENTS) return `还剩 ${sleepTimerSegmentsLeft.value} 段`
  const total = Math.max(0, Math.ceil(sleepTimerRemaining.value / 1000))
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')} 后暂停`
})

const onSleepChange = (value: unknown) => {
  const val = String(value)
  sleepSelectValue.value = val
  if (val === 'off') {
    setSleepTimer(SLEEP_TIMER_OFF)
    message.info('已关闭睡眠定时')
  } else if (val === 'end') {
    setSleepTimer(SLEEP_TIMER_END_OF_SEGMENT)
    message.success('将在本段播完后暂停')
  } else if (val === '3' || val === '5' || val === '10') {
    setSleepTimerSegments(Number(val))
    message.success(`将在 ${val} 段后暂停`)
  } else {
    setSleepTimer(Number(val))
    message.success(`将在 ${val} 分钟后暂停`)
  }
}

// 定时自动触发后引擎会把模式复位为关闭，选择框同步回到「关闭」
watch(sleepTimerMode, (mode) => {
  if (mode === SLEEP_TIMER_OFF) sleepSelectValue.value = 'off'
})

/** 校验路由参数并（重新）加载；参数非法跳回书城 */
function validateAndLoad() {
  if (bookId.value == null) {
    message.error('无效的书籍ID')
    router.replace('/store')
    return
  }
  // 切书时重置旧状态，避免上一本的段落/播放串台
  teardown()
  // 长按菜单/词典/浏览器朗读都属于「上一本书的上下文」，一并收掉
  clearSelection()
  stopSpeaking()
  dictOpen.value = false
  segments.value = []
  segmentToc.value = []
  paragraphs.value = []
  plainToc.value = []
  rows.value = []
  rowParaStart.value = []
  rowParaEnd.value = []
  rowParas.value = []
  itemRefs.value = []
  activeIndex.value = -1
  loadData()
}

onMounted(() => {
  validateAndLoad()
  // 拉取云端单词本：跨端同步后词典面板的「已收藏」态与单词本列表自动刷新
  wordBook.ensureLoaded()
  // 参数跳转（前进/后退、站内跳转）复用同一实例，必须监听
  watch(() => route.params.bookId, () => validateAndLoad())
  // 同一本书带新定位 query 跳进来时组件实例复用：不重新加载（loadData 已跑过），
  // 但要重新定位（例如浏览器前进后退到另一条单词的跳转）。加载中交给 loadData 末尾处理。
  watch(
    () => [route.query.wordJump, route.query.segment, route.query.sentence] as const,
    () => {
      if (wordJump.value && !loading.value) applyWordJump()
    },
  )
  // 页面级滚动 + 键盘翻页：正文容器内部滚动由容器自己的 scroll 事件负责
  window.addEventListener('scroll', onViewportScroll, { passive: true })
  window.addEventListener('keydown', onKeyDown)
  // 视口高度变了 ⇒ 可见范围变了 ⇒ 重新判定按钮显隐
  window.addEventListener('resize', onViewportResize)
})

onUnmounted(() => {
  setActivePauser(null)
  // 离开阅读页必须掐掉浏览器 TTS：它属于**页面**而不是音频引擎，
  // 不取消的话用户退出后还会继续念（Android 端退出阅读页同样要 stop）
  stopSpeaking()
  window.removeEventListener('scroll', onViewportScroll)
  window.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('resize', onViewportResize)
})
</script>

<template>
  <Card>
    <div class="reader-header">
      <Space>
        <Button :icon="h(ArrowLeftOutlined)" @click="router.back()">返回</Button>
        <Title :level="4" style="margin: 0">《{{ bookTitle }}》</Title>
        <Tag v-if="hasAudio" color="blue"><SoundOutlined /> 有声书</Tag>
        <Tag v-else>纯文本</Tag>
      </Space>
      <Space :wrap="true">
        <Button
          :icon="h(UnorderedListOutlined)"
          :disabled="(hasAudio ? segmentToc.length : plainToc.length) === 0"
          @click="tocVisible = true"
        >
          目录
        </Button>
        <Space>
          <FontSizeOutlined />
          <Select
            :value="settingsStore.readerFontSize"
            size="small"
            style="width: 82px"
            :options="FONT_SIZES.map((s) => ({ value: s, label: `${s}px` }))"
            @change="onFontSizeChange"
          />
        </Space>
      </Space>
    </div>

    <!-- W1：正文取自合成时的快照，而磁盘文件已换过版本 —— 说明音频来自旧文件。
         此时**不重排正文**（那会让音频与文字错位），只提示原因，避免用户以为是 bug。 -->
    <Alert
      v-if="staleFile"
      type="warning"
      show-icon
      style="margin-bottom: 12px"
      message="本书文件已更新，当前显示的是合成时的正文"
      description="音频是按当时那一版文件合成的，因此这里继续显示当时的文字，以保证「听到的」与「看到的」一致；按新版文件重新合成后才会显示新正文。"
    />

    <Spin :spinning="loading">
      <!-- ============ 有音频：边读边播 ============ -->
      <template v-if="hasAudio">
        <Card v-if="segments.length > 0" class="playbar" :bordered="false">
          <div class="now-playing">
            <template v-if="currentSegment">
              <Tag color="blue">第 {{ currentSegment.segmentNumber }} 段</Tag>
              <Text type="secondary">共 {{ segments.length }} 段 · 可播放 {{ completedCount }} 段</Text>
            </template>
            <Text v-else type="secondary">点击下方任意段落开始播放</Text>
          </div>

          <div class="seek-row">
            <Button size="small" :icon="h(BackwardOutlined)" :disabled="!currentSegment" title="后退10秒" @click="skip(-10)" />
            <Slider
              class="seek-slider"
              :min="0"
              :max="duration || 1"
              :step="1"
              :value="currentTime"
              :tip-formatter="(v?: number) => formatTime(v || 0)"
              :disabled="!currentSegment"
              @change="onSeek"
            />
            <Button size="small" :icon="h(ForwardOutlined)" :disabled="!currentSegment" title="前进10秒" @click="skip(10)" />
            <Text type="secondary" class="time-text">
              {{ formatTime(currentTime) }} / {{ formatTime(duration) }}
            </Text>
          </div>

          <div class="player-controls">
            <Button size="large" :icon="h(StepBackwardOutlined)" @click="onPrevSegment" />
            <Button
              type="primary"
              size="large"
              shape="circle"
              :loading="buffering"
              :icon="h(isPlaying ? PauseCircleOutlined : PlayCircleOutlined)"
              @click="togglePlay"
            />
            <Button size="large" :icon="h(StepForwardOutlined)" @click="onNextSegment" />
          </div>

          <div class="extra-controls">
            <Space>
              <Text type="secondary">倍速</Text>
              <Select
                :value="playbackRate"
                size="small"
                style="width: 84px"
                :options="RATE_OPTIONS.map((r) => ({ value: r, label: `${r}x` }))"
                @change="onRateChange"
              />
            </Space>
            <Space>
              <Text type="secondary">音量</Text>
              <Slider
                class="volume-slider"
                :min="0"
                :max="1"
                :step="0.05"
                :value="volume"
                :tip-formatter="(v?: number) => `${Math.round((v || 0) * 100)}%`"
                @change="onVolumeChange"
              />
            </Space>
            <Space>
              <Text type="secondary">睡眠定时</Text>
              <Select
                :value="sleepSelectValue"
                size="small"
                style="width: 118px"
                :options="SLEEP_OPTIONS"
                @change="onSleepChange"
              />
              <Text type="secondary" class="sleep-status">{{ sleepLabel }}</Text>
            </Space>
          </div>
        </Card>

        <!-- 正文：显示**原文行**（保留行首缩进），悬停/点击定位到鼠标所在句子，从该句起播 -->
        <div v-if="usingRows" ref="bodyRef" class="reader-body">
          <p
            v-for="(_, i) in rows"
            :key="i"
            :ref="(el) => setItemRef(el as Element | null, i)"
            class="row"
            :class="{ 'para-head': isParagraphHead(i) }"
            @click="onRowClick(i, $event)"
            @mousemove="onRowMousemove"
            @mouseleave="hoveredParagraph = -1"
          ><span v-if="hasAudio && isParagraphHead(i)" class="para-no">{{ paragraphOfRow(i) + 1 }}</span><template
              v-for="(seg) in rowSegments(i)"
              :key="i + '-' + seg.start + '-' + seg.end"
            ><span
              class="row-part"
              :data-p="seg.paragraphIndex"
              :class="{
                active: seg.paragraphIndex === highlightIndex && isRowActive(i),
                hover: seg.paragraphIndex === hoveredParagraph && isRowHovered(i),
              }"
            >{{ seg.text }}</span></template><Tag v-if="rowStatus(i)" :color="rowStatus(i)!.color" class="para-tag">
              {{ rowStatus(i)!.text }}
            </Tag>
          </p>
        </div>

        <!-- 老口径兜底：服务端没给原文行（如老服务端），仍渲染片段 -->
        <div v-else-if="segments.length > 0" ref="bodyRef" class="reader-body">
          <p
            v-for="(seg, i) in segments"
            :key="seg.id"
            :ref="(el) => setItemRef(el as Element | null, i)"
            class="para"
            :class="{ active: i === highlightIndex }"
            @click="onParagraphClick(i)"
          >
            <span class="para-no">{{ seg.segmentNumber }}</span>{{ seg.textContent }}
            <Tag v-if="seg.status !== 'completed'" :color="statusMeta(seg.status).color" class="para-tag">
              {{ statusMeta(seg.status).text }}
            </Tag>
          </p>
        </div>
        <Empty v-else-if="!loading" description="该书籍暂无正文内容" />
      </template>

      <!-- ============ 无音频：纯文本阅读 ============ -->
      <template v-else>
        <div v-if="usingRows" ref="bodyRef" class="reader-body">
          <p
            v-for="(_, i) in rows"
            :key="i"
            :ref="(el) => setItemRef(el as Element | null, i)"
            class="row"
            :class="{ 'para-head': isParagraphHead(i) }"
            @click="onRowClick(i, $event)"
            @mousemove="onRowMousemove"
            @mouseleave="hoveredParagraph = -1"
          ><template
              v-for="(seg) in rowSegments(i)"
              :key="i + '-' + seg.start + '-' + seg.end"
            ><span
              class="row-part"
              :data-p="seg.paragraphIndex"
              :class="{
                active: seg.paragraphIndex === highlightIndex && isRowActive(i),
                hover: seg.paragraphIndex === hoveredParagraph && isRowHovered(i),
              }"
            >{{ seg.text }}</span></template>
          </p>
        </div>
        <div v-else-if="paragraphs.length > 0" ref="bodyRef" class="reader-body">
          <p
            v-for="(p, i) in paragraphs"
            :key="p.paragraphIndex"
            :ref="(el) => setItemRef(el as Element | null, i)"
            class="para"
            :class="{ active: i === highlightIndex }"
            @click="onParagraphClick(i)"
          >
            {{ p.text }}
          </p>
        </div>
        <Empty v-else-if="!loading" description="本书暂不支持在线阅读（文件缺失或格式无法解析）" />
      </template>
    </Spin>

    <!-- 目录弹窗：两条路径统一到段落下标（%= 段号 - 1）后跳转 -->
    <Modal v-model:open="tocVisible" title="目录" :footer="null" width="440px">
      <List
        size="small"
        bordered
        :data-source="hasAudio ? segmentToc : plainToc"
        :locale="{ emptyText: '暂无目录' }"
      >
        <template #renderItem="{ item }">
          <List.Item
            class="toc-item"
            @click="jumpToToc(hasAudio ? item.segmentNumber - 1 : item.paragraphIndex)"
          >
            {{ item.title }}
            <Text v-if="item.pageNumber && item.pageNumber > 0" type="secondary" style="margin-left: 8px">
              第 {{ item.pageNumber }} 页
            </Text>
          </List.Item>
        </template>
      </List>
    </Modal>

    <!-- 长按选词浮动菜单：复制 / 收藏 / 翻译 / 查字典 / 从此处读（后两项按选区内容动态出现） -->
    <TextActionMenu
      :open="!!selection"
      :anchor="selection?.rect ?? null"
      :items="menuItems"
      @action="onMenuAction"
    />

    <!-- 词典释义：英汉词条与中文单字共用一个底部卡片 -->
    <DictPanel
      :open="dictOpen"
      :mode="dictMode"
      :loading="dictLoading"
      :error="dictError"
      :word="dictWord"
      :available="dictAvailable"
      :en="enEntries"
      :zh="zhEntries"
      :favorited="dictFavorited"
      @favorite="onDictFavorite"
      @close="dictOpen = false"
    />
    <!-- 「回到播放处」：只有播放位置被用户滚出视口时才出现。
         点它把正文瞬时定位回当前播放段的首行，并恢复自动跟随（对照 Android 的 btnBackToReadPosition）。 -->
    <div v-if="showBackToPlay" class="back-to-play">
      <Button type="primary" shape="round" :icon="h(AimOutlined)" @click="backToPlaybackPosition">
        回到播放处
      </Button>
    </div>
  </Card>
</template>

<style scoped>
.reader-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 8px;
}

.playbar {
  background: #fafafa;
  margin-bottom: 16px;
  position: sticky;
  top: 0;
  z-index: 10;
}

.now-playing {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.seek-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.seek-slider {
  flex: 1;
  margin: 0 4px;
}

.time-text {
  min-width: 96px;
  text-align: right;
  font-size: 12px;
  white-space: nowrap;
}

.player-controls {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  margin-top: 8px;
}

.extra-controls {
  display: flex;
  justify-content: center;
  gap: 32px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.volume-slider {
  width: 120px;
  margin: 0;
}

.sleep-status {
  min-width: 96px;
  font-size: 12px;
  white-space: nowrap;
}

/* 正文区：阅读主体，字号跟随全局 --reader-font-size（与 Player.vue 同源设置） */
.reader-body {
  max-height: 68vh;
  overflow-y: auto;
  padding: 4px 8px;
  /*
   * 长按选词的前提：正文必须可选中。
   * - `user-select: text`：某些全局样式会给容器加 none（防拖拽误选），那样长按永远选不中；
   * - `-webkit-touch-callout: none`：关掉 iOS 长按的「拷贝/查询」系统气泡，
   *   否则会和我们的浮动菜单同时出现两套（Android 端对应的是禁用系统 ActionMode 的智能项）。
   */
  -webkit-user-select: text;
  user-select: text;
  -webkit-touch-callout: none;
}

/* 选区底色：默认蓝在深色正文上偏亮，这里统一成主题蓝的浅淡版本 */
.reader-body ::selection {
  background: rgba(22, 119, 255, 0.28);
}

.para {
  font-size: var(--reader-font-size, 18px);
  line-height: 2;
  margin: 0 0 6px;
  padding: 6px 10px;
  border-radius: 6px;
  color: rgba(0, 0, 0, 0.85);
  cursor: pointer;
  transition: background 0.15s;
  text-indent: 2em;
}

/*
 * 原文行：PDF/EPUB 的行本身已带缩进（`white-space: pre-wrap` 原样保留），
 * 因此**不能**再加 `text-indent` —— 那会把每行都推一格，排版比原文还乱。
 * 段与段的间距改由 `.para-head`（一段的第一行）给上边距，视觉上仍是「一段一块」。
 */
.row {
  font-size: var(--reader-font-size, 18px);
  line-height: 1.9;
  margin: 0;
  padding: 2px 10px;
  border-radius: 6px;
  color: rgba(0, 0, 0, 0.85);
  cursor: pointer;
  transition: background 0.15s;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* 每段的第一行：与上一段拉开距离，读起来才有分段感 */
.row.para-head {
  margin-top: 0.85em;
}

/*
 * 悬停/播放高亮按「段」铺在 .row-part 上：某句占满的行整段蓝底（整行铺满），
 * 而某句从行中间开始时，第一行蓝底只从该句真实起点起（不再盖到上一句残字）。
 * 整行的 :hover 不再铺满（否则与 .row-part.hover 冲突、又误导成整行）。
 */
.row:hover {
  /* 反馈统一由 .row-part.hover 提供，这里不再铺整行底色 */
}

.row-part {
  border-radius: 3px;
  transition: background 0.15s;
}

/* 悬停预览：比播放更淡，提示「点了会播这一整句」 */
.row-part.hover {
  background: #f0f7ff;
}

/* 当前播放/选中的句子：底色更实，作为阅读定位 */
.row-part.active {
  background: #e6f4ff;
}

.para:hover {
  background: rgba(0, 0, 0, 0.03);
}

/* 当前段：用左侧色条 + 底色双重标识，避免只靠底色在护眼/夜间下不明显 */
.para.active {
  background: #e6f4ff;
  border-left: 3px solid #1677ff;
  padding-left: 7px;
}

.para-no {
  display: inline-block;
  min-width: 30px;
  margin-right: 6px;
  color: #bbb;
  font-size: 0.72em;
  text-indent: 0;
}

.para-tag {
  margin-left: 8px;
  font-size: 0.7em;
}

.toc-item {
  cursor: pointer;
}

.toc-item:hover {
  background: #fafafa;
}

/* 移动端：控制区换行、进度条独占一行、加大触控目标 */
@media (max-width: 768px) {
  .seek-row {
    flex-wrap: wrap;
  }

  .seek-slider {
    flex-basis: 100%;
    order: -1;
    margin: 0 0 8px;
  }

  .player-controls .ant-btn {
    min-width: 48px;
    min-height: 44px;
  }

  .extra-controls {
    gap: 16px;
  }

  .reader-body {
    max-height: none;
  }

  /* 移动端按钮贴近屏幕边缘，避免挡住正文 */
  .back-to-play {
    right: 14px;
    bottom: 18px;
  }
}

/* 夜间模式：浅色面板翻转为深色，正文文字调亮 */
.dark .playbar {
  background: #1f1f1f;
}

.dark .para {
  color: rgba(255, 255, 255, 0.85);
}

.dark .row {
  color: rgba(255, 255, 255, 0.85);
}

.dark .row:hover {
  /* 反馈统一由 .row-part.hover 提供 */
}

.dark .row-part.hover {
  background: #14253a;
}

.dark .row-part.active {
  background: #111d2c;
}

.dark .para:hover {
  background: rgba(255, 255, 255, 0.06);
}

.dark .para.active {
  background: #111d2c;
}

.dark .para-no {
  color: rgba(255, 255, 255, 0.35);
}

.dark .toc-item:hover {
  background: #262626;
}

/* 「回到播放处」：右下角浮动。
   fixed 相对视口而非正文容器 —— 桌面端正文容器自己滚动，按钮若挂在容器里会跟着一起滚走。 */
.back-to-play {
  position: fixed;
  right: 28px;
  bottom: 28px;
  z-index: 30;
}
</style>
