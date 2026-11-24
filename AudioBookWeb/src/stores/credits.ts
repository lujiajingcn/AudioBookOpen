import { defineStore } from 'pinia'
import { ref } from 'vue'
import { creditApi, type CreditPackage, type PaymentChannel, type PrepayResponse } from '@/services/api'

export const useCreditsStore = defineStore('credits', () => {
  // ---------- 状态 ----------
  const balance = ref<number>(0)
  const enabled = ref<boolean>(false)
  const creditsPerTask = ref<number>(0)
  const refundOnTaskFailure = ref<boolean>(false)
  const enoughForOneTask = ref<boolean>(false)

  // 便捷查询用的只读缓存（列表类数据，按需刷新）
  const packages = ref<CreditPackage[]>([])
  const channels = ref<PaymentChannel[]>([])

  // ---------- 余额 / 配置 ----------
  async function fetchBalance() {
    try {
      const res = await creditApi.getMyCredits()
      if (res.data.success) {
        balance.value = res.data.balance
        enabled.value = res.data.enabled
        creditsPerTask.value = res.data.creditsPerTask
        refundOnTaskFailure.value = res.data.refundOnTaskFailure
        enoughForOneTask.value = res.data.enoughForOneTask
      }
    } catch (e) {
      // 接口失败时保留默认值，不阻断页面（余额错误不应让合成/书城等页面崩溃）
      console.error('[credits] fetchBalance failed', e)
    }
  }

  /** 顶部/充值页统一入口：刷新余额 */
  async function refresh() {
    await fetchBalance()
  }

  // ---------- 充值相关（供 Credits.vue 调用）----------

  async function fetchPackages() {
    const res = await creditApi.getPackages()
    if (res.data.success) {
      packages.value = res.data.packages || []
    }
    return packages.value
  }

  async function getChannels() {
    const res = await creditApi.getChannels()
    if (res.data.success) {
      channels.value = res.data.channels || []
    }
    return channels.value
  }

  /** 创建预付订单 → PrepayResponse（含 payType / payUrl / orderNo 等） */
  async function createOrder(packageId: number, channel: string): Promise<PrepayResponse> {
    const res = await creditApi.createOrder(packageId, channel)
    return res.data
  }

  /** mock 支付方式：确认支付 */
  async function confirmMock(orderNo: string) {
    await creditApi.confirmMock(orderNo)
    await fetchBalance()
  }

  return {
    balance,
    enabled,
    creditsPerTask,
    refundOnTaskFailure,
    enoughForOneTask,
    packages,
    channels,
    fetchBalance,
    refresh,
    fetchPackages,
    getChannels,
    createOrder,
    confirmMock,
  }
})
