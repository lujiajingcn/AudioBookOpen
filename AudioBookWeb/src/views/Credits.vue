<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, h } from 'vue'
import {
  Card,
  Button,
  Tag,
  Space,
  message,
  Spin,
  Typography,
  Alert,
  Select,
  type SelectProps,
} from 'ant-design-vue'
import { DollarOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { isAxiosError } from 'axios'
import QRCode from 'qrcode'
import { useCreditsStore } from '@/stores/credits'
import { creditApi, type PrepayResponse } from '@/services/api'

const { Title, Text, Paragraph } = Typography
const creditsStore = useCreditsStore()

// ---------- 列表 / 选择状态 ----------
const loading = ref(false)
const error = ref('')
const selectedPackageId = ref<number | undefined>(undefined)
const selectedChannel = ref<string>('')

// ---------- 下单后状态 ----------
const prepay = ref<PrepayResponse | null>(null)
const qrCodeUrl = ref<string>('')
const paying = ref(false)
const paidOk = ref(false)

// 轮询订单状态：每 2s 一次；用截止时间而非次数做上限——弱网下单次请求最长 30s
// 超时，按次数计 60 次可能拖到约 30 分钟才提示超时，与「约 2 分钟」的预期严重不符
const POLL_INTERVAL_MS = 2000
const POLL_TIMEOUT_MS = 120000
let pollTimer: number | null = null

// 顶层跳转支付（redirect）会离开当前页面，回跳后需据此恢复支付态并继续轮询
const REDIRECT_ORDER_KEY = 'audiobook:credits:redirectOrder'

const packageOptions = computed<SelectProps['options']>(() =>
  creditsStore.packages.map((p) => ({
    value: p.id,
    label: `${p.name}（${p.totalCredits} 积分${p.bonusCredits ? `，含赠送 ${p.bonusCredits}` : ''}）`,
  })),
)

const channelOptions = computed<SelectProps['options']>(() =>
  creditsStore.channels.map((c) => ({ value: c.channel, label: c.name })),
)

const selectedPackage = computed(() =>
  creditsStore.packages.find((p) => p.id === selectedPackageId.value) || null,
)

// ---------- 数据加载 ----------
const loadData = async () => {
  loading.value = true
  error.value = ''
  try {
    await creditsStore.fetchBalance()
    await Promise.all([
      creditsStore.fetchPackages(),
      creditsStore.getChannels(),
    ])
    // 默认选中第一个套餐与渠道，降低用户操作成本
    if (!selectedPackageId.value && creditsStore.packages.length) {
      selectedPackageId.value = creditsStore.packages[0].id
    }
    if (!selectedChannel.value && creditsStore.channels.length) {
      selectedChannel.value = creditsStore.channels[0].channel
    }
  } catch (e) {
    error.value = '加载充值信息失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

// ---------- 轮询订单状态（mock / qrcode 通用）----------
// 追踪**全部**在跑的 interval，而不只是最后一个。
// 原先 `stopPolling` 只清 `pollTimer` 指向的那一个：一旦发生重入
// （下单 → 切走路由 → 再进来下单），`pollTimer` 已指向新定时器，
// 旧 interval 就再也没人清 ⇒ 它继续每 2s 打 `/payment/orders/{orderNo}`，
// 两个订单同时轮询，余额刷新互相覆盖。
const pollTimers = new Set<number>()
// 组件是否已卸载。**await 之后必须复查**：onUnmounted 里的 stopPolling() 只能
// 清掉定时器，清不掉**在途请求** —— 下单后立刻回退到书城，响应回来后仍会
// 继续执行（弹「支付确认超时」、改余额）。
const isUnmounted = ref(false)
const stopPolling = () => {
  for (const t of pollTimers) clearInterval(t)
  pollTimers.clear()
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const clearRedirectOrder = () => {
  try {
    sessionStorage.removeItem(REDIRECT_ORDER_KEY)
  } catch {
    // 隐私模式禁用 storage 时忽略
  }
}

// 网关回跳（顶层跳转支付后）恢复支付态并继续轮询订单状态，
// 否则回跳后页面停在初始态、永远不弹「充值成功」横幅
const resumeRedirectOrder = () => {
  const raw = sessionStorage.getItem(REDIRECT_ORDER_KEY)
  if (!raw) return
  try {
    const saved = JSON.parse(raw) as { orderNo: string | null; prepay: PrepayResponse }
    if (!saved.orderNo || !saved.prepay) {
      clearRedirectOrder()
      return
    }
    prepay.value = saved.prepay
    paying.value = false
    paidOk.value = false
    startPolling(saved.orderNo)
  } catch {
    // 解析失败：丢弃恢复标记，走正常下单流程
    clearRedirectOrder()
  }
}

// 服务端订单状态只有三种取值（CreditOrder.STATUS_*）：PENDING / PAID / CLOSED
const STATUS_PAID = 'PAID'
const STATUS_CLOSED = 'CLOSED'

const startPolling = (orderNo: string) => {
  stopPolling()
  // 截止时间：本轮轮询的绝对超时上限（不受单次请求耗时影响）
  const deadline = Date.now() + POLL_TIMEOUT_MS
  // 并发锁：上一次请求未完成则跳过本次 tick，避免 async 轮询请求堆积/重叠
  let isRequesting = false
  const timer = window.setInterval(async () => {
    if (isRequesting) return
    if (isUnmounted) { stopPolling(); return }
    isRequesting = true
    try {
      const res = await creditApi.getOrderStatus(orderNo)
      if (isUnmounted) return
      // 跨订单竞态防护：取消旧订单并创建新订单后，旧订单的在途响应会晚于
      // 新订单到达——若直接生效会停掉新订单的轮询/清掉新订单的二维码。
      // 仅当页面仍停留在本订单的支付流程时才处理结果
      if (prepay.value?.orderNo !== orderNo) return
      if (res.data.success && res.data.status === STATUS_PAID) {
        stopPolling()
        await creditsStore.fetchBalance()
        pendingReset()
        paidOk.value = true
        clearRedirectOrder()
        message.success('充值成功，积分已到账')
        return
      }
      // 订单被关闭（超时/取消/支付失败）后再轮询也不会变成 PAID，
      // 必须立即结束，否则用户要空等满 2 分钟才看到反馈。
      if (res.data.success && res.data.status === STATUS_CLOSED) {
        stopPolling()
        pendingReset()
        clearRedirectOrder()
        message.warning('订单已关闭（超时或已取消），请重新下单')
        return
      }
    } catch {
      // 轮询失败不影响主流程，下个周期重试
    } finally {
      isRequesting = false
    }
    // 定时器所有权守卫：取消/新订单接管后 pollTimer 已指向新定时器，旧定时器的
    // 迟到回调若继续执行会 stopPolling 掉新订单的轮询并弹出误导性的超时提示
    if (pollTimer !== timer) return
    if (isUnmounted) return
    if (Date.now() >= deadline) {
      stopPolling()
      message.info('支付确认超时，如已支付请稍后刷新余额')
    }
  }, POLL_INTERVAL_MS)
  pollTimers.add(timer)
  pollTimer = timer
}

// ---------- 下单 ----------
const handleCreateOrder = async () => {
  if (!selectedPackageId.value) {
    message.warning('请选择充值套餐')
    return
  }
  if (!selectedChannel.value) {
    message.warning('请选择支付方式')
    return
  }
  paying.value = true
  prepay.value = null
  qrCodeUrl.value = ''
  paidOk.value = false
  try {
    const res = await creditsStore.createOrder(selectedPackageId.value, selectedChannel.value)
    if (!res.success || !res.orderNo) {
      message.error(res.message || '创建订单失败')
      return
    }
    prepay.value = res

    // 按服务端 payType 分流（与后端 PaymentController 保持一致）
    if (res.payType === 'redirect') {
      // 顶层浏览器跳转：绝不使用 iframe（Web 端无 WebView）
      if (res.payUrl) {
        // 跳转前暂存订单号与下单结果，网关回跳后 onMounted 据此恢复并继续轮询
        try {
          sessionStorage.setItem(REDIRECT_ORDER_KEY, JSON.stringify({ orderNo: res.orderNo, prepay: res }))
        } catch {
          // 隐私模式禁用 storage：跳过恢复，回跳后用户手动刷新余额即可
        }
        window.location.href = res.payUrl
      } else {
        // 支付链接缺失：重置下单状态，否则页面永远停在「正在为您跳转支付页面…」
        pendingReset()
        message.error('支付链接缺失')
      }
      return
    }

    if (res.payType === 'qrcode') {
      // 用 payUrl 生成二维码（qrcode 包已纳入依赖）
      if (res.payUrl) {
        try {
          qrCodeUrl.value = await QRCode.toDataURL(res.payUrl)
        } catch {
          // 二维码生成失败：重置下单状态，让用户可以直接重新下单。
          // 此时 prepay 已赋值、qrCodeUrl 为空，不重置会卡死在「二维码生成中…」
          // 且套餐选择区被隐藏，没有任何重试入口
          stopPolling()
          pendingReset()
          message.error('二维码生成失败，请重试')
          return
        }
      } else {
        // 同样必须重置，否则页面卡在「二维码生成中…」无法重试
        pendingReset()
        message.error('支付链接缺失')
        return
      }
    }

    // mock / qrcode 均轮询订单状态，自动检测完成
    startPolling(res.orderNo)
  } catch (e: unknown) {
    message.error(extractMessage(e) || '创建订单失败，请稍后重试')
  } finally {
    paying.value = false
  }
}

// ---------- mock 支付：手动确认 ----------
const handleConfirmMock = async () => {
  if (!prepay.value?.orderNo) return
  paying.value = true
  try {
    await creditsStore.confirmMock(prepay.value.orderNo!)
    stopPolling()
    pendingReset()
    // pendingReset 不再清 paidOk，这里显式置位，成功提示条才会真正显示出来
    paidOk.value = true
    message.success('充值成功，积分已到账')
  } catch (e: unknown) {
    message.error(extractMessage(e) || '确认支付失败')
  } finally {
    paying.value = false
  }
}

/**
 * 用户主动取消：必须先停轮询，否则定时器会继续跑满 2 分钟，
 * 并在用户已经退出支付流程后弹出「充值成功」这类错误反馈。
 * 同时通知服务端关单，避免留下一堆 PENDING 僵尸订单。
 */
const handleCancel = async () => {
  stopPolling()
  const orderNo = prepay.value?.orderNo
  pendingReset()
  paidOk.value = false
  clearRedirectOrder()
  if (!orderNo) return
  try {
    await creditApi.cancelOrder(orderNo)
  } catch {
    // 关单失败不影响前端流程：服务端有过期自动关单兜底
  }
}

// 重置下单相关中间态，保留列表与余额（不动 paidOk，由调用方决定）
const pendingReset = () => {
  prepay.value = null
  qrCodeUrl.value = ''
}

/** 从 axios 错误里安全取出服务端 message，避免到处写 any */
function extractMessage(e: unknown): string | undefined {
  if (isAxiosError(e)) {
    const data = e.response?.data as { message?: string } | undefined
    return data?.message
  }
  return undefined
}

onMounted(async () => {
  await loadData()
  // 网关回跳后恢复支付态并继续轮询，确保「充值成功」横幅能正常弹出
  resumeRedirectOrder()
})

onUnmounted(() => {
  isUnmounted.value = true // 让在途请求回来后自行短路
  stopPolling()
})
</script>

<template>
  <Card>
    <Title :level="4">
      <DollarOutlined /> 积分充值
    </Title>

    <Spin :spinning="loading">
      <!-- 当前余额 -->
      <Alert
        style="margin-bottom: 16px"
        type="info"
        show-icon
        :message="`当前积分余额：${creditsStore.balance}`"
        :description="creditsStore.enabled ? `每次合成消耗约 ${creditsStore.creditsPerTask} 积分` : '积分功能暂未开启'"
      />

      <Button
        style="margin-bottom: 16px"
        :icon="h(ReloadOutlined)"
        :loading="loading"
        @click="loadData"
      >
        刷新
      </Button>

      <div v-if="error">
        <Alert type="error" show-icon :message="error" />
      </div>

      <!-- 充值成功提示 -->
      <Alert v-if="paidOk" type="success" show-icon message="充值成功，积分已到账" style="margin-bottom: 16px" />

      <!-- 选择区（未进入支付流程时显示） -->
      <Space v-if="!prepay" direction="vertical" style="width: 100%" size="large">
        <div>
          <Text strong>1. 选择充值套餐</Text>
          <Select
            v-model:value="selectedPackageId"
            style="width: 100%; margin-top: 8px"
            placeholder="请选择充值套餐"
            :options="packageOptions"
          />
          <div v-if="selectedPackage" style="margin-top: 8px">
            <Space wrap>
              <Tag color="green">售价 ¥{{ selectedPackage.price.toFixed(2) }}</Tag>
              <Tag color="blue">到账 {{ selectedPackage.totalCredits }} 积分</Tag>
              <Text type="secondary">
                含基础 {{ selectedPackage.credits }} 积分<template v-if="selectedPackage.bonusCredits"> + 赠送 {{ selectedPackage.bonusCredits }}</template>
              </Text>
            </Space>
          </div>
        </div>

        <div>
          <Text strong>2. 选择支付方式</Text>
          <Select
            v-model:value="selectedChannel"
            style="width: 100%; margin-top: 8px"
            placeholder="请选择支付方式"
            :options="channelOptions"
          />
        </div>

        <Button type="primary" size="large" :loading="paying" @click="handleCreateOrder">
          立即充值
        </Button>
      </Space>

      <!-- 支付流程区（下单后显示） -->
      <div v-else-if="prepay" style="margin-top: 16px">
        <Alert
          type="warning"
          show-icon
          :message="`订单已创建：¥${prepay.amount.toFixed(2)} → ${prepay.totalCredits} 积分`"
          :description="prepay.packageName || ''"
          style="margin-bottom: 16px"
        />

        <!-- 二维码支付 -->
        <div v-if="prepay.payType === 'qrcode'" style="text-align: center">
          <Paragraph type="secondary">请使用对应 App 扫描下方二维码完成支付</Paragraph>
          <div v-if="qrCodeUrl" style="margin: 16px 0">
            <img :src="qrCodeUrl" alt="支付二维码" style="width: 240px; height: 240px" />
          </div>
          <Spin v-else :spinning="true" tip="二维码生成中..." />
          <div style="margin-top: 8px">
            <Text type="secondary">支付成功后本页将自动刷新余额</Text>
          </div>
        </div>

        <!-- mock 支付 -->
        <div v-else-if="prepay.payType === 'mock'" style="text-align: center">
          <Paragraph type="secondary">当前为模拟支付环境，点击「确认支付」即可完成充值</Paragraph>
          <Button type="primary" size="large" :loading="paying" @click="handleConfirmMock">
            确认支付
          </Button>
        </div>

        <!-- 兜底（redirect 支付回跳后停留于此，直到轮询确认结果） -->
        <div v-else>
          <Paragraph type="secondary">正在确认支付状态…</Paragraph>
        </div>

        <div style="margin-top: 16px; text-align: center">
          <Button :disabled="paying" @click="handleCancel">取消</Button>
        </div>
      </div>
    </Spin>
  </Card>
</template>

<style scoped>
.pay-qr {
  width: 240px;
  height: 240px;
}
</style>
