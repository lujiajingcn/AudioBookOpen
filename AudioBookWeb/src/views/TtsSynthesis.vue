<script setup lang="ts">
import { ref, computed, watch, onMounted, h } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Upload,
  Button,
  message,
  Card,
  Typography,
  Space,
  Progress,
  Alert,
  Radio,
  Select,
  Checkbox,
  Tag,
  Modal,
  type UploadProps,
  type UploadFile,
} from 'ant-design-vue'
import {
  UploadOutlined,
  AudioOutlined,
  SyncOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons-vue'
import { ttsApi, type UserReferenceAudio, type ServerReferenceAudio, type VoiceOption } from '@/services/api'
import { formatFileSize, formatDuration } from '@audiobook/shared'
import { useConfigStore } from '@/stores/config'
import { useCreditsStore } from '@/stores/credits'
import { useAudioPreview } from '@/composables/useAudioPreview'

/**
 * 从 antd 上传组件给的「可能是裸 File、也可能是 UploadFile 包装」的值里取出真正的 File。
 *
 * `beforeUpload` 回调收到的是 `RcFile`（本身就是 File，`originFileObj` 为 undefined）；
 * 走 `@change` 事件链路时拿到的才是 `UploadFile`（`originFileObj` 有值）。
 * 两种形态都可能在同一处出现，必须都兼容 —— 这与 AdminBooks.vue / AdminReference.vue
 * 的 `originFileObj ?? picked` 写法同源。
 */
const resolveRawFile = (picked: File | { originFileObj?: File } | null): File | null => {
  if (!picked) return null
  const wrapped = (picked as { originFileObj?: File }).originFileObj
  return wrapped ?? (picked as File)
}

/**
 * 把File 转成 `<Upload :file-list>` 需要的 UploadFile 形状，**仅用于列表展示**。
 *
 * 组件全程用 `before-upload`（returnfalse，从不真正上传），
 * 所以 antd 只会读 name / size / status 这几个展示字段。
 * 真正提交时用的是 [resolveRawFile] 拿到的**原始 File**，不是这里的包装结果
 * —— 所以这里绝不能把包装对象当成待上传的文件（那会是 0 字节的空文件）。
 */
const toUploadFile = (f: File): UploadFile =>
  ({
    uid: f.name,
    name: f.name,
    size: f.size,
    type: f.type,
    status: 'done',
    originFileObj: f,
    // antd 4.x 的 FileType 还带这些遗留字段，运行时用不到但类型要求存在
    lastModified: f.lastModified,
    lastModifiedDate: new Date(f.lastModified),
  }) as unknown as UploadFile

const { Title, Text } = Typography
const route = useRoute()
const router = useRouter()

const configStore = useConfigStore()

// 选中的电子书 / 参考音频。
// ⚠️ 类型用 `File` 而非 `UploadFile`：这两个值来自 `beforeUpload(file: RcFile)`，
// 而 `RcFile` **就是裸File**（`node_modules/ant-design-vue/es/vc-upload/interface.d.ts:38`），
// 它没有 `originFileObj` 属性 —— 那是 `file2Obj` 仅在 `@change` 事件链路上才加的包装。
// 原先声明成 `UploadFile` 再读 `.originFileObj` ⇒ 恒为 `undefined`，
// `formData.append('book_file', undefined)` 被字符串化成 "undefined"，
// 服务端 `@RequestParam("book_file") MultipartFile` 绑定到字面量 ⇒ 合成必然失败。
const bookFile = ref<File | null>(null)
const audioFile = ref<File | null>(null)
const uploading = ref(false)
const progress = ref(0)
const taskId = ref<number | null>(null)
const totalSegments = ref<number | null>(null)
const estimateSeconds = ref<number | null>(null)

// 参考音频来源：上传新文件 / 从我的库选择 / 从服务器库选择
const refMode = ref<'upload' | 'library' | 'server'>('upload')
const libraryList = ref<UserReferenceAudio[]>([])
const selectedRefId = ref<number | null>(null)
const saveToLibrary = ref(true)
const serverList = ref<ServerReferenceAudio[]>([])
const selectedServerId = ref<number | null>(null)
const serverLoading = ref(false)
const guideVisible = ref(false)
const libraryLoading = ref(false)

// 可选预设音色清单（默认音色作用于整本；逐段/逐角色绑定在听书页完成）
const voiceOptions = ref<VoiceOption[]>([])
const defaultVoiceId = ref<string | null>(null)
const voiceLoading = ref(false)

const voiceSelectOptions = computed(() =>
  voiceOptions.value.map((v) => ({ value: v.id, label: v.name })),
)

// 选了预设默认音色时，参考音频不是必填项（可用预置音色直接合成）
const hasVoice = computed(() => !!defaultVoiceId.value)
const referenceMissing = computed(() => {
  if (hasVoice.value) return false
  return refMode.value === 'upload'
    ? !audioFile.value
    : refMode.value === 'library'
      ? !selectedRef.value
      : !selectedServer.value
})
/**
 * 是否已选定参考音频（与「是否必填」无关）。
 * 用于提示：服务端一旦收到 voice_id 就会整本用预设音色、参考音频被忽略，
 * 两者无法叠加，需要明确告知用户当前实际会走哪条路径。
 */
const referenceSelected = computed(() =>
  refMode.value === 'upload'
    ? !!audioFile.value
    : refMode.value === 'library'
      ? !!selectedRef.value
      : !!selectedServer.value,
)

const canSubmit = computed(
  () => ((storeBookId.value != null) || !!bookFile.value) && !referenceMissing.value,
)

// antd Select 的 value 用稳定的 id（而非整个对象）：重新拉取列表后对象引用会变，
// 但 id 不变，computed 通过 id 反查当前选中项，避免选中标签漂移。
const selectedRef = computed<UserReferenceAudio | null>(
  () => libraryList.value.find((r) => r.id === selectedRefId.value) || null,
)
const selectedServer = computed<ServerReferenceAudio | null>(
  () => serverList.value.find((r) => r.id === selectedServerId.value) || null,
)
const selectedRefModel = computed({
  get: () => selectedRefId.value as any,
  set: (v: number | string | null) => {
    selectedRefId.value = v == null ? null : (v as number)
  },
})
const selectedServerModel = computed({
  get: () => selectedServerId.value as any,
  set: (v: number | string | null) => {
    selectedServerId.value = v == null ? null : (v as number)
  },
})
const libraryOptions = computed(() =>
  libraryList.value.map((r) => ({ value: r.id, label: r.name })),
)
const serverOptions = computed(() =>
  serverList.value.map((r) => ({ value: r.id, label: r.name })),
)

// 积分概况（用于提交前校验与界面提示）——读取全局 credits store，与顶栏/充值页共享数据源
const creditsStore = useCreditsStore()
const creditBalance = computed(() => creditsStore.balance)
const creditPerTask = computed(() => creditsStore.creditsPerTask)
const creditEnabled = computed(() => creditsStore.enabled)
const creditEnough = computed(() => creditsStore.enoughForOneTask)

const loadCredits = () => creditsStore.fetchBalance()

// 从书城跳转带入的书
const storeBookId = ref<number | null>(null)
const storeBookName = ref('')

// 试听：统一由 useAudioPreview 管理（组件卸载自动清理、play() 拒绝捕获、切换竞态守卫）
const onPreviewError = () => message.error('试听失败')
const userPreview = useAudioPreview((id) => ttsApi.previewUserReference(id), onPreviewError)
const serverPreview = useAudioPreview((id) => ttsApi.previewServerReference(id), onPreviewError)

const previewingId = userPreview.previewingId
const serverPreviewingId = serverPreview.previewingId
const togglePreview = (r: UserReferenceAudio) => userPreview.toggle(r.id)
const toggleServerPreview = (r: ServerReferenceAudio) => serverPreview.toggle(r.id)

const SUPPORTED_REF_EXTENSIONS = [
  '.wav', '.mp3', '.flac', '.aac', '.ogg', '.m4a',
  '.mp4', '.avi', '.mkv', '.mov', '.flv', '.wmv', '.webm', '.m4v', '.3gp',
]

const SUPPORTED_BOOK_EXTENSIONS = ['.txt', '.pdf', '.epub', '.mobi', '.md']

const estimateText = computed(() =>
  totalSegments.value && estimateSeconds.value
    ? `任务共 ${totalSegments.value} 段，预计约 ${formatDuration(estimateSeconds.value)} 完成（并发 ${configStore.ttsConcurrency}，高峰可能排队）`
    : '',
)

// 成功提示文案：耗时预估 + 「边合成边听」指引，降低用户干等的焦虑
const createdDescription = computed(() => {
  const base = estimateText.value || `任务ID: ${taskId.value}，请在任务列表中查看进度`
  return `${base}。等待期间可到任务列表点「在线听」，已合成的片段支持边合成边收听。`
})

// ---------- 记住上次选择的参考音色 ----------
// 仅记忆「我的库 / 服务器库」的选择（上传的本地文件无法跨会话持久化），
// 下次进入合成页自动恢复，减少重复挑选音色的操作成本
const LAST_REF_KEY = 'tts_last_reference'
interface LastRefMemory {
  mode: 'user' | 'server'
  id: number
}

const saveLastReference = () => {
  const memory: LastRefMemory | null =
    refMode.value === 'library' && selectedRefId.value != null
      ? { mode: 'user', id: selectedRefId.value }
      : refMode.value === 'server' && selectedServerId.value != null
        ? { mode: 'server', id: selectedServerId.value }
        : null
  if (memory) {
    try {
      localStorage.setItem(LAST_REF_KEY, JSON.stringify(memory))
    } catch {
      // 隐私模式等存储不可用时静默忽略
    }
  }
}

/** 列表加载完成后恢复上次的选择（找不到对应条目则忽略） */
const restoreLastReference = (mode: 'user' | 'server') => {
  try {
    const raw = localStorage.getItem(LAST_REF_KEY)
    if (!raw) return
    const memory = JSON.parse(raw) as LastRefMemory
    if (!memory || memory.mode !== mode) return
    if (mode === 'user') {
      if (libraryList.value.some((r) => r.id === memory.id)) {
        refMode.value = 'library'
        selectedRefId.value = memory.id
      }
    } else {
      if (serverList.value.some((r) => r.id === memory.id)) {
        refMode.value = 'server'
        selectedServerId.value = memory.id
      }
    }
  } catch {
    // 数据损坏时忽略
  }
}

const loadLibrary = async () => {
  libraryLoading.value = true
  try {
    const res = await ttsApi.listUserReferences()
    if (res.data.success) {
      libraryList.value = res.data.references || []
      restoreLastReference('user')
    }
  } catch {
    // 静默失败
  } finally {
    libraryLoading.value = false
  }
}

// 服务器共享参考音频库（所有登录用户合成时均可引用）
const loadServerReferences = async () => {
  serverLoading.value = true
  try {
    const res = await ttsApi.listServerReferences()
    if (res.data.success) {
      serverList.value = res.data.references || []
      restoreLastReference('server')
    }
  } catch {
    // 静默失败
  } finally {
    serverLoading.value = false
  }
}

// 拉取可选预设音色清单（供默认音色下拉；逐段绑定在听书页）
const loadVoices = async () => {
  voiceLoading.value = true
  try {
    const res = await ttsApi.getVoices()
    if (res.data.success) {
      voiceOptions.value = res.data.voices || []
    }
  } catch {
    // 拉取失败不阻断主流程
  } finally {
    voiceLoading.value = false
  }
}

const handleBookChange: UploadProps['beforeUpload'] = (file) => {
  const fileName = (file.name || '').toLowerCase()
  if (!SUPPORTED_BOOK_EXTENSIONS.some((ext) => fileName.endsWith(ext))) {
    message.warning('不支持的电子书格式，请选择 TXT / PDF / EPUB / MOBI')
    return false
  }
  if (file.size > configStore.maxFileSizeBytes) {
    message.warning(`电子书文件大小不能超过 ${formatFileSize(configStore.maxFileSizeBytes)}`)
    return false
  }
  bookFile.value = file
  return false
}

const handleAudioChange: UploadProps['beforeUpload'] = (file) => {
  if (file.size > configStore.maxFileSizeBytes) {
    message.warning(`参考文件大小不能超过 ${formatFileSize(configStore.maxFileSizeBytes)}`)
    return false
  }
  const fileName = (file.name || '').toLowerCase()
  if (!SUPPORTED_REF_EXTENSIONS.some((ext) => fileName.endsWith(ext))) {
    message.warning('不支持的文件格式，请选择 WAV/MP3/FLAC 等音频或 MP4/AVI/MKV 等视频文件')
    return false
  }
  audioFile.value = file
  return false
}

const handleRemoveBook = () => {
  bookFile.value = null
}

const handleRemoveAudio = () => {
  audioFile.value = null
}

const handleSynthesize = () => {
  const hasStoreBook = storeBookId.value != null
  if (!hasStoreBook && !bookFile.value) {
    message.warning('请选择电子书文件')
    return
  }

  // 选了预设默认音色时，参考音频为可选（可用预置音色直接合成）
  const hasVoice = !!defaultVoiceId.value

  let refFile: File | null = null
  let refId: string | null = null
  let refSource: 'user' | 'server' | null = null
  if (refMode.value === 'library') {
    if (!selectedRef.value) {
      if (!hasVoice) {
        message.warning('请从我的参考音频库选择一个音频，或先在上方选择一个预设音色')
        return
      }
      // 选了预设音色但没选库音频：仅用预设音色合成（不传参考音频）
    } else {
      refId = selectedRef.value.fileName
      refSource = 'user'
    }
  } else if (refMode.value === 'server') {
    if (!selectedServer.value) {
      if (!hasVoice) {
        message.warning('请从服务器参考音频库选择一个音频，或先在上方选择一个预设音色')
        return
      }
    } else {
      refId = selectedServer.value.fileName
      refSource = 'server'
    }
  } else {
    if (!audioFile.value) {
      if (!hasVoice) {
        message.warning('请选择参考音频文件，或先在上方选择一个预设音色')
        return
      }
    } else {
      refFile = resolveRawFile(audioFile.value)
    }
  }

  // 提交前积分校验：积分功能开启且余额不足时，直接引导充值，不再弹出二次确认
  if (creditEnabled.value && !creditEnough.value) {
    Modal.confirm({
      title: '积分不足',
      content: `每次合成需要 ${creditPerTask.value} 积分，当前余额 ${creditBalance.value}，不足以完成一次合成。是否前往充值？`,
      okText: '去充值',
      cancelText: '取消',
      onOk: () => router.push('/credits'),
    })
    return
  }

  // 提交前二次确认
  Modal.confirm({
    title: '确认开始合成？',
    content: hasVoice && !refFile && !refId
      ? '系统将用所选预设音色对整本书分段合成（无需参考音频），耗时取决于书籍长度与排队情况。'
      : (refFile || refId)
        ? '系统将用所选参考音频的克隆音色对整本书分段合成，耗时取决于书籍长度与排队情况，可稍后在任务列表查看实时进度。'
        : '系统将对整本书分段并逐段合成，耗时取决于书籍长度与排队情况，可稍后在任务列表查看实时进度。',
    okText: '开始合成',
    cancelText: '再检查一下',
    onOk: () => doSynthesize(hasStoreBook, refFile, refId, refSource),
  })
}

const doSynthesize = async (hasStoreBook: boolean, refFile: File | null, refId: string | null, refSource: 'user' | 'server' | null) => {
  uploading.value = true
  progress.value = 0
  const voice = defaultVoiceId.value
  // 服务端语义：一旦传了 voice_id，就会把每个段落都设成该预设音色，参考音频被整本忽略
  // （VoiceCloningTtsService 的 segVoice 优先分支），两者无法叠加。
  // 与 Android 对齐：已选参考音频时以克隆为准，不再下发 voiceId，
  // 避免用户上传了参考音频却被静默丢弃、还以为在用克隆音色。
  const effectiveVoice = refFile || refId ? null : voice
  try {
    let response
    if (hasStoreBook) {
      response = await ttsApi.createTtsFromStore(
        storeBookId.value!,
        refFile ? { file: refFile, referenceId: refId ?? undefined, source: refSource ?? undefined } : { referenceId: refId ?? undefined, source: refSource ?? undefined },
        effectiveVoice,
      )
    } else {
      const book = resolveRawFile(bookFile.value)
      if (!book) {
        message.error('电子书文件已失效，请重新选择后再试')
        return
      }
      if (refFile) {
        response = await ttsApi.uploadBook(book, refFile, (p) => (progress.value = p), effectiveVoice)
      } else if (refId) {
        response = await ttsApi.uploadBookWithRef(book, refId, refSource!, (p) => (progress.value = p), effectiveVoice)
      } else {
        // 仅用预设音色合成，不传任何参考音频
        response = await ttsApi.uploadBookOnly(book, voice!, (p) => (progress.value = p))
      }
    }

    if (response.data.success) {
      taskId.value = response.data.taskId
      totalSegments.value = response.data.totalSegments || null
      estimateSeconds.value = response.data.totalSegments
        ? configStore.estimateSeconds(response.data.totalSegments)
        : null
      progress.value = 100
      message.success('任务创建成功，正在后台处理')
      // 合成成功已扣费，立即刷新余额（否则用户看不到扣减、误以为未提交而重复合成）
      loadCredits()

      // 上传文件时顺带保存到「我的库」以便复用
      if (refFile && saveToLibrary.value) {
        try {
          await ttsApi.uploadUserReference(refFile)
          loadLibrary()
        } catch {
          // 保存到库失败不影响主流程
        }
      }
    } else {
      message.error(response.data.message || '创建失败')
    }
  } catch (error: any) {
    // 积分不足（后端返回 402）：引导去充值，不要诱导重试产生重复任务
    // 积分不足（后端返回 402，body: {success,message,code:'INSUFFICIENT_CREDITS',required,balance,shortage}）
    if (error.response?.status === 402) {
      const data = error.response?.data || {}
      const required: number | undefined = data.required
      const balance: number | undefined = data.balance
      const shortage: number | undefined = data.shortage
      // 优先用结构化的「还差 N 积分」提示；字段缺失时退回 message / 兜底文案
      const detail =
        (typeof shortage === 'number' && shortage > 0)
          ? `积分不足，还差 ${shortage} 积分（当前 ${typeof balance === 'number' ? balance : '?'} / 本次需 ${typeof required === 'number' ? required : '?'}）`
          : (data.message || '本次合成所需积分不足，请先充值后再试。')
      Modal.confirm({
        title: '积分不足',
        content: detail,
        okText: '去充值',
        cancelText: '取消',
        onOk: () => router.push('/credits'),
      })
      return
    }
    // 超时/网络中断时后端可能已经收到请求并创建了任务——直接提示"创建失败"会诱导
    // 用户立刻重试，产生重复的昂贵合成任务。先引导刷新任务列表确认。
    if (error.code === 'ECONNABORTED' || !error.response) {
      message.warning('网络不稳定，任务可能已创建。请先到「任务列表」确认，避免重复合成')
    } else {
      message.error(error.response?.data?.message || '创建失败')
    }
  } finally {
    uploading.value = false
  }
}

/** 校验路由参数为合法书城书籍 ID：与 Player.vue 的 parseTaskId 同源写法。
 *  Number() 会放过 '0x10'/'1e3'/' 12 '，且 Number('abc') 得 NaN——
 *  `NaN != null` 为 true，会让 hasStoreBook 误判为已选书籍，把 bookId=NaN/0 提交到
 *  /tts/from-store；只有纯数字且为正安全整数才视为有效参数。 */
function parseStoreBookId(raw: unknown): number | null {
  if (typeof raw !== 'string' || !/^\d+$/.test(raw)) return null
  const id = Number(raw)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

onMounted(() => {
  const sid = route.query.storeBookId
  const parsedId = parseStoreBookId(sid)
  if (parsedId != null) {
    storeBookId.value = parsedId
    storeBookName.value = (route.query.name as string) || '书城书籍'
  }
  loadLibrary()
  loadServerReferences()
  loadVoices()
  loadCredits()
})

// 选择变化时记住音色（仅库选择；upload 模式不覆盖上次的库记忆）
watch([refMode, selectedRefId, selectedServerId], () => saveLastReference())
</script>

<template>
  <Card>
    <Title :level="4">
      <AudioOutlined /> TTS语音合成
    </Title>

    <Space direction="vertical" style="width: 100%" size="large">
      <Alert
        message="使用说明"
        :description="`选择电子书（TXT/PDF/EPUB/MOBI）和参考音频，系统将用音色克隆技术生成有声书。建议参考音频为 10–30 秒的干净人声（无背景音乐/多人说话），效果更佳。`"
        type="info"
        show-icon
      >
        <template #action>
          <a @click="guideVisible = true">查看参考音频录制指南</a>
        </template>
      </Alert>

      <Alert
        v-if="creditEnabled"
        style="margin-bottom: 16px"
        :type="creditEnough ? 'info' : 'warning'"
        show-icon
        :message="creditEnough
          ? `当前积分：${creditBalance}，每次合成消耗 ${creditPerTask}`
          : `积分不足：当前 ${creditBalance}，每次合成需 ${creditPerTask}，请先充值后再试`"
      />

      <Modal
        v-model:open="guideVisible"
        title="参考音频录制指南"
        :footer="null"
      >
        <ol class="guide-list">
          <li><Text strong>安静环境</Text>：在安静房间录制，避免空调、风扇等持续噪音。</li>
          <li><Text strong>时长 10–30 秒</Text>：说一段自然流畅的话，语速平稳，不要过快或过慢。</li>
          <li><Text strong>单人清晰人声</Text>：只录一个人的声音，不要有背景音乐、多人同时说话。</li>
          <li><Text strong>音色一致</Text>：整段保持同一音色、音量和情绪，中途不要换人或改变语气。</li>
          <li><Text strong>格式</Text>：支持 WAV/MP3 等音频，也可上传含人声的视频（自动提取音轨）。</li>
        </ol>
      </Modal>

      <!-- 1. 电子书 -->
      <div>
        <Text strong>1. 选择电子书（TXT/PDF/EPUB/MOBI，≤{{ formatFileSize(configStore.maxFileSizeBytes) }}）</Text>
        <div v-if="storeBookId">
          <Tag color="blue" style="margin-top: 8px">已选书城书籍：《{{ storeBookName }}》</Tag>
        </div>
        <Upload
          v-else
          accept=".txt,.pdf,.epub,.mobi,.md"
          :max-count="1"
          :before-upload="handleBookChange"
          :file-list="bookFile ? [toUploadFile(bookFile)] : []"
          @remove="handleRemoveBook"
          style="margin-top: 8px"
        >
          <Button>
            <UploadOutlined /> 选择电子书
          </Button>
        </Upload>
        <div v-if="bookFile" style="margin-top: 8px">
          <Text type="success">已选择: {{ bookFile.name }} ({{ formatFileSize(bookFile.size) }})</Text>
        </div>
      </div>

      <!-- 2. 参考音频 -->
      <div>
        <Text strong>2. 选择参考音频</Text>
        <div style="margin-top: 8px">
          <Radio.Group v-model:value="refMode">
            <Radio.Button value="upload">上传新文件</Radio.Button>
            <Radio.Button value="library">从我的库选择</Radio.Button>
            <Radio.Button value="server">从服务器库选择</Radio.Button>
          </Radio.Group>
        </div>

        <!-- 上传模式 -->
        <div v-if="refMode === 'upload'" style="margin-top: 8px">
          <Upload
            accept=".wav,.mp3,.flac,.aac,.ogg,.m4a,.mp4,.avi,.mkv,.mov,.flv,.wmv,.webm,.m4v,.3gp"
            :max-count="1"
            :before-upload="handleAudioChange"
            :file-list="audioFile ? [toUploadFile(audioFile)] : []"
            @remove="handleRemoveAudio"
          >
            <Button>
              <UploadOutlined /> 选择参考文件
            </Button>
          </Upload>
          <div v-if="audioFile" style="margin-top: 8px">
            <Text type="success">已选择: {{ audioFile.name }}</Text>
          </div>
          <Checkbox v-model:checked="saveToLibrary" style="margin-top: 8px">
            同时保存到「我的参考音频库」，下次合成可直接复用
          </Checkbox>
        </div>

        <!-- 我的库选择模式 -->
        <div v-else-if="refMode === 'library'" style="margin-top: 8px">
          <Space style="width: 100%">
            <Select
              v-model:value="selectedRefModel"
              style="min-width: 320px"
              placeholder="选择已保存的参考音频"
              :loading="libraryLoading"
              :options="libraryOptions"
            />
            <Button :icon="h(ReloadOutlined)" @click="loadLibrary">刷新</Button>
          </Space>
          <div v-if="selectedRef" style="margin-top: 8px">
            <Space>
              <Button size="small" :icon="h(PlayCircleOutlined)" @click="togglePreview(selectedRef)">
                {{ previewingId === selectedRef.id ? '停止试听' : '试听' }}
              </Button>
              <Text type="secondary">选择后将用该音色克隆</Text>
            </Space>
          </div>
          <Alert
            v-if="libraryList.length === 0 && !libraryLoading"
            style="margin-top: 8px"
            type="info"
            show-icon
            message="库为空"
            description="切换到「上传新文件」并勾选保存，即可建立你的音色库"
          />
        </div>

        <!-- 服务器库选择模式 -->
        <div v-else style="margin-top: 8px">
          <Space style="width: 100%">
            <Select
              v-model:value="selectedServerModel"
              style="min-width: 320px"
              placeholder="选择服务器共享参考音频"
              :loading="serverLoading"
              :options="serverOptions"
            />
            <Button :icon="h(ReloadOutlined)" @click="loadServerReferences">刷新</Button>
          </Space>
          <div v-if="selectedServer" style="margin-top: 8px">
            <Space>
              <Button size="small" :icon="h(PlayCircleOutlined)" @click="toggleServerPreview(selectedServer)">
                {{ serverPreviewingId === selectedServer.id ? '停止试听' : '试听' }}
              </Button>
              <Text type="secondary">选择后将用该音色克隆（服务器共享音频）</Text>
            </Space>
          </div>
          <Alert
            v-if="serverList.length === 0 && !serverLoading"
            style="margin-top: 8px"
            type="info"
            show-icon
            message="服务器库为空"
            description="管理员尚未上传共享参考音频"
          />
        </div>
      </div>

        <!-- 2.5 默认音色（可选预设，无需参考音频） -->
      <div>
        <Text strong>2.5 默认音色（可选预设，免参考音频）</Text>
        <div style="margin-top: 8px">
          <Select
            :value="defaultVoiceId ?? undefined"
            style="min-width: 320px"
            placeholder="不选则使用上方参考音频克隆"
            :loading="voiceLoading"
            :options="voiceSelectOptions"
            allow-clear
            @change="(v: unknown) => (defaultVoiceId = ((v as string | null | undefined) ?? null))"
          />
        </div>
        <div style="margin-top: 6px">
          <Text type="secondary">
            选中预设音色后，整本书将用该音色合成，可不再上传参考音频；逐段/逐角色绑定在听书页调整。
          </Text>
          <div v-if="hasVoice && referenceSelected" style="margin-top: 4px">
            <Text type="warning">
              已选择参考音频时将以克隆音色为准，默认音色不生效（两者无法叠加）。如需用预设音色，请先清除参考音频。
            </Text>
          </div>
        </div>
      </div>

      <!-- 3. 提交 -->
      <div>
        <Button
          type="primary"
          size="large"
          :loading="uploading"
          :disabled="!canSubmit"
          @click="handleSynthesize"
        >
          <SyncOutlined /> 开始合成
        </Button>
      </div>

      <div v-if="uploading">
        <Text>正在上传...</Text>
        <Progress :percent="progress" status="active" />
      </div>

      <Alert
        v-if="taskId"
        message="任务创建成功"
        :description="createdDescription"
        type="success"
        show-icon
      >
        <template #action>
          <Button size="small" type="link" @click="router.push('/tasks')">查看进度</Button>
        </template>
      </Alert>
    </Space>
  </Card>
</template>

<style scoped>
.guide-list {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  line-height: 1.6;
}
</style>
