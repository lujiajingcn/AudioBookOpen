<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue'
import {
  Table,
  Button,
  Tag,
  Space,
  Modal,
  Input,
  InputNumber,
  FormItem,
  Tooltip,
  message,
  Card,
  Typography,
} from 'ant-design-vue'
import { UserOutlined, DeleteOutlined, ExclamationCircleOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import type { ColumnsType } from 'ant-design-vue/es/table'
import { adminApi, creditAdminApi, authApi, type AdminUser } from '@/services/api'
import { isHandledByInterceptor } from '@/services/apiClient'
import { formatDateTime, formatCredits } from '@audiobook/shared'
import { useAuthStore } from '@/stores/auth'
import { isAxiosError } from 'axios'

const { Title } = Typography
const authStore = useAuthStore()

const users = ref<AdminUser[]>([])
const loading = ref(false)
const totalElements = ref(0)
const currentPage = ref(0)
const pageSize = ref(20)

// 当前登录管理员用户名，用于"自我操作"提示
// 必须是 computed 而不是 setup 期的一次性快照：
// 同一浏览器可在 A 标签页登录甲账号、B 标签页登录乙账号（Cookie 按端隔离但同端共享）。
// 快照会导致 A 标签页拿旧用户名判「不是自己」→ 管理员**摘掉自己的权限时
// 不弹自我操作警告**，刷新后才发现已被踢出。
const selfUsername = computed(() => authStore.username)

const showPasswordModal = ref(false)
const passwordTargetUser = ref<AdminUser | null>(null)
const newPassword = ref('')
const confirmPassword = ref('')
const pwdSaving = ref(false)

const showBanModal = ref(false)
const banTargetUser = ref<AdminUser | null>(null)
const banDuration = ref(60)
const banSaving = ref(false)

const showAdjustModal = ref(false)
const adjustTargetUser = ref<AdminUser | null>(null)
const adjustDelta = ref<number>(0)
const adjustRemark = ref('')
const adjustSaving = ref(false)

// 调积分 Modal 的边界限制与余额预览。
// 注：服务端 CreditService.adminAdjust 已有完整防御（parseInt 拦畸形输入 + delta==0 拒绝 +
// after<0 拒绝 + 行锁），前端校验仅 UX 价值，不构成资损防线缺失。
// 单次调整上下限：与 Server parseInt 保持一致（int 范围 / 100），避免管理员误输 1e100
// 等极端值被前端 Server 兜底时弹出"缺少参数 delta"那种"我明明填了"的困惑文案。
const ADJUST_MAX = 1_000_000
const ADJUST_MIN = -1_000_000

// 余额预览：调整后预计余额 = 当前余额 + delta。负数时标红警告。
// 与 Server adminAdjust 校验文案对齐："扣减后余额不能为负数"。
const previewBalance = computed(() => {
  const u = adjustTargetUser.value
  if (!u) return null
  const current = typeof u.credits === 'number' ? u.credits : 0
  return current + (adjustDelta.value || 0)
})
const previewWillBeNegative = computed(() => previewBalance.value !== null && previewBalance.value < 0)

const openAdjustModal = (user: AdminUser) => {
  adjustTargetUser.value = user
  adjustDelta.value = 0
  adjustRemark.value = ''
  showAdjustModal.value = true
}

const doAdjust = async () => {
  if (!adjustTargetUser.value) return
  if (adjustDelta.value === 0 || adjustDelta.value == null) {
    message.warning('调整积分不能为 0')
    return
  }
  // 余额非负预检：与 Server adminAdjust 校验对齐，提前给管理员可见反馈；
  // Server 是最终防线，这里只避免把明显错误的请求送过去。
  if (previewWillBeNegative.value) {
    message.warning(`扣减后余额将为 ${previewBalance.value}（不能为负），请减少扣减额度`)
    return
  }
  try {
    adjustSaving.value = true
    const res = await creditAdminApi.adjustUser(adjustTargetUser.value.id, adjustDelta.value, adjustRemark.value || undefined)
    if (res.data.success) {
      message.success(`${res.data.message}（当前余额 ${res.data.balance}）`)
      showAdjustModal.value = false
      loadUsers()
    } else {
      message.error(res.data.message || '调整失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '调整失败')
    }
  } finally {
    adjustSaving.value = false
  }
}

const columns: ColumnsType<AdminUser> = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
  { title: '用户名', dataIndex: 'username', key: 'username' },
  {
    title: '邮箱',
    dataIndex: 'email',
    key: 'email',
    customRender: ({ text }) => text || '-',
  },
  {
    title: '角色',
    dataIndex: 'isAdmin',
    key: 'isAdmin',
    width: 100,
    customRender: ({ text }) => h(Tag, { color: text ? 'red' : 'blue' }, () => (text ? '管理员' : '普通用户')),
  },
  {
    title: '封禁状态',
    dataIndex: 'bannedUntil',
    key: 'bannedUntil',
    width: 210,
    customRender: ({ text }) => {
      if (text) {
        return h(Tag, { color: 'red' }, () => `封禁至 ${formatDateTime(text)}`)
      }
      return h(Tag, { color: 'green' }, () => '正常')
    },
  },
  {
    title: '积分',
    dataIndex: 'credits',
    key: 'credits',
    width: 100,
    customRender: ({ text }) => formatCredits(text ?? 0),
  },
  {
    title: '注册时间',
    dataIndex: 'createdAt',
    key: 'createdAt',
    width: 200,
    customRender: ({ text }) => formatDateTime(text),
  },
  {
    title: '操作',
    key: 'action',
    width: 340,
    fixed: 'right',
    customRender: ({ record }) => {
      const user = record as AdminUser
      const children: ReturnType<typeof h>[] = [
        h(Button, {
          size: 'small',
          type: user.isAdmin ? 'default' : 'primary',
          danger: !user.isAdmin,
          onClick: () => toggleAdmin(user),
        }, () => (user.isAdmin ? '取消管理员' : '设为管理员')),
      ]

      // 管理员账号不可被封禁 / 删除
      if (user.isAdmin) {
        children.push(
          h(Tooltip, { title: '管理员不可操作' }, () =>
            h('span', {}, [
              h(Button, { size: 'small', danger: true, disabled: true }, () => '封禁'),
            ]),
          ),
          h(Tooltip, { title: '管理员不可操作' }, () =>
            h('span', {}, [
              h(Button, { size: 'small', danger: true, disabled: true, icon: h(DeleteOutlined) }, () => '删除'),
            ]),
          ),
        )
      } else {
        children.push(
          user.bannedUntil
            ? h(Button, { size: 'small', type: 'link', onClick: () => unban(user) }, () => '解封')
            : h(Button, { size: 'small', danger: true, onClick: () => openBanModal(user) }, () => '封禁'),
          h(Button, { size: 'small', type: 'link', onClick: () => openPasswordModal(user) }, () => '重置密码'),
          // 普通用户可删除：二次确认 + 后端清盘逻辑（任务/音频/参考音频）在
          // AdminService.deleteUser 中完成。
          h(Button, { size: 'small', danger: true, icon: h(DeleteOutlined), onClick: () => confirmDelete(user) }, () => '删除'),
        )
      }

      children.push(
        h(Button, { size: 'small', type: 'link', onClick: () => openAdjustModal(user) }, () => '调整积分'),
      )

      return h(Space, { size: 'small' }, { default: () => children })
    },
  },
]

const loadUsers = async () => {
  try {
    loading.value = true
    const response = await adminApi.getUsers(currentPage.value, pageSize.value)
    if (response.data.success) {
      users.value = response.data.content ?? []
      totalElements.value = response.data.totalElements ?? users.value.length
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '加载用户列表失败')
    }
  } finally {
    loading.value = false
  }
}

// antd 的 TablePaginationConfig 里 current/pageSize 都是可选的，
// 直接 pagination.current - 1 在字段缺失时会算出 NaN 并被带进请求参数
const handleTableChange = (pagination: { current?: number; pageSize?: number }) => {
  const newSize = pagination.pageSize ?? pageSize.value
  const newCurrent = pagination.current ?? 1
  // 页大小变化时重置到第 1 页（0 基），避免越界或空页
  if (newSize !== pageSize.value) {
    currentPage.value = 0
  } else {
    currentPage.value = Math.max(0, newCurrent - 1)
  }
  pageSize.value = newSize
  loadUsers()
}

const toggleAdmin = (user: AdminUser) => {
  const newAdmin = !user.isAdmin
  const isSelf = user.username === selfUsername.value
  Modal.confirm({
    title: newAdmin ? '设为管理员' : '取消管理员',
    icon: () => h(ExclamationCircleOutlined),
    content:
      (isSelf && user.isAdmin
        ? '⚠️ 注意：这是您当前登录的账号，取消自己的管理员权限后将无法再通过后台管理本系统。\n'
        : '') +
      (newAdmin
        ? `确定将「${user.username}」设为管理员吗？该用户将获得用户管理、书籍管理等全部管理权限。`
        : `确定取消「${user.username}」的管理员权限吗？`),
    okText: newAdmin ? '设为管理员' : '取消管理员',
    okType: newAdmin ? 'default' : 'danger',
    cancelText: '再想想',
    onOk: async () => {
      try {
        const response = await adminApi.setUserAdmin(user.id, newAdmin)
        if (response.data.success) {
          message.success(newAdmin ? '已设为管理员' : '已取消管理员')
          loadUsers()
          // 自我角色变更后，让客户端路由守卫/菜单以服务端最新角色为准：
          // 重新校验 token 并用回传的 isAdmin 刷新本地标记（对应 bootstrap.ts 的同步逻辑）。
          if (isSelf) {
            try {
              const vres = await authApi.validateToken()
              authStore.markVerified(vres.data.isAdmin)
            } catch {
              // 校验失败不阻断主流程：业务请求的 401 拦截器会自然纠正登录态
            }
          }
        } else {
          message.error(response.data.message || '操作失败')
        }
      } catch (error: unknown) {
        if (!isHandledByInterceptor(error)) {
          const msg = isAxiosError(error) ? error.response?.data?.message : null
          message.error(msg || '操作失败')
        }
      }
    },
  })
}

const openBanModal = (user: AdminUser) => {
  banTargetUser.value = user
  banDuration.value = 60
  showBanModal.value = true
}

const doBan = async () => {
  if (banDuration.value < 1) {
    message.warning('封禁时长至少为 1 分钟')
    return
  }
  if (!banTargetUser.value) return
  try {
    banSaving.value = true
    const response = await adminApi.banUser(banTargetUser.value.id, banDuration.value)
    if (response.data.success) {
      message.success(`用户已被封禁 ${banDuration.value} 分钟`)
      showBanModal.value = false
      loadUsers()
    } else {
      message.error(response.data.message || '操作失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '操作失败')
    }
  } finally {
    banSaving.value = false
  }
}

const unban = (user: AdminUser) => {
  Modal.confirm({
    title: '确认解封',
    icon: () => h(ExclamationCircleOutlined),
    content: `确定立即解封「${user.username}」吗？该用户将可以立即重新登录。`,
    okText: '解封',
    cancelText: '取消',
    onOk: async () => {
      try {
        const response = await adminApi.unbanUser(user.id)
        if (response.data.success) {
          message.success('用户已解封')
          loadUsers()
        } else {
          message.error(response.data.message || '操作失败')
        }
      } catch (error: unknown) {
        if (!isHandledByInterceptor(error)) {
          const msg = isAxiosError(error) ? error.response?.data?.message : null
          message.error(msg || '操作失败')
        }
      }
    },
  })
}

const openPasswordModal = (user: AdminUser) => {
  passwordTargetUser.value = user
  newPassword.value = ''
  confirmPassword.value = ''
  showPasswordModal.value = true
}

const doResetPassword = async () => {
  if (!passwordTargetUser.value) return
  const trimmed = newPassword.value.trim()
  if (trimmed.length < 6) {
    message.warning('密码长度至少为 6 位')
    return
  }
  if (trimmed !== confirmPassword.value.trim()) {
    message.warning('两次输入的密码不一致')
    return
  }
  try {
    pwdSaving.value = true
    const response = await adminApi.resetUserPassword(passwordTargetUser.value.id, trimmed)
    if (response.data.success) {
      message.success('密码已重置')
      showPasswordModal.value = false
    } else {
      message.error(response.data.message || '操作失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '操作失败')
    }
  } finally {
    pwdSaving.value = false
  }
}

const confirmDelete = (user: AdminUser) => {
  Modal.confirm({
    title: '确认删除',
    icon: () => h(ExclamationCircleOutlined),
    content: `确定要删除用户「${user.username}」吗？此操作不可恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const response = await adminApi.deleteUser(user.id)
        if (response.data.success) {
          message.success('用户已删除')
          // 依据服务端返回的总量计算新的总页数，删除最后一页最后一条时正确回退
          const newTotal = totalElements.value - 1
          const pages = Math.max(1, Math.ceil(newTotal / pageSize.value))
          if (currentPage.value >= pages) {
            currentPage.value = pages - 1
          }
          loadUsers()
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

onMounted(() => {
  loadUsers()
})
</script>

<template>
  <Card>
    <div class="header">
      <Title :level="4">
        <UserOutlined /> 用户管理
      </Title>
      <Button type="primary" @click="loadUsers">
        <ReloadOutlined /> 刷新
      </Button>
    </div>

    <Table
      :columns="columns"
      :data-source="users"
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
      v-model:open="showBanModal"
      title="封禁用户"
      :ok-text="'封禁'"
      :cancel-text="'取消'"
      :confirm-loading="banSaving"
      @ok="doBan"
    >
      <p>将封禁用户「{{ banTargetUser?.username }}」</p>
      <InputNumber v-model:value="banDuration" :min="1" style="width: 100%" />
      <div style="font-size: 12px; color: #999; margin-top: 4px;">
        封禁时长（分钟）
      </div>
    </Modal>

    <Modal
      v-model:open="showPasswordModal"
      title="重置密码"
      :ok-text="'重置'"
      :cancel-text="'取消'"
      :confirm-loading="pwdSaving"
      @ok="doResetPassword"
    >
      <p>为「{{ passwordTargetUser?.username }}」设置新密码</p>
      <Input.Password v-model:value="newPassword" placeholder="新密码（至少6位）" />
      <div style="height: 12px" />
      <Input.Password v-model:value="confirmPassword" placeholder="再次输入新密码" />
    </Modal>

    <Modal
      v-model:open="showAdjustModal"
      title="调整积分"
      :ok-text="'提交'"
      :cancel-text="'取消'"
      :confirm-loading="adjustSaving"
      @ok="doAdjust"
    >
      <p>为用户「{{ adjustTargetUser?.username }}」（当前余额 {{ adjustTargetUser?.credits ?? 0 }} 积分）调整积分</p>
      <FormItem label="调整积分（正数增加，负数扣减）">
        <InputNumber
          v-model:value="adjustDelta"
          :min="ADJUST_MIN"
          :max="ADJUST_MAX"
          :precision="0"
          style="width: 100%"
        />
      </FormItem>
      <!-- 余额预览：负数标红，与 Server adminAdjust 校验文案对齐 -->
      <div
        v-if="previewBalance !== null"
        :style="{
          marginBottom: '12px',
          padding: '8px 12px',
          borderRadius: '4px',
          background: previewWillBeNegative ? '#fff1f0' : '#f6ffed',
          color: previewWillBeNegative ? '#cf1322' : '#389e0d',
          border: '1px solid ' + (previewWillBeNegative ? '#ffa39e' : '#b7eb8f'),
        }"
      >
        调整后余额：<strong>{{ previewBalance }}</strong> 积分
        <span v-if="previewWillBeNegative">（不能为负数，请减少扣减额度）</span>
      </div>
      <FormItem label="备注">
        <Input v-model:value="adjustRemark" placeholder="如：客诉补偿" />
      </FormItem>
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
</style>
