<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, h } from 'vue'
import { useRouter } from 'vue-router'
import {
  Table,
  Button,
  Tag,
  Space,
  message,
  Card,
  Typography,
  Progress,
  Modal,
  Empty,
  Tooltip,
} from 'ant-design-vue'
import {
  CalendarOutlined,
  DownloadOutlined,
  ReloadOutlined,
  PlayCircleOutlined,
  RedoOutlined,
  DeleteOutlined,
} from '@ant-design/icons-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import { ttsApi, assertDownloadable, extractBlobErrorMessage, type TtsTaskResponse, type SegmentInfo } from '@/services/api'
import { useCreditsStore } from '@/stores/credits'
import { SseClient } from '@/services/sse'
import { useConfigStore } from '@/stores/config'
import apiClient, { redirectToLogin, CLIENT_APP_ID } from '@/services/apiClient'
import { APP_ID_HEADER } from '@audiobook/shared'
import { formatDateTime, formatDuration } from '@audiobook/shared'
import dayjs from 'dayjs'

const { Title, Text } = Typography
const router = useRouter()
const configStore = useConfigStore()
const creditsStore = useCreditsStore()

const tasks = ref<TtsTaskResponse[]>([])
const loading = ref(false)
/** 服务端当前排队规模（queuedCount），用于排队中任务展示「队列约 N 个」 */
const queuedCount = ref(0)

// 失败段详情缓存 + 加载状态
const detailMap = ref<Record<number, SegmentInfo[]>>({})
const loadingDetail = ref<Record<number, boolean>>({})

// SSE 连接管理（每个活跃任务一个连接）
const sseClients = new Map<number, SseClient>()
/** v5/W9：已经就「实时进度失联」警告过的任务，避免每 30s 轮询重连后重复弹窗 */
const warnedTaskIds = new Set<number>()
const notifiedTasks = new Set<number>()
// SSE 并发上限：浏览器同域名 HTTP/1.1 仅 6 连接，活跃任务多时其余降级为轮询兜底
const MAX_SSE = 3

const getStatusColor = (status: string) => {
  switch (status) {
    case 'completed':
      return 'success'
    case 'processing':
      return 'processing'
    case 'queued':
      return 'default'
    case 'partial':
      return 'warning'
    case 'paused':
      // 管理端可以暂停书城任务；缺这一条时标签直接渲染英文 paused
      return 'warning'
    case 'failed':
      return 'error'
    default:
      return 'default'
  }
}

const getStatusText = (status: string) => {
  switch (status) {
    case 'completed':
      return '已完成'
    case 'processing':
      return '处理中'
    case 'queued':
    case 'pending':
      return '排队中'
    case 'partial':
      return '部分完成'
    case 'paused':
      return '已暂停'
    case 'failed':
      return '失败'
    default:
      return status
  }
}

// pending 是后端建任务后的初始状态（等待工作线程提取文本并开始合成），对用户来说就是"排队中"
const isActive = (status: string) =>
  status === 'queued' || status === 'pending' || status === 'processing'

const isTerminal = (status: string) =>
  status === 'completed' || status === 'partial' || status === 'failed'

/** 预计剩余时间（基于剩余段数 × 单段耗时 / 并发）+ 预计完成时刻 */
const remainingText = (record: TtsTaskResponse) => {
  if (!isActive(record.status)) return ''
  const remaining = (record.totalSegments || 0) - (record.completedSegments || 0)
  if (remaining <= 0) return ''
  const seconds = configStore.estimateSeconds(remaining)
  const finishAt = dayjs().add(seconds, 'second').format('HH:mm')
  return `预计剩余 ${formatDuration(seconds)}，约 ${finishAt} 完成`
}

// ---------- 桌面通知 ----------

const notify = (taskId: number, status: string) => {
  if (notifiedTasks.has(taskId)) return
  if (!('Notification' in window)) return

  const name = tasks.value.find((t) => t.taskId === taskId)?.originalBookName || `任务 ${taskId}`
  const title =
    status === 'completed' ? '合成完成' : status === 'partial' ? '部分完成' : '合成失败'
  const body = `《${name}》${title}`

  const fire = () => new Notification(title, { body })
  if (Notification.permission === 'granted') {
    fire()
    notifiedTasks.add(taskId)
  } else if (Notification.permission === 'default') {
    // 必须带 catch：非安全上下文（http 访问且非 localhost）下部分实现会 reject，
    // 而这是个无人接管的 promise ⇒ Unhandled rejection，
    // 且同一次 SSE onMessage 里的后续逻辑会因此不再执行。
    Notification.requestPermission()
      .then((p) => {
        if (p === 'granted') {
          fire()
          notifiedTasks.add(taskId)
        }
      })
      .catch(() => { /* 用户未授权：忽略 */ })
  }
}

// ---------- 数据加载 ----------

const loadTasks = async () => {
  try {
    loading.value = true
    const response = await ttsApi.getUserTasks()
    if (response.data.success) {
      tasks.value = response.data.tasks
      queuedCount.value = response.data.queuedCount ?? 0
      syncSse()
    }
  } catch {
    message.error('加载任务列表失败')
  } finally {
    loading.value = false
  }
}

// ---------- SSE 实时进度 ----------

const streamUrl = (taskId: number) => `${apiClient.defaults.baseURL}/tts/stream/${taskId}`

const setupSse = (task: TtsTaskResponse) => {
  if (sseClients.has(task.taskId)) return
  // 认证凭据由 SseClient 以 HttpOnly Cookie 方式自动携带，无需传入 token。
  // 应用标识必须显式传：SSE 走 fetch，不继承 apiClient 的默认请求头，
  // 缺了它服务端会去读历史共享 Cookie 名而非本端专属的 AUTH_TOKEN_WEB。
  const client = new SseClient(
    streamUrl(task.taskId),
    {
      // SSE 走 fetch 而非 axios，401/403 不会经过 axios 401 拦截器，此处显式登出跳转
      onUnauthorized: () => {
        redirectToLogin('登录已过期，请重新登录')
      },
      onMessage: (event, data) => {
        if (event === 'init' || event === 'progress') {
          applyProgress(data)
        } else if (event === 'complete') {
          applyProgress(data)
          closeSse(data.taskId)
          notify(data.taskId, data.status)
          // 终态后轻量刷新一次，确保状态与后端一致
          loadTasks()
        }
      },
      onGiveUp: (reason) => {
        // SSE 彻底放弃（401/403/404 永久错误，或重连 10 次仍失败）：
        // 从连接表移除，交给下方轮询兜底继续跟踪进度
        sseClients.delete(task.taskId)
        if (reason === 'retries-exhausted' && !warnedTaskIds.has(task.taskId)) {
          // v5/W9：同一任务只警告一次。原实现每次 onGiveUp 都弹，而 syncSse 会在
          // 下次 loadTasks/quietPoll（≤30s）里发现任务仍活跃 → 重建连接 → 再放弃 → 再弹窗，
          // 弱网下变成周期性骚扰。
          warnedTaskIds.add(task.taskId)
          message.warning(`任务 ${task.taskId} 实时进度连接失败，已切换为定期刷新`)
        }
      },
    },
    { [APP_ID_HEADER]: CLIENT_APP_ID },
  )
  sseClients.set(task.taskId, client)
  client.start()
}

const applyProgress = (data: any) => {
  // 形参守卫：畸形 SSE 载荷（缺 taskId / status）直接丢弃，避免静默 no-op 或异常抛出
  if (!data || typeof data.taskId === 'undefined' || typeof data.status === 'undefined') return
  const t = tasks.value.find((x) => x.taskId === data.taskId)
  if (!t) return
  if (data.status !== undefined) t.status = data.status
  if (data.completedSegments !== undefined) t.completedSegments = data.completedSegments
  if (data.totalSegments !== undefined) t.totalSegments = data.totalSegments
}

const closeSse = (taskId: number) => {
  const client = sseClients.get(taskId)
  if (client) {
    client.close()
    sseClients.delete(taskId)
  }
}

const syncSse = () => {
  const activeIds = new Set(tasks.value.filter((t) => isActive(t.status)).map((t) => t.taskId))
  // 关闭已到终态的任务连接
  for (const id of [...sseClients.keys()]) {
    if (!activeIds.has(id)) closeSse(id)
  }
  // 为活跃任务建立连接，最多保留 MAX_SSE 条并发：
  // 浏览器对同一域名 HTTP/1.1 仅 6 个连接，超限会阻塞普通 API 请求；
  // 超出部分依赖 30s 轮询兜底（quietPoll）继续跟踪进度，不丢更新。
  let openCount = sseClients.size
  for (const t of tasks.value) {
    if (openCount >= MAX_SSE) break
    if (isActive(t.status)) {
      setupSse(t)
      openCount = sseClients.size
    }
  }
}

// ---------- 操作 ----------

const goPlayer = (record: TtsTaskResponse) => {
  router.push({
    path: `/player/${record.taskId}`,
    query: { name: record.originalBookName || undefined },
  })
}

const handleDownload = async (taskId: number) => {
  try {
    const response = await ttsApi.downloadAllSegments(taskId)
    // 先校验响应是 zip 而非错误 JSON，避免把错误当文件下载
    const blob = await assertDownloadable(response)
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', `task_${taskId}.zip`)
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    // 延迟释放：整本 zip 体积大（弱网下下载久），部分浏览器同步 revoke 会中断下载；
    // 单段 mp3 仍用 10s 短延迟，此处大文件显著拉长到 120s，避免下载被截断
    window.setTimeout(() => window.URL.revokeObjectURL(url), 120000)
    message.success('下载已开始')
  } catch (e) {
    // 错误多为非 2xx 的 Blob 响应，解析出真实原因，避免显示英文泛化文案
    message.error(await extractBlobErrorMessage(e, '下载失败'))
  }
}

const handleRetry = async (record: TtsTaskResponse) => {
  try {
    const res = await ttsApi.retryTask(record.taskId)
    message.success(res.data.message || '已重新调度')
    detailMap.value[record.taskId] = []
    await loadTasks()
    // 重试会重新消耗积分：顶栏余额在 MainLayout 挂载后不再主动刷新，
    // 这里拉取一次（creditsStore 为全局 store，顶栏响应式同步），避免显示扣费前的旧值
    await creditsStore.fetchBalance()
  } catch (e: any) {
    message.error(e.response?.data?.message || '重试失败')
  }
}

/** 当前失败任务集合（「重试全部失败」按钮的显示与操作范围） */
const failedTasks = computed(() => tasks.value.filter((t) => t.status === 'failed'))

const handleRetryAll = () => {
  const ids = failedTasks.value.map((t) => t.taskId)
  if (ids.length === 0) return
  Modal.confirm({
    title: '重试全部失败任务',
    content: `将为 ${ids.length} 个失败任务重新调度。任务失败时积分已退还，重试将按规则重新消耗积分（部分完成的任务不重复收费）。`,
    okText: '全部重试',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await ttsApi.retryFailedTasks(ids)
        if (res.data.success) {
          // 服务端只要循环跑完就回 success=true，即使全部被跳过（积分不足/仍在处理）。
          // 这时弹绿色「成功」会盖住真正的结论，必须按实际重试条数分流。
          const retried = res.data.retried ?? 0
          if (retried > 0) {
            message.success(res.data.message || '已重新调度')
          } else {
            message.warning(res.data.message || '没有任务被重新调度')
          }
          // 部分任务可能因积分不足等原因被跳过，给用户明细
          const skipped = res.data.skipped || []
          if (skipped.length > 0) {
            message.warning(skipped.map((s) => `#${s.taskId}：${s.message}`).join('；'))
          }
        }
        detailMap.value = {}
        await loadTasks()
        // 批量重试同样会重新消耗积分，刷新余额（与单条重试一致）
        await creditsStore.fetchBalance()
      } catch (e: any) {
        message.error(e.response?.data?.message || '批量重试失败')
      }
    },
  })
}

const handleDelete = (record: TtsTaskResponse) => {
  Modal.confirm({
    title: '删除任务',
    content: `确定删除任务 ${record.taskId}${record.originalBookName ? `（${record.originalBookName}）` : ''} 吗？删除后音频将无法恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await ttsApi.deleteTask(record.taskId)
        closeSse(record.taskId)
        tasks.value = tasks.value.filter((t) => t.taskId !== record.taskId)
        // 同步清掉该任务的失败段缓存：detailMap 以 taskId 为 key 缓存整本书的
        // 失败段正文（可达数千段），原先只在「重试全部」时整体清空。
        // 不清的话，用户连续「重试→失败→展开→删除」几十次后，
        // 这些正文会永久驻留在内存里。
        delete detailMap.value[record.taskId]
        delete loadingDetail.value[record.taskId]
        message.success('已删除')
      } catch (e: any) {
        message.error(e.response?.data?.message || '删除失败')
      }
    },
  })
}

// ---------- 失败段详情（展开行懒加载） ----------

const onExpand = (expanded: boolean, record: TtsTaskResponse) => {
  if (!expanded) return
  if (detailMap.value[record.taskId] || loadingDetail.value[record.taskId]) return
  loadingDetail.value[record.taskId] = true
  ttsApi
    .getTaskSegmentsList(record.taskId)
    .then((res) => {
      if (res.data.success) {
        detailMap.value[record.taskId] = (res.data.segments || []).filter(
          (s) => s.status === 'failed',
        )
      }
    })
    .catch(() => message.error('加载失败详情失败'))
    .finally(() => {
      loadingDetail.value[record.taskId] = false
    })
}

// ---------- 轮询兜底 ----------

/**
 * SSE 断线/被放弃时的兜底：页面挂载期间每 30s 静默刷新一次任务列表。
 * 只在有活跃任务时真正发请求，全部终态后自动静默，避免无意义的流量消耗。
 */
const POLL_INTERVAL_MS = 30000
let pollTimer: number | null = null
let pollInFlight = false

const quietPoll = () => {
  const hasActive = tasks.value.some((t) => isActive(t.status))
  if (!hasActive || pollInFlight) return
  pollInFlight = true
  ttsApi
    .getUserTasks()
    .then((res) => {
      if (res.data.success) {
        tasks.value = res.data.tasks
        queuedCount.value = res.data.queuedCount ?? 0
        syncSse()
      }
    })
    .catch(() => {
      // 轮询失败保持静默（主路径 loadTasks 已有错误提示），下个周期自动重试
    })
    .finally(() => {
      pollInFlight = false
    })
}

// ---------- 列定义 ----------

const columns: ColumnsType<TtsTaskResponse> = [
  {
    title: '任务ID',
    dataIndex: 'taskId',
    key: 'taskId',
    width: 90,
  },
  {
    title: '书名',
    dataIndex: 'originalBookName',
    key: 'originalBookName',
    width: 200,
    ellipsis: true,
    customRender: ({ text }) => text || '—',
  },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    width: 140,
    customRender: ({ record }) => {
      const status = record.status
      const tag = h(Tag, { color: getStatusColor(status) }, () => getStatusText(status))
      // 排队中附队列规模，让用户对等待时间有预期；
      // 失败附任务级失败原因（与展开行的段级原因互补，悬停可看完整内容）
      const extra =
        (status === 'queued' || status === 'pending') && queuedCount.value > 0
          ? h(
              Text,
              { type: 'secondary', style: { fontSize: '12px', display: 'block' } },
              () => `队列约 ${queuedCount.value} 个任务`,
            )
          : status === 'failed' && record.errorMessage
            ? h(
                Tooltip,
                { title: record.errorMessage },
                () =>
                  h(
                    Text,
                    {
                      type: 'danger',
                      style: {
                        fontSize: '12px',
                        display: 'block',
                        maxWidth: '130px',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      },
                    },
                    record.errorMessage as string,
                  ),
              )
            : null
      return h('div', {}, [tag, extra])
    },
  },
  {
    title: '进度',
    key: 'progress',
    width: 220,
    customRender: ({ record }) => {
      const percent =
        record.totalSegments > 0
          ? Math.round((record.completedSegments / record.totalSegments) * 100)
          : 0
      const remain = remainingText(record)
      // 边合成边听：任务进行中且已有完成片段时，显式提示可先试听
      const canListenEarly = isActive(record.status) && record.completedSegments > 0
      return h('div', {}, [
        h(Progress, {
          percent,
          // partial / paused 都不是「成功」：原本两者都落进 success，
          // 6513 / 6517 的进度条与「已完成」同样是一条绿色满格，缺段的事实被视觉抹掉了。
          status:
            record.status === 'failed'
              ? 'exception'
              : record.status === 'partial' || record.status === 'paused'
                ? 'normal'
                : isActive(record.status) ? 'active' : 'success',
        }),
        remain ? h(Text, { type: 'secondary', style: { fontSize: '12px' } }, remain) : null,
        canListenEarly
          ? h(Text, { type: 'success', style: { fontSize: '12px', display: 'block' } }, '已有完成片段，可点「在线听」边合成边听')
          : null,
      ])
    },
  },
  {
    title: '完成片段',
    key: 'segments',
    width: 110,
    customRender: ({ record }) => `${record.completedSegments} / ${record.totalSegments}`,
  },
  {
    title: '创建时间',
    dataIndex: 'createdAt',
    key: 'createdAt',
    width: 170,
    customRender: ({ text }) => formatDateTime(text),
  },
  {
    title: '操作',
    key: 'action',
    width: 300,
    customRender: ({ record }) => {
      const btns: any[] = []
      // 只要有已完成片段即可在线听
      if (record.completedSegments > 0 || record.status === 'completed') {
        btns.push(
          h(Button, {
            size: 'small',
            type: 'link',
            icon: h(PlayCircleOutlined),
            onClick: () => goPlayer(record),
          }, () => '在线听'),
        )
      }
      if (record.status === 'completed' || record.status === 'partial') {
        btns.push(
          h(Button, {
            size: 'small',
            type: 'link',
            icon: h(DownloadOutlined),
            onClick: () => handleDownload(record.taskId),
          }, () => '下载'),
        )
      }
      if (record.status === 'failed' || record.status === 'partial') {
        btns.push(
          h(Button, {
            size: 'small',
            type: 'link',
            icon: h(RedoOutlined),
            onClick: () => handleRetry(record),
          }, () => '重试失败段'),
        )
      }
      // 暂停的任务在用户端**没有任何可用操作**：服务端没有用户级 resume 端点，
      // 它只能由管理端的「继续合成」恢复。原先这里什么都不显示，一行空白会让人
      // 以为是加载失败；如实说明并把出口指出来。
      if (record.status === 'paused') {
        btns.push(h(Text, { type: 'secondary', style: { fontSize: '12px' } }, () => '已暂停，请在管理端继续合成'))
      }
      if (isTerminal(record.status)) {
        btns.push(
          h(Button, {
            size: 'small',
            type: 'link',
            danger: true,
            icon: h(DeleteOutlined),
            onClick: () => handleDelete(record),
          }, () => '删除'),
        )
      }
      // 排队中的任务可取消（尚未开始合成）
      if (record.status === 'queued' || record.status === 'pending') {
        btns.push(
          h(Button, {
            size: 'small',
            type: 'link',
            danger: true,
            onClick: () => handleDelete(record),
          }, () => '取消'),
        )
      }
      return btns.length ? h(Space, {}, () => btns) : null
    },
  },
]

onMounted(() => {
  loadTasks()
  // 轮询兜底：SSE 异常时进度仍能以 30s 粒度推进，不会永久冻结
  pollTimer = window.setInterval(quietPoll, POLL_INTERVAL_MS)
})

onUnmounted(() => {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  for (const id of [...sseClients.keys()]) closeSse(id)
})
</script>

<template>
  <Card>
    <div class="header">
      <Title :level="4">
        <CalendarOutlined /> 任务列表
      </Title>
      <Space>
        <Text type="secondary">处理中/排队中的任务会实时更新进度</Text>
        <Button v-if="failedTasks.length > 0" type="primary" danger @click="handleRetryAll">
          <RedoOutlined /> 重试全部失败（{{ failedTasks.length }}）
        </Button>
        <Button @click="loadTasks">
          <ReloadOutlined /> 刷新
        </Button>
      </Space>
    </div>

    <Table
      :columns="columns"
      :data-source="tasks"
      row-key="taskId"
      :loading="loading"
      :expand-row-by-click="false"
      :scroll="{ x: 1200 }"
      :pagination="{
        pageSize: 10,
        showSizeChanger: true,
        showTotal: (total) => `共 ${total} 条`,
      }"
      @expand="onExpand"
    >
      <template #expandedRowRender="{ record }">
        <div v-if="loadingDetail[record.taskId]" class="detail-loading">加载中...</div>
        <div v-else-if="detailMap[record.taskId]?.length">
          <Text strong style="margin-bottom: 8px; display: block">失败片段（{{ detailMap[record.taskId].length }} 段）</Text>
          <div
            v-for="seg in detailMap[record.taskId]"
            :key="seg.id"
            class="failed-item"
          >
            <Tag>第 {{ seg.segmentNumber }} 段</Tag>
            <span class="failed-text">{{ seg.textContent }}</span>
            <Tooltip :title="seg.errorMessage || '无详细原因'">
              <Text type="danger" class="failed-err">{{ seg.errorMessage || '合成失败' }}</Text>
            </Tooltip>
          </div>
        </div>
        <Empty v-else description="无失败片段" :image-style="{ height: '40px' }" />
      </template>
    </Table>
  </Card>
</template>

<style scoped>
.header {
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-loading {
  padding: 8px;
  color: #999;
}

.failed-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px dashed #f0f0f0;
}

.failed-item:last-child {
  border-bottom: none;
}

.failed-text {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #555;
}

/* 夜间模式：失败片段正文的深灰字在暗色展开区里几乎看不清，调亮 */
.dark .failed-text {
  color: rgba(255, 255, 255, 0.85);
}

/* 分隔虚线在暗色下是一条亮线，与调亮后的 .failed-text 同处一个区块，一并翻转 */
.dark .failed-item {
  border-bottom-color: #303030;
}

.failed-err {
  max-width: 300px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
}
</style>
