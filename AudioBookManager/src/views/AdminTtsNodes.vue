<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, reactive, ref } from 'vue'
import {
  Alert, Button, Card, Input, InputNumber, Modal, Space, Switch,
  Table, Tag, Tooltip, Typography, message,
} from 'ant-design-vue'
import {
  ApiOutlined, DeleteOutlined, EditOutlined, ExclamationCircleOutlined,
  PlusOutlined, PoweroffOutlined,
} from '@ant-design/icons-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import { adminApi, type AdminTtsNode, type AdminTtsNodeListPayload } from '@/services/api'
import { isHandledByInterceptor } from '@/services/apiClient'
import { formatDateTime } from '@audiobook/shared'
import { isAxiosError } from 'axios'

const { Title, Text } = Typography

/**
 * 列表自动刷新周期（毫秒）。
 * 在途数是瞬时指标，不刷新就看不出负载是否真的摊平了。5 秒是「看得见变化」
 * 与「不打扰管理端接口」之间的折中；页面隐藏时自动停刷（见下方 visibilitychange）。
 */
const POLL_INTERVAL_MS = 5000

const nodes = ref<AdminTtsNode[]>([])
const summary = ref<AdminTtsNodeListPayload | null>(null)
const loading = ref(false)
const autoRefresh = ref(true)

const modalOpen = ref(false)
const submitting = ref(false)
/** null 表示新增，非 null 表示编辑该 id */
const editingId = ref<number | null>(null)

const formState = reactive({
  name: '',
  url: '',
  weight: 1,
  sortOrder: 0,
  remark: '',
})

let pollTimer: number | null = null

/** 服务端返回的是 epoch 毫秒，统一走 shared 的格式化，保证与其它页面展示口径一致 */
const formatEpoch = (value?: number | null): string =>
  value ? formatDateTime(new Date(value).toISOString()) : '-'

/** 熔断冷却剩余时长，展示成秒 */
const formatCooldown = (stat: AdminTtsNode): string =>
  stat.coolingDown ? `${Math.ceil(stat.cooldownRemainingMillis / 1000)} 秒后恢复` : '-'

const statusMap: Record<AdminTtsNode['status'], { color: string; text: string; tip: string }> = {
  online: { color: 'success', text: '正常', tip: '正在承接合成任务' },
  idle: { color: 'blue', text: '空闲', tip: '已加入调度，尚未接到任务' },
  cooling: { color: 'warning', text: '熔断冷却', tip: '连续失败已暂时移出调度，冷却结束后自动恢复' },
  unreachable: { color: 'error', text: '不可达', tip: '探活失败（TCP 无法建连），请检查机器与端口' },
  disabled: { color: 'default', text: '已停用', tip: '不参与调度，但保留配置与统计' },
  pending: { color: 'default', text: '同步中', tip: '刚写入数据库，本地快照将在数秒内生效' },
}

const columns: ColumnsType<AdminTtsNode> = [
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    width: 110,
    customRender: ({ record }) => {
      const meta = statusMap[record.status] ?? statusMap.pending
      return h(Tooltip, { title: meta.tip, placement: 'top' }, {
        default: () => h(Tag, { color: meta.color }, () => meta.text),
      })
    },
  },
  {
    title: '名称',
    dataIndex: 'name',
    key: 'name',
    width: 150,
    customRender: ({ text, record }) => {
      const name = h('div', { style: 'font-weight: 500;' }, text || '-')
      if (!record.remark) return name
      return h(Tooltip, { title: record.remark, placement: 'top' }, { default: () => name })
    },
  },
  {
    title: '地址',
    dataIndex: 'url',
    key: 'url',
    ellipsis: true,
    customRender: ({ text }) => h('code', { style: 'font-size: 12px;' }, text),
  },
  {
    title: '权重',
    dataIndex: 'weight',
    key: 'weight',
    width: 70,
    customRender: ({ text }) => text ?? 1,
  },
  {
    title: '在途',
    dataIndex: 'inFlight',
    key: 'inFlight',
    width: 80,
    customRender: ({ text, record }) => {
      // 在途数是选点的直接依据，也是「负载是否真的摊平」的唯一观测点，
      // 故做一点视觉强调：有在途请求时高亮，冷却/不可达时置灰（不会被选中）
      const active = (text as number) > 0
      const usable = record.status === 'online' || record.status === 'idle'
      const color = !usable ? '#999' : active ? '#1677ff' : undefined
      return h('span', { style: color ? `color: ${color}; font-weight: 500;` : '' }, String(text ?? 0))
    },
  },
  {
    title: '调用/失败',
    key: 'stats',
    width: 130,
    customRender: ({ record }) => {
      const total = record.totalRequests ?? 0
      const failed = record.totalFailures ?? 0
      // 进程重启后统计会归零，这里只反映「本进程运行期间」的口径，
      // 用 tooltip 说清楚，避免管理员把它当成历史累计值而误判稳定性
      const text = `${total} / ${failed}`
      const tip = `本进程启动以来：成功调用 ${total - failed} 次，失败 ${failed} 次`
      return h(Tooltip, { title: tip, placement: 'top' }, {
        default: () => h('span', { style: failed > 0 ? 'color: #d4380d;' : '' }, text),
      })
    },
  },
  {
    title: '冷却',
    key: 'cooldown',
    width: 120,
    customRender: ({ record }) => formatCooldown(record),
  },
  {
    title: '最近错误',
    dataIndex: 'lastError',
    key: 'lastError',
    ellipsis: true,
    customRender: ({ text }) => (text
      ? h(Tooltip, { title: text, placement: 'top' }, {
          default: () => h('span', { style: 'color: #cf1322;' }, text),
        })
      : '-'),
  },
  {
    title: '最后成功',
    dataIndex: 'lastSuccessAt',
    key: 'lastSuccessAt',
    width: 170,
    customRender: ({ text }) => formatEpoch(text as number | null),
  },
  {
    title: '操作',
    key: 'action',
    width: 210,
    fixed: 'right',
    customRender: ({ record }) => h(Space, { size: 4 }, () => [
      h(Button, {
        size: 'small',
        type: 'link',
        onClick: () => openEdit(record),
      }, () => [h(EditOutlined), ' 编辑']),
      h(Button, {
        size: 'small',
        type: 'link',
        onClick: () => toggleEnabled(record),
      }, () => [h(PoweroffOutlined), record.enabled ? ' 停用' : ' 启用']),
      h(Button, {
        size: 'small',
        type: 'link',
        danger: true,
        onClick: () => confirmDelete(record),
      }, () => [h(DeleteOutlined), ' 删除']),
    ]),
  },
]

const schedulableLabel = computed(() => {
  const data = summary.value
  if (!data) return ''
  return `参与调度 ${data.schedulableCount} / 启用 ${data.enabledCount} / 共 ${data.total} 台`
})

const loadNodes = async (silent = false) => {
  if (!silent) loading.value = true
  try {
    const response = await adminApi.getTtsNodes()
    if (response.data.success) {
      // 载荷字段与 success/message 平铺在同一层（ApiResult<T> = { success, message? } & T），
      // 不存在嵌套的 data 字段
      summary.value = response.data
      nodes.value = response.data.nodes ?? []
    } else if (!silent) {
      message.error(response.data.message || '加载节点列表失败')
    }
  } catch (error: unknown) {
    // 自动轮询静默失败：网络抖动时不要每 5 秒弹一次错误打断管理员操作，
    // 表格里的数据保持上一次结果，下一次轮询若能恢复则自动接上
    if (!silent && !isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '加载节点列表失败')
    }
  } finally {
    if (!silent) loading.value = false
  }
}

const resetForm = () => {
  formState.name = ''
  formState.url = ''
  formState.weight = 1
  formState.sortOrder = 0
  formState.remark = ''
}

const openCreate = () => {
  editingId.value = null
  resetForm()
  // 新增时按当前节点数给一个排在最后的序号，避免新节点插队改变既有展示顺序
  formState.sortOrder = nodes.value.length
  modalOpen.value = true
}

const openEdit = (node: AdminTtsNode) => {
  editingId.value = node.id
  formState.name = node.name
  formState.url = node.url
  formState.weight = node.weight ?? 1
  formState.sortOrder = node.sortOrder ?? 0
  formState.remark = node.remark ?? ''
  modalOpen.value = true
}

const submitForm = async () => {
  const name = formState.name.trim()
  const url = formState.url.trim()
  if (!name) {
    message.warning('请填写节点名称')
    return
  }
  if (!/^https?:\/\/[^\s/]+/i.test(url)) {
    message.warning('节点地址需形如 http://10.0.0.11:18083/api/generate')
    return
  }

  submitting.value = true
  try {
    const payload = {
      name,
      url,
      weight: formState.weight,
      sortOrder: formState.sortOrder,
      remark: formState.remark.trim(),
    }
    const response = editingId.value == null
      ? await adminApi.createTtsNode(payload)
      : await adminApi.updateTtsNode(editingId.value, payload)

    if (response.data.success) {
      message.success(response.data.message || '已保存')
      modalOpen.value = false
      await loadNodes()
    } else {
      message.error(response.data.message || '保存失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '保存失败')
    }
  } finally {
    submitting.value = false
  }
}

const toggleEnabled = async (node: AdminTtsNode) => {
  const next = !node.enabled
  try {
    const response = await adminApi.setTtsNodeEnabled(node.id, next)
    if (response.data.success) {
      message.success(response.data.message || '操作成功')
      await loadNodes()
    } else {
      message.error(response.data.message || '操作失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '操作失败')
    }
  }
}

const confirmDelete = (node: AdminTtsNode) => {
  Modal.confirm({
    title: '确认删除节点',
    icon: () => h(ExclamationCircleOutlined),
    content: `确定要删除「${node.name}」(${node.url}) 吗？`
      + '删除后该节点的历史调用统计会一并消失。'
      + '如果只是要下线这台机器做维护，建议改用「停用」—— 停用会立即停止分配新任务，'
      + '且不会打断正在进行的合成。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const response = await adminApi.deleteTtsNode(node.id)
        if (response.data.success) {
          message.success('节点已删除')
          await loadNodes()
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

const startPolling = () => {
  stopPolling()
  pollTimer = window.setInterval(() => {
    // 页面在后台时不必刷：省掉无意义的请求，回到前台会立刻手动拉一次
    if (autoRefresh.value && document.visibilityState === 'visible') {
      loadNodes(true)
    }
  }, POLL_INTERVAL_MS)
}

const stopPolling = () => {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

const handleVisibilityChange = () => {
  if (document.visibilityState === 'visible' && autoRefresh.value) {
    loadNodes(true)
  }
}

onMounted(() => {
  loadNodes()
  startPolling()
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onUnmounted(() => {
  stopPolling()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})
</script>

<template>
  <Card>
    <div class="header">
      <Title :level="4">
        <ApiOutlined /> 合成节点管理
      </Title>
      <Space>
        <span class="auto-refresh">
          <Switch v-model:checked="autoRefresh" size="small" /> 自动刷新
        </span>
        <Button :loading="loading" @click="loadNodes()">
          刷新
        </Button>
        <Button type="primary" @click="openCreate">
          <PlusOutlined /> 添加节点
        </Button>
      </Space>
    </div>

    <Alert
      v-if="summary?.concurrencyHint"
      type="warning"
      show-icon
      class="hint"
      message="节点数与并发上限不匹配"
      :description="summary.concurrencyHint"
    />

    <Alert
      type="info"
      show-icon
      class="hint"
      message="增删节点即时生效，无需重启服务"
      description="合成任务按「每段一次」在多台机器间调度：优先选择当前在途请求数最少的节点。
        停用一台机器不会打断它正在进行的合成，只会停止分配新任务。
        进程重启后调用统计会归零，因此「调用/失败」反映的是本次运行期间的情况。"
    />

    <div v-if="summary" class="summary-line">
      <Text type="secondary">{{ schedulableLabel }}</Text>
      <Text type="secondary"> · 合成并发上限 {{ summary.ttsConcurrency }}</Text>
      <Text type="secondary"> · 主动探活 {{ summary.healthCheckEnabled ? '已开启' : '已关闭' }}</Text>
    </div>

    <Table
      row-key="id"
      :columns="columns"
      :data-source="nodes"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: 1300 }"
      size="middle"
    />

    <Modal
      v-model:open="modalOpen"
      :title="editingId == null ? '添加合成节点' : '编辑合成节点'"
      :confirm-loading="submitting"
      ok-text="保存"
      cancel-text="取消"
      @ok="submitForm"
    >
      <div class="form-row">
        <label>节点名称</label>
        <Input v-model:value="formState.name" placeholder="例如：GPU-01 / 机房A-3090" :maxlength="64" />
      </div>
      <div class="form-row">
        <label>合成地址</label>
        <Input
          v-model:value="formState.url"
          placeholder="http://10.0.0.11:18083/api/generate"
          :maxlength="255"
        />
        <div class="form-tip">
          需填写完整的合成端点（含 /api/generate 路径），与改造前 moss.tts.url 的写法一致。
        </div>
      </div>
      <div class="form-row form-row-inline">
        <div class="form-col">
          <label>权重</label>
          <InputNumber v-model:value="formState.weight" :min="1" :max="100" style="width: 100%" />
          <div class="form-tip">仅在多台机器「在途数相同」时生效，越大越优先。</div>
        </div>
        <div class="form-col">
          <label>排序</label>
          <InputNumber v-model:value="formState.sortOrder" :min="0" :max="9999" style="width: 100%" />
          <div class="form-tip">仅影响本页展示顺序，不影响调度算法。</div>
        </div>
      </div>
      <div class="form-row">
        <label>备注</label>
        <Input v-model:value="formState.remark" placeholder="可选，例如机房位置、显卡型号" :maxlength="255" />
      </div>
    </Modal>
  </Card>
</template>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.auto-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #666;
}

.hint {
  margin-bottom: 12px;
}

.summary-line {
  margin-bottom: 12px;
}

.form-row {
  margin-bottom: 14px;
}

.form-row label {
  display: block;
  margin-bottom: 6px;
  font-weight: 500;
}

.form-row-inline {
  display: flex;
  gap: 16px;
}

.form-col {
  flex: 1;
}

.form-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #999;
  line-height: 1.5;
}
</style>
