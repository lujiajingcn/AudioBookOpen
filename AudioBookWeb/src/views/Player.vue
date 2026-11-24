<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, h } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Card,
  Button,
  Tag,
  Space,
  message,
  Spin,
  Empty,
  Typography,
  Modal,
  List,
  Tooltip,
  Slider,
  Select,
} from 'ant-design-vue'
import {
  ArrowLeftOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  StepBackwardOutlined,
  StepForwardOutlined,
  DownloadOutlined,
  UnorderedListOutlined,
  StarOutlined,
  StarFilled,
  BackwardOutlined,
  ForwardOutlined,
  SoundOutlined,
} from '@ant-design/icons-vue'
import { ttsApi, assertDownloadable, extractBlobErrorMessage, type SegmentInfo, type TocEntry, type VoiceOption } from '@/services/api'
import { favorites, favoritesVersion } from '@/services/favorites'
import { useAudioEngine } from '@/composables/useAudioEngine'
import { setActivePauser } from '@/services/playbackControl'

const { Title, Text, Paragraph } = Typography

const route = useRoute()
const router = useRouter()

/** 校验路由参数是否为合法任务 ID：对原始字符串做纯数字校验，
 *  Number() 会放过 '0x10'/'1e3'/' 12 ' 等非纯数字，发到后端只会得到误导性 400/404 */
function parseTaskId(raw: unknown): number | null {
  if (typeof raw !== 'string' || !/^\d+$/.test(raw)) return null
  const id = Number(raw)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

// taskId 做成响应式：/player/1 → /player/2 参数跳转复用组件实例，const 捕获一次
// 会导致页面永远显示旧任务（浏览器前进/后退、站内参数跳转）
const taskId = computed(() => parseTaskId(route.params.taskId))
const bookName = computed(() => (route.query.name as string) || `任务 ${taskId.value ?? ''}`)

const segments = ref<SegmentInfo[]>([])
const toc = ref<TocEntry[]>([])
const loading = ref(false)
const tocVisible = ref(false)

// 无缝播放引擎抽到 useAudioEngine；解构后模板里可直接用同名绑定（模板自动解包顶层 ref）
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
  invalidateBuffers,
  loadProgress,
  // 睡眠定时
  sleepTimerMode,
  sleepTimerRemaining,
  sleepTimerSegmentsLeft,
  setSleepTimer,
  setSleepTimerSegments,
  SLEEP_TIMER_OFF,
  SLEEP_TIMER_END_OF_SEGMENT,
  SLEEP_TIMER_SEGMENTS,
} = useAudioEngine(segments, taskId)

// 把「暂停当前播放」注册到全局总线，供 401 拦截器在登录过期时调用，
// 避免过期期间音频继续空放。组件卸载时解绑，避免悬挂引用。
setActivePauser(pause)
onUnmounted(() => setActivePauser(null))

const completedCount = computed(() => segments.value.filter((s) => s.status === 'completed').length)

const formatTime = (s: number) => {
  if (!Number.isFinite(s) || s < 0) return '00:00'
  const m = Math.floor(s / 60)
  const sec = Math.floor(s % 60)
  return `${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
}

const statusMeta = (status: string) => {
  switch (status) {
    case 'completed':
      return { color: 'success', text: '已合成' }
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

const loadData = async () => {
  const id = taskId.value
  if (id == null) return
  loading.value = true
  try {
    const [segRes, tocRes] = await Promise.all([
      ttsApi.getTaskSegmentsList(id),
      ttsApi.getTaskToc(id).catch(() => null),
    ])
    // 守卫：请求期间若 taskId 被切换（/player/1 → /player/2 参数跳转），
    // 丢弃过期响应，避免把旧任务的段落/目录串到新任务上
    if (taskId.value !== id || id == null) return
    if (segRes.data.success) {
      segments.value = segRes.data.segments || []
    } else {
      message.error('加载段落列表失败')
    }
    if (tocRes && tocRes.data.success) {
      toc.value = tocRes.data.toc || []
    }

    // 有上次播放进度时提示续播（带回段内偏移）。
    // 段内只要有有效偏移就提供续播：第 1 段保存了进度也应被纳入
    // （判断条件若限定 segmentNumber > 1，第 1 段进度将永不提示）。
    // loadProgress 为异步（先本地兜底、再拉服务端并与本地合并），故此处 await。
    const last = await loadProgress(id)
    // v5/W10：loadProgress 也是异步的，回来时可能已经切到别的任务 ——
    // 二次复查，避免旧任务的「继续播放」弹窗盖在新任务页面上。
    if (taskId.value !== id || id == null) return
    if (last && last.segmentNumber >= 1 && last.offset > 0) {
      const idx = segments.value.findIndex((s) => s.segmentNumber === last.segmentNumber)
      if (idx >= 0) {
        // 该段可能因重合成不再是 completed：失效则提示并放弃续播，避免用户点「继续」却无反应
        if (segments.value[idx]?.status !== 'completed') {
          message.warning('该段已失效，请重新选择')
          return
        }
        const offsetText = last.offset > 5 ? `（约 ${formatTime(last.offset)} 处）` : ''
        Modal.confirm({
          title: '继续上次收听？',
          content: `上次听到第 ${last.segmentNumber} 段${offsetText}，是否从该处继续？`,
          okText: '继续',
          cancelText: '从头开始',
          // 防御性：不传 last.offset。上次的 offset 在浏览器切换账号 / 设备后可能属于别人，
          // 强行续播会触发段中起播（钟鼓楼段 8 偏移 4 秒 = 只听到最后 0.5 秒）。
          onOk: () => playSegment(idx),
        })
      }
    }
  } catch (e) {
    message.error('加载任务内容失败')
  } finally {
    loading.value = false
  }
}

const scrollToItem = (index: number) => {
  const el = document.getElementById(`seg-${index}`)
  if (!el) return
  const parent = el.closest('.segment-list')
  if (parent) {
    const rect = el.getBoundingClientRect()
    const prect = parent.getBoundingClientRect()
    // 仅当高亮项已不在可视区时才滚动，避免连播时短段频繁 smooth 滚动互相打断
    const visible = rect.top >= prect.top && rect.bottom <= prect.bottom
    if (!visible) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      return
    }
  }
  // 兜底：无父容器时直接滚动
  el.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

// 当前段变化时（连播衔接 / 手动跳转 / 续播）自动把高亮项滚入可视区，
// 否则连播时高亮项会飘出列表窗口，观感上「文字跟不上播放」。
watch(currentIndex, (i) => {
  if (i >= 0) scrollToItem(i)
})

const downloadCurrent = () => {
  // 点击时先捕获段信息：文件名必须在 .then 里沿用点击时刻的段号，
  // 否则下载期间用户切段会得到「旧段音频 + 新段文件名」的错配
  const seg = currentSegment.value
  if (!seg) return
  ttsApi
    .downloadSegment(seg.id)
    .then(async (res) => {
      // 先校验响应是音频而非错误 JSON
      const blob = await assertDownloadable(res)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `segment_${seg.segmentNumber}.mp3`
      a.click()
      // 延迟释放：部分浏览器下同步 revoke 会中断大文件下载
      window.setTimeout(() => URL.revokeObjectURL(url), 10000)
    })
    .catch(async (e: unknown) =>
      message.error(await extractBlobErrorMessage(e, '下载失败')),
    )
}

// ---------- 收藏当前段（服务端为准 + 本地缓存） ----------

/** 依赖收藏缓存版本号：云端同步完成后自动重算「是否已收藏」 */
const isFavorited = computed(() => {
  favoritesVersion.value // 依赖刷新（服务端同步后版本号自增）
  if (!currentSegment.value) return false
  return favorites.has(bookName.value, currentSegment.value.segmentNumber)
})

const toggleFavorite = async () => {
  const seg = currentSegment.value
  if (!seg) return
  if (favorites.has(bookName.value, seg.segmentNumber)) {
    const item = favorites.find(bookName.value, seg.segmentNumber)
    if (item) await favorites.remove(item.id)
    message.success('已取消收藏')
  } else {
    const added = await favorites.add(bookName.value, seg.segmentNumber, seg.textContent, taskId.value ?? undefined)
    if (added) message.success('已收藏该段')
  }
}

// ---------- 睡眠定时（与 Android 四态对齐：关闭 / 播完本段 / 若干段 / N 分钟） ----------
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

/** 当前睡眠定时的可读状态（面板上展示倒计时 / 剩余段数） */
const sleepLabel = computed(() => {
  const mode = sleepTimerMode.value
  if (mode === SLEEP_TIMER_OFF) return '未开启'
  if (mode === SLEEP_TIMER_END_OF_SEGMENT) return '播完本段后暂停'
  if (mode === SLEEP_TIMER_SEGMENTS) return `还剩 ${sleepTimerSegmentsLeft.value} 段`
  const total = Math.max(0, Math.ceil(sleepTimerRemaining.value / 1000))
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')} 后暂停`
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

// 定时自动触发（到期 / 播完本段 / 段数耗尽）后引擎会把模式复位为关闭，选择框同步回到「关闭」
watch(sleepTimerMode, (mode) => {
  if (mode === SLEEP_TIMER_OFF) sleepSelectValue.value = 'off'
})

const jumpToToc = (entry: TocEntry) => {
  tocVisible.value = false
  const idx = segments.value.findIndex((s) => s.segmentNumber === entry.segmentNumber)
  if (idx >= 0) {
    // 高亮项自动滚动交给 watch(currentIndex) 处理（关闭弹窗后再滚动更顺滑）
    playSegment(idx)
  }
}

// ---------- 逐段 / 逐角色绑定音色 ----------
const voiceOptions = ref<VoiceOption[]>([])
const voiceLoading = ref(false)
/** 每段当前选中的音色草稿：segmentId -> voiceId（null 表示清除预设、回退克隆参考音频） */
const voiceDraft = ref<Record<number, string | null>>({})
const voicePanelOpen = ref(false)
const applyingVoices = ref(false)

const voiceSelectOptions = computed(() =>
  voiceOptions.value.map((v) => ({ value: v.id, label: v.name })),
)

const loadVoices = async () => {
  voiceLoading.value = true
  try {
    const res = await ttsApi.getVoices()
    if (res.data.success) voiceOptions.value = res.data.voices || []
  } catch {
    // 拉取失败不影响主流程
  } finally {
    voiceLoading.value = false
  }
}

// 切换任务时整体重置草稿：不同任务的段落 id 不同，旧草稿必须丢弃。
// 声明顺序在下面的 segments 监听之前，保证「先清空、再按新段落补齐」。
watch(taskId, () => {
  voiceDraft.value = {}
})

// 段落加载后，用服务端返回的真实绑定音色初始化草稿。
// 采用「只补齐缺失 key」的合并式初始化，而不是整体替换 voiceDraft：
// 整体替换依赖「只有切任务 / 应用成功这两个时机才会刷新 segments」这一脆弱前提，
// 一旦后续加入轮询刷新或手动刷新按钮，用户尚未应用的编辑会被静默丢弃。
watch(
  segments,
  (list) => {
    const draft: Record<number, string | null> = { ...voiceDraft.value }
    for (const s of list) {
      if (!(s.id in draft)) draft[s.id] = s.voice ?? null
    }
    voiceDraft.value = draft
  },
  { immediate: true },
)

/** 相对服务端原始值发生变化的段：段号 -> voiceId（null 表示清除预设） */
const changedVoices = computed(() => {
  const map: Record<number, string | null> = {}
  let any = false
  for (const s of segments.value) {
    const draft = voiceDraft.value[s.id] ?? null
    const original = s.voice ?? null
    if (draft !== original) {
      map[s.segmentNumber] = draft
      any = true
    }
  }
  return { map, any }
})

const applyVoices = async () => {
  if (!changedVoices.value.any || taskId.value == null) return
  applyingVoices.value = true
  try {
    const res = await ttsApi.updateTaskVoices(taskId.value, {
      // 刻意不下发 defaultVoiceId：服务端用 containsKey 区分「未传（不修改）」与
      // 「传 null（清空）」。这里只改段落级音色，任务级默认音色必须保持原样 ——
      // 显式传 null 会把整本书的默认音色抹掉，之后清除某段预设就无处回退
      //（对「仅预设音色创建」的任务会直接导致该段合成失败）。
      segmentVoices: changedVoices.value.map,
    })
    if (res.data.success) {
      message.success('音色已更新，正在重新合成受影响的片段')
      // 服务端会把受影响段重新合成（pending → completed），
      // 必须清空已解码的 AudioBuffer 缓存，否则 decodeSegment 命中旧 PCM，
      // 用户点该段时会听到旧音色。当前正在播的段不动，由用户自然过渡到下一段。
      invalidateBuffers()
      await loadData()
    } else {
      message.error(res.data.message || '应用失败')
    }
  } catch (e: any) {
    // 服务端对处理中的任务会返回 400 + 「任务仍在处理中，请稍后再修改音色」，
    // 吞掉它会让用户误判成网络故障而反复点击。
    message.error(e?.response?.data?.message || '应用音色失败，请稍后重试')
  } finally {
    applyingVoices.value = false
  }
}

const voiceName = (id: string | null | undefined) => {
  if (!id) return ''
  return voiceOptions.value.find((v) => v.id === id)?.name ?? id
}

/** 校验当前路由参数并（重新）加载任务；参数非法跳回列表 */
function validateAndLoad() {
  if (taskId.value == null) {
    message.error('无效的任务ID')
    router.replace('/tasks')
    return
  }
  // 任务切换（/player/1 → /player/2 参数跳转）时重置旧任务状态，
  // 避免旧任务的段落/播放进度串到新任务
  teardown()
  segments.value = []
  toc.value = []
  loadData()
}

onMounted(() => {
  // 路由参数校验：对原始字符串做 /^\d+$/ 纯数字校验——Number() 会放过
  // '0x10'/'1e3'/' 12 ' 等非纯数字，发到后端只会得到误导性 400/404
  validateAndLoad()
  // /player/1 → /player/2 参数跳转（浏览器前进/后退、站内参数跳转）复用同一组件实例，
  // 不监听参数变化的话页面永远显示旧任务
  watch(() => route.params.taskId, () => validateAndLoad())
  // 拉取可选预设音色清单（逐段绑定下拉用）
  loadVoices()
})
</script>

<template>
  <Card>
    <div class="player-header">
      <Space>
        <Button :icon="h(ArrowLeftOutlined)" @click="router.back()">返回</Button>
        <Title :level="4" style="margin: 0">《{{ bookName }}》在线听书</Title>
      </Space>
      <Space>
        <Button
          :icon="h(UnorderedListOutlined)"
          :disabled="toc.length === 0"
          @click="tocVisible = true"
        >
          目录
        </Button>
      </Space>
    </div>

    <Spin :spinning="loading">
      <template v-if="segments.length > 0">
        <!-- 播放器卡片 -->
        <Card class="player-card" :bordered="false">
          <div v-if="currentSegment" class="now-playing">
            <Tag color="blue">第 {{ currentSegment.segmentNumber }} 段</Tag>
            <Text type="secondary">共 {{ segments.length }} 段 · 已合成 {{ completedCount }} 段</Text>
            <Paragraph class="now-text">{{ currentSegment.textContent }}</Paragraph>
          </div>
          <Empty v-else description="点击下方任意已合成片段开始播放" />

          <!-- 段内进度条 + 时间 -->
          <div v-if="currentSegment" class="seek-row">
            <Button size="small" :icon="h(BackwardOutlined)" :disabled="!currentSegment" @click="skip(-10)" title="后退10秒" />
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
            <Button size="small" :icon="h(ForwardOutlined)" :disabled="!currentSegment" @click="skip(10)" title="前进10秒" />
            <Text type="secondary" class="time-text">
              {{ formatTime(currentTime) }} / {{ formatTime(duration) }}
            </Text>
          </div>

          <div class="player-controls">
            <Button size="large" :icon="h(StepBackwardOutlined)" @click="prevSegment" />
            <Button
              type="primary"
              size="large"
              shape="circle"
              :loading="buffering"
              :icon="h(isPlaying ? PauseCircleOutlined : PlayCircleOutlined)"
              @click="togglePlay"
            />
            <Button size="large" :icon="h(StepForwardOutlined)" @click="nextSegment" />
            <Button
              size="large"
              :icon="h(DownloadOutlined)"
              :disabled="!currentSegment"
              @click="downloadCurrent"
            >
              下载当前段
            </Button>
            <Button
              size="large"
              :icon="h(isFavorited ? StarFilled : StarOutlined)"
              :disabled="!currentSegment"
              @click="toggleFavorite"
            >
              {{ isFavorited ? '已收藏' : '收藏' }}
            </Button>
          </div>

          <!-- 倍速 + 音量 -->
          <div v-if="currentSegment" class="extra-controls">
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

        <!-- 逐段 / 逐角色绑定音色 -->
        <div class="voice-toolbar">
          <Button size="small" :icon="h(SoundOutlined)" @click="voicePanelOpen = !voicePanelOpen">
            {{ voicePanelOpen ? '收起音色' : '逐段音色' }}
          </Button>
          <Button
            v-if="voicePanelOpen"
            type="primary"
            size="small"
            :loading="applyingVoices"
            :disabled="!changedVoices.any"
            @click="applyVoices"
          >
            应用并重新合成
          </Button>
          <Text v-if="voicePanelOpen && changedVoices.any" type="secondary" class="voice-hint">
            已调整 {{ Object.keys(changedVoices.map).length }} 段
          </Text>
          <Text v-if="voicePanelOpen && !changedVoices.any" type="secondary" class="voice-hint">
            选择每段音色后点「应用并重新合成」
          </Text>
        </div>

        <!-- 段落列表 -->
        <div class="segment-list">
          <div
            v-for="(seg, i) in segments"
            :id="`seg-${i}`"
            :key="seg.id"
            class="segment-item"
            :class="{ active: i === currentIndex }"
            @click="playSegment(i)"
          >
            <span class="seg-no">{{ seg.segmentNumber }}</span>
            <span class="seg-text">{{ seg.textContent }}</span>
            <Select
              v-if="voicePanelOpen"
              :value="voiceDraft[seg.id] ?? undefined"
              :options="voiceSelectOptions"
              :loading="voiceLoading"
              allow-clear
              placeholder="跟随默认"
              size="small"
              class="seg-voice"
              @click.stop
              @change="(v: unknown) => (voiceDraft[seg.id] = ((v as string | null | undefined) ?? null))"
            />
            <Tag v-else-if="seg.voice" color="purple" class="seg-voice-tag">{{ voiceName(seg.voice) }}</Tag>
            <Tag :color="statusMeta(seg.status).color" style="margin-left: 8px">
              {{ statusMeta(seg.status).text }}
            </Tag>
            <Tooltip v-if="seg.errorMessage" :title="seg.errorMessage">
              <Text type="danger" class="seg-err">原因</Text>
            </Tooltip>
          </div>
        </div>
      </template>
      <Empty v-else-if="!loading" description="该任务暂无内容" />
    </Spin>

    <!-- 目录弹窗 -->
    <Modal
      v-model:open="tocVisible"
      title="目录"
      :footer="null"
      width="420px"
    >
      <List
        size="small"
        bordered
        :data-source="toc"
        :locale="{ emptyText: '暂无目录' }"
      >
        <template #renderItem="{ item }">
          <List.Item class="toc-item" @click="jumpToToc(item)">
            {{ item.title }}
            <Text type="secondary" style="margin-left: 8px">→ 第 {{ item.segmentNumber }} 段</Text>
          </List.Item>
        </template>
      </List>
    </Modal>
  </Card>
</template>

<style scoped>
.player-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.player-card {
  background: #fafafa;
  margin-bottom: 16px;
}

.now-playing {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.now-text {
  /* 阅读字号跟随全局设置（--reader-font-size 由 useSettingsStore 注入），默认 18px */
  font-size: var(--reader-font-size, 18px);
  line-height: 1.8;
  margin: 8px 0;
  white-space: pre-wrap;
}

.player-controls {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  margin-top: 8px;
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

.segment-list {
  max-height: 60vh;
  overflow-y: auto;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
}

.segment-item {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  cursor: pointer;
  border-bottom: 1px solid #f5f5f5;
  transition: background 0.15s;
}

.segment-item:hover {
  background: #fafafa;
}

.segment-item.active {
  background: #e6f4ff;
}

.segment-item:last-child {
  border-bottom: none;
}

.seg-no {
  min-width: 44px;
  color: #999;
  font-size: 12px;
}

.seg-text {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #333;
  /* 跟读主区域（段落列表）也跟随「阅读字号」设置。
     列表比正文略小一档（0.9×），既响应字号调节又不至于撑爆行高。 */
  font-size: calc(var(--reader-font-size, 18px) * 0.9);
}

.voice-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0 8px;
  flex-wrap: wrap;
}

.voice-hint {
  font-size: 13px;
}

.seg-voice {
  width: 150px;
  margin-left: 8px;
  flex: 0 0 auto;
}

.seg-voice-tag {
  margin-left: 8px;
  flex: 0 0 auto;
}

.seg-err {
  margin-left: 8px;
  font-size: 12px;
}

.toc-item {
  cursor: pointer;
}

.toc-item:hover {
  background: #fafafa;
}

/* 移动端：控制区换行、进度条置顶独占一行、加大触控目标 */
@media (max-width: 768px) {
  .player-header {
    flex-wrap: wrap;
    gap: 8px;
  }

  .seek-row {
    flex-wrap: wrap;
  }

  .seek-slider {
    flex-basis: 100%;
    order: -1;
    margin: 0 0 8px;
  }

  .time-text {
    min-width: 0;
    margin-left: auto;
  }

  .player-controls {
    flex-wrap: wrap;
    gap: 12px;
  }

  .player-controls .ant-btn {
    min-width: 48px;
    min-height: 44px;
  }

  .extra-controls {
    gap: 16px;
  }

  .segment-item {
    padding: 12px;
  }
}

/* 夜间模式：播放器卡片、段落列表等自定义浅色面板在暗色下翻转为深色，
   消除夜里播放/跟读时的刺眼白底；文字色同步调亮。 */
.dark .player-card {
  background: #1f1f1f;
}

.dark .segment-list {
  border-color: #303030;
}

.dark .segment-item {
  border-bottom-color: #303030;
}

.dark .segment-item:hover {
  background: #262626;
}

.dark .segment-item.active {
  background: #111d2c;
}

.dark .seg-text {
  color: rgba(255, 255, 255, 0.85);
}

.dark .seg-no {
  color: rgba(255, 255, 255, 0.45);
}

.dark .now-text {
  color: rgba(255, 255, 255, 0.9);
}

/* 目录弹窗：暗色下 #fafafa 悬停底 + 浅色文字 = 实质不可读，必须翻转 */
.dark .toc-item:hover {
  background: #262626;
}
</style>
