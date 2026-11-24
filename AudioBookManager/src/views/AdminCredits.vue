<script setup lang="ts">
import { ref, onMounted, h, reactive } from 'vue'
import {
  Table,
  Button,
  Tag,
  Space,
  Modal,
  Input,
  InputNumber,
  Select,
  Switch,
  Form,
  message,
  Card,
  Typography,
} from 'ant-design-vue'
import {
  DollarOutlined,
  DeleteOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  ReloadOutlined,
  PlusOutlined,
} from '@ant-design/icons-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import type { TablePaginationConfig } from 'ant-design-vue'
import { creditAdminApi, type CreditPackage, type CreditTransaction } from '@/services/api'
import { isHandledByInterceptor } from '@/services/apiClient'
import { formatDateTime, formatCredits } from '@audiobook/shared'
import { isAxiosError } from 'axios'

const { Title } = Typography

// ---------------- 充值套餐 ----------------
const packages = ref<CreditPackage[]>([])
const packagesLoading = ref(false)

const showPackageModal = ref(false)
const packageSaving = ref(false)
const editingPackageId = ref<number | null>(null)
const packageForm = reactive<{
  name: string
  // 空值用 undefined（InputNumber 的 ValueType 不接受 null，null 会触发 vue-tsc 报错）
  credits: number | undefined
  bonusCredits: number
  price: number | undefined
  description: string
  enabled: boolean
  sortOrder: number
}>({
  name: '',
  credits: undefined,
  bonusCredits: 0,
  price: undefined,
  description: '',
  enabled: true,
  sortOrder: 0,
})

const packageColumns: ColumnsType<CreditPackage> = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
  { title: '套餐名称', dataIndex: 'name', key: 'name' },
  {
    title: '积分',
    dataIndex: 'credits',
    key: 'credits',
    width: 110,
    customRender: ({ text }) => formatCredits(text),
  },
  {
    title: '赠送积分',
    dataIndex: 'bonusCredits',
    key: 'bonusCredits',
    width: 110,
    customRender: ({ text }) => formatCredits(text ?? 0),
  },
  {
    title: '到账积分',
    dataIndex: 'totalCredits',
    key: 'totalCredits',
    width: 110,
    customRender: ({ record }) =>
      formatCredits((record.credits ?? 0) + (record.bonusCredits ?? 0)),
  },
  {
    title: '价格',
    dataIndex: 'price',
    key: 'price',
    width: 110,
    customRender: ({ text }) => (text != null ? `¥${Number(text).toFixed(2)}` : '-'),
  },
  {
    title: '状态',
    dataIndex: 'enabled',
    key: 'enabled',
    width: 90,
    customRender: ({ text }) =>
      h(Tag, { color: text ? 'green' : 'default' }, () => (text ? '已上架' : '已下架')),
  },
  {
    title: '操作',
    key: 'action',
    width: 160,
    fixed: 'right',
    customRender: ({ record }) =>
      h(Space, { size: 'small' }, {
        default: () => [
          h(Button, { size: 'small', icon: h(EditOutlined), onClick: () => openEditPackage(record) }, () => '编辑'),
          h(Button, { size: 'small', danger: true, icon: h(DeleteOutlined), onClick: () => confirmDeletePackage(record) }, () => '删除'),
        ],
      }),
  },
]

const loadPackages = async () => {
  try {
    packagesLoading.value = true
    const res = await creditAdminApi.listPackages()
    if (res.data.success) {
      packages.value = res.data.packages ?? []
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '加载套餐列表失败')
    }
  } finally {
    packagesLoading.value = false
  }
}

const openCreatePackage = () => {
  editingPackageId.value = null
  packageForm.name = ''
  packageForm.credits = undefined
  packageForm.bonusCredits = 0
  packageForm.price = undefined
  packageForm.description = ''
  packageForm.enabled = true
  packageForm.sortOrder = 0
  showPackageModal.value = true
}

const openEditPackage = (pkg: CreditPackage) => {
  editingPackageId.value = pkg.id
  packageForm.name = pkg.name
  packageForm.credits = pkg.credits ?? undefined
  packageForm.bonusCredits = pkg.bonusCredits ?? 0
  packageForm.price = pkg.price
  packageForm.description = pkg.description || ''
  packageForm.enabled = pkg.enabled !== false
  packageForm.sortOrder = pkg.sortOrder ?? 0
  showPackageModal.value = true
}

const savePackage = async () => {
  if (!packageForm.name.trim()) {
    message.warning('套餐名称不能为空')
    return
  }
  // InputNumber 未限制 precision，用户可输入 1.5 直接提交——文案承诺「整数」就要真校验
  if (packageForm.credits == null || packageForm.credits < 0 || !Number.isInteger(packageForm.credits)) {
    message.warning('积分数必须为非负整数')
    return
  }
  if (!Number.isInteger(packageForm.bonusCredits) || packageForm.bonusCredits < 0) {
    message.warning('赠送积分必须为非负整数')
    return
  }
  if (!Number.isInteger(packageForm.sortOrder) || packageForm.sortOrder < 0) {
    message.warning('排序必须为非负整数')
    return
  }
  if (packageForm.price == null || packageForm.price < 0) {
    message.warning('价格必须为非负数')
    return
  }
  packageSaving.value = true
  const payload = {
    name: packageForm.name.trim(),
    credits: Number(packageForm.credits),
    bonusCredits: Number(packageForm.bonusCredits || 0),
    price: Number(packageForm.price),
    description: packageForm.description.trim(),
    enabled: packageForm.enabled,
    sortOrder: Number(packageForm.sortOrder || 0),
  }
  try {
    const res = editingPackageId.value
      ? await creditAdminApi.updatePackage(editingPackageId.value, payload)
      : await creditAdminApi.createPackage(payload)
    if (res.data.success) {
      message.success(editingPackageId.value ? '套餐已更新' : '套餐已创建')
      showPackageModal.value = false
      loadPackages()
    } else {
      message.error(res.data.message || '保存失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '保存失败')
    }
  } finally {
    packageSaving.value = false
  }
}

const confirmDeletePackage = (pkg: CreditPackage) => {
  Modal.confirm({
    title: '确认删除',
    icon: () => h(ExclamationCircleOutlined),
    content: `确定要删除套餐「${pkg.name}」吗？此操作不可恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await creditAdminApi.deletePackage(pkg.id)
        if (res.data.success) {
          message.success('套餐已删除')
          loadPackages()
        } else {
          message.error(res.data.message || '删除失败')
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

// ---------------- 积分流水 ----------------
const transactions = ref<CreditTransaction[]>([])
const txLoading = ref(false)
const txTotal = ref(0)
const txPage = ref(0)
const txSize = ref(20)
const txType = ref('')

// 流水类型筛选选项：取值必须与服务端 CreditTransaction.TYPE_* 完全一致，
// 用固定选项替代自由文本，避免拼写错误导致静默空结果
const TX_TYPE_OPTIONS = [
  { value: 'RECHARGE', label: '充值到账' },
  { value: 'CONSUME', label: '任务消耗' },
  { value: 'REFUND', label: '失败退还' },
  { value: 'ADMIN_ADJUST', label: '手动调整' },
  { value: 'REGISTER_BONUS', label: '注册赠送' },
]

const txColumns: ColumnsType<CreditTransaction> = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
  { title: '用户', dataIndex: 'username', key: 'username', customRender: ({ text }) => text || '-' },
  {
    title: '类型',
    dataIndex: 'type',
    key: 'type',
    width: 120,
    customRender: ({ text }) => {
      // key 必须与服务端 CreditTransaction.TYPE_* 常量完全一致，
      // 取值只有：RECHARGE / CONSUME / REFUND / ADMIN_ADJUST / REGISTER_BONUS。
      // 之前用的 TASK_CONSUME / ADJUST_ADD / ADJUST_DEDUCT / REGISTER_GIFT 均不存在，
      // 会导致「任务消耗」「手动调整」「注册赠送」三类流水直接显示成原始枚举字符串。
      const map: Record<string, { color: string; label: string }> = {
        RECHARGE: { color: 'green', label: '充值到账' },
        CONSUME: { color: 'red', label: '任务消耗' },
        REFUND: { color: 'purple', label: '失败退还' },
        ADMIN_ADJUST: { color: 'blue', label: '手动调整' },
        REGISTER_BONUS: { color: 'cyan', label: '注册赠送' },
      }
      const m = map[String(text)] || { color: 'default', label: String(text || '-') }
      return h(Tag, { color: m.color }, () => m.label)
    },
  },
  {
    title: '积分变动',
    dataIndex: 'credits',
    key: 'credits',
    width: 110,
    customRender: ({ text }) => formatCredits(text),
  },
  {
    title: '变动后余额',
    dataIndex: 'balanceAfter',
    key: 'balanceAfter',
    width: 130,
    customRender: ({ text }) => formatCredits(text),
  },
  { title: '备注', dataIndex: 'remark', key: 'remark', customRender: ({ text }) => text || '-' },
  { title: '操作人', dataIndex: 'operator', key: 'operator', width: 120, customRender: ({ text }) => text || '-' },
  {
    title: '时间',
    dataIndex: 'createdAt',
    key: 'createdAt',
    width: 200,
    customRender: ({ text }) => formatDateTime(text),
  },
]

// 流水请求序号：搜索（回车+按钮）与分页切换会快速连续触发 loadTransactions，
// 慢的旧响应可能后到覆盖新结果，导致列表与 txPage/txTotal 错位——只有最新请求可写入
let txReqSeq = 0

const loadTransactions = async () => {
  const seq = ++txReqSeq
  try {
    txLoading.value = true
    const res = await creditAdminApi.listTransactions(
      txPage.value,
      txSize.value,
      undefined,
      txType.value || undefined,
    )
    if (seq !== txReqSeq) return // 已有更新的请求发出，丢弃本次过期响应
    if (res.data.success) {
      transactions.value = res.data.content ?? []
      txTotal.value = res.data.totalElements ?? transactions.value.length
    }
  } catch (error: unknown) {
    if (seq !== txReqSeq) return
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '加载流水失败')
    }
  } finally {
    if (seq === txReqSeq) {
      txLoading.value = false
    }
  }
}

const handleTxChange = (pagination: TablePaginationConfig) => {
  if (pagination.pageSize !== txSize.value) {
    txPage.value = 0
  } else {
    txPage.value = (pagination.current ?? 1) - 1
  }
  // 回退值用txSize.value 本身（保持原值），不要硬编码 10：
  // 初始化是 ref(20)，硬编码会让某些 antd 版本下 pageSize 传回 undefined 时
  // 页大小静默从 20 跳到 10，而分页组件仍按 txSize 渲染 ⇒ 列表条数与页数对不上。
  txSize.value = pagination.pageSize ?? txSize.value
  loadTransactions()
}

// 过滤条件提交（回车/查询按钮）时重置到第一页，避免与分页错位
const handleTxSearch = () => {
  txPage.value = 0
  loadTransactions()
}

onMounted(() => {
  loadPackages()
  loadTransactions()
})
</script>

<template>
  <div class="credits-page">
    <Card>
      <div class="header">
        <Title :level="4">
          <DollarOutlined /> 充值套餐
        </Title>
        <Space>
          <Button @click="loadPackages">
            <ReloadOutlined /> 刷新
          </Button>
          <Button type="primary" @click="openCreatePackage">
            <PlusOutlined /> 新增套餐
          </Button>
        </Space>
      </div>

      <Table
        :columns="packageColumns"
        :data-source="packages"
        row-key="id"
        :loading="packagesLoading"
        :pagination="false"
      />
    </Card>

    <Card style="margin-top: 16px;">
      <div class="header">
        <Title :level="4">
          积分流水
        </Title>
        <Space>
          <Select
            v-model:value="txType"
            placeholder="按类型筛选"
            allow-clear
            style="width: 220px"
            :options="TX_TYPE_OPTIONS"
            @change="handleTxSearch"
          />
          <Button @click="handleTxSearch">
            查询
          </Button>
        </Space>
      </div>

      <Table
        :columns="txColumns"
        :data-source="transactions"
        row-key="id"
        :loading="txLoading"
        :pagination="{
          current: txPage + 1,
          pageSize: txSize,
          total: txTotal,
          showSizeChanger: true,
          showTotal: (total: number) => `共 ${total} 条`,
        }"
        @change="handleTxChange"
      />
    </Card>

    <Modal
      v-model:open="showPackageModal"
      :title="editingPackageId ? '编辑套餐' : '新增套餐'"
      :ok-text="'保存'"
      :cancel-text="'取消'"
      :confirm-loading="packageSaving"
      @ok="savePackage"
      width="520px"
    >
      <Form layout="vertical">
        <Form.Item label="套餐名称 *" required>
          <Input v-model:value="packageForm.name" placeholder="如：基础包" />
        </Form.Item>
        <Form.Item label="积分数 *" required>
          <InputNumber v-model:value="packageForm.credits" :min="0" :precision="0" style="width: 100%" />
        </Form.Item>
        <Form.Item label="赠送积分">
          <InputNumber v-model:value="packageForm.bonusCredits" :min="0" :precision="0" style="width: 100%" />
        </Form.Item>
        <Form.Item label="价格（元）*" required>
          <InputNumber v-model:value="packageForm.price" :min="0" :step="0.01" :precision="2" style="width: 100%" />
        </Form.Item>
        <Form.Item label="排序（越小越靠前）">
          <InputNumber v-model:value="packageForm.sortOrder" :min="0" :precision="0" style="width: 100%" />
        </Form.Item>
        <Form.Item label="是否上架">
          <Switch v-model:checked="packageForm.enabled" />
        </Form.Item>
        <Form.Item label="描述">
          <Input.TextArea v-model:value="packageForm.description" :rows="2" placeholder="套餐说明（可选）" />
        </Form.Item>
      </Form>
    </Modal>
  </div>
</template>

<style scoped>
.header {
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
