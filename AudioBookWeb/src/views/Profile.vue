<script setup lang="ts">
import { ref, computed, onMounted, watch, h } from 'vue'
import { useRouter } from 'vue-router'
import {
  Card,
  Typography,
  Avatar,
  Space,
  Tabs,
  Button,
  message,
  List,
  Upload,
  Input,
  Form,
  Alert,
  Popconfirm,
  Switch,
  Slider,
} from 'ant-design-vue'
import {
  UserOutlined,
  UploadOutlined,
  DeleteOutlined,
  PlayCircleOutlined,
  StarOutlined,
  DollarOutlined,
} from '@ant-design/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { authApi, ttsApi, type TtsTaskResponse, type UserReferenceAudio } from '@/services/api'
import { useCreditsStore } from '@/stores/credits'
import { useSettingsStore } from '@/stores/settings'
import { useAudioPreview } from '@/composables/useAudioPreview'
import { favorites, favoritesVersion, type FavoriteItem } from '@/services/favorites'
import { formatFileSize, formatDateTimeShort } from '@audiobook/shared'
import { ACCEPT, validateAudioFile } from '@/utils/audioFile'
import WordBook from '@/views/WordBook.vue'

const { Title } = Typography
const router = useRouter()
const authStore = useAuthStore()

// ---------- 积分余额（统一走全局 credits store，与顶栏/充值页共享数据源） ----------
const creditsStore = useCreditsStore()
const balance = computed(() => creditsStore.balance)
const creditsPerTask = computed(() => creditsStore.creditsPerTask)
const loadCredits = () => creditsStore.fetchBalance()

// 显示与阅读偏好（夜间模式 / 阅读字号 / 界面缩放），与 Android AppPrefs 同源
const settings = useSettingsStore()

/**
 * antd Slider 的 change 回调类型是 `Value = number | [number, number]`（区间滑块会传数组）。
 * 这里统一取单值；解析失败时回退到当前值，避免拖到异常位置把字号/缩放写成 NaN。
 * 另外 Slider 的 @change 在拖动过程中每一步都会触发，若直接落盘 localStorage 会在
 * 一次拖动里产生十几次同步写，因此模板侧改用 @after-change（松手才提交）。
 */
const sliderValue = (v: unknown, fallback: number): number => {
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (Array.isArray(v) && typeof v[0] === 'number' && Number.isFinite(v[0])) return v[0]
  return fallback
}

// ---------- 任务统计 ----------

const taskStats = ref({ total: 0, completed: 0, processing: 0, queued: 0, failed: 0 })

const loadStats = async () => {
  try {
    const res = await ttsApi.getUserTasks()
    if (res.data.success) {
      const list = res.data.tasks || []
      taskStats.value = {
        total: list.length,
        completed: list.filter((t: TtsTaskResponse) => t.status === 'completed').length,
        processing: list.filter((t: TtsTaskResponse) => t.status === 'processing').length,
        queued: list.filter((t: TtsTaskResponse) => t.status === 'queued').length,
        failed: list.filter((t: TtsTaskResponse) => t.status === 'failed' || t.status === 'partial').length,
      }
    }
  } catch {
    // 静默失败
  }
}

// ---------- 我的参考音频 ----------

const references = ref<UserReferenceAudio[]>([])
const refLoading = ref(false)

// 试听：统一由 useAudioPreview 管理（卸载时释放 blob URL，并捕获 play() 的自动播放拒绝）
const { previewingId, toggle: togglePreviewById, stop } = useAudioPreview(
  (id) => ttsApi.previewUserReference(id),
  () => message.error('试听失败'),
)
const togglePreview = (r: UserReferenceAudio) => togglePreviewById(r.id)

const loadReferences = async () => {
  refLoading.value = true
  try {
    const res = await ttsApi.listUserReferences()
    if (res.data.success) references.value = res.data.references || []
  } catch {
    message.error('加载参考音频库失败')
  } finally {
    refLoading.value = false
  }
}

const handleRefUpload = async (file: File) => {
  // 上传前做扩展名 + 大小校验（复用参考音频库的 validateAudioFile），拦截非法文件
  if (!validateAudioFile(file)) return false
  try {
    const res = await ttsApi.uploadUserReference(file)
    if (res.data.success) {
      message.success('已保存到我的参考音频库')
      loadReferences()
    }
  } catch (e: any) {
    message.error(e.response?.data?.message || '上传失败')
  }
  return false
}

const deleteReference = async (r: UserReferenceAudio) => {
  // 删除正在试听的参考音频前先停掉，否则音频续播数秒 + blob URL 泄漏
  if (previewingId.value === r.id) stop()
  try {
    await ttsApi.deleteUserReference(r.id)
    message.success('已删除')
    loadReferences()
  } catch (e: any) {
    message.error(e.response?.data?.message || '删除失败')
  }
}

// ---------- 修改密码 ----------

const pwdForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })
const pwdLoading = ref(false)

const changePassword = async () => {
  if (!pwdForm.value.oldPassword || !pwdForm.value.newPassword) {
    message.warning('请填写完整')
    return
  }
  if (pwdForm.value.newPassword.length < 6) {
    message.warning('新密码长度至少为 6 位')
    return
  }
  if (pwdForm.value.newPassword !== pwdForm.value.confirmPassword) {
    message.warning('两次输入的新密码不一致')
    return
  }
  pwdLoading.value = true
  try {
    const res = await authApi.changePassword(pwdForm.value.oldPassword, pwdForm.value.newPassword)
    // 改密会让旧凭据立即作废（服务端 tokenVersion 递增），服务端已在响应的
    // Set-Cookie 中轮换认证 Cookie，前端无需处理响应体里的新 token
    message.success(res.data.message || '密码修改成功')
    pwdForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
  } catch (e: any) {
    message.error(e.response?.data?.message || '修改失败')
  } finally {
    pwdLoading.value = false
  }
}

// ---------- 我的收藏 ----------

const favoriteList = ref<FavoriteItem[]>(favorites.list())

// 收藏缓存变化时刷新列表：云端同步完成 / 增删后 favoritesVersion 自增
watch(favoritesVersion, () => {
  favoriteList.value = favorites.list()
})

const goFavorite = (f: FavoriteItem) => {
  if (!f.taskId) return
  router.push({ path: `/player/${f.taskId}`, query: { name: f.bookName } })
}

const removeFavorite = async (f: FavoriteItem) => {
  await favorites.remove(f.id)
  favoriteList.value = favorites.list()
}

onMounted(() => {
  loadStats()
  loadReferences()
  loadCredits()
  // 拉取云端收藏：跨端同步后本页列表自动刷新（清缓存/换设备也能看到历史收藏）
  favorites.ensureLoaded()
})
</script>

<template>
  <Card>
    <div style="text-align: center; margin-bottom: 24px">
      <Avatar :size="80" :icon="h(UserOutlined)" />
      <Title :level="3" style="margin-top: 12px">{{ authStore.username }}</Title>
    </div>

    <!-- 任务统计 -->
    <div class="stats-row">
      <div class="stat-item"><div class="stat-num">{{ taskStats.total }}</div><div class="stat-label">总任务</div></div>
      <div class="stat-item"><div class="stat-num green">{{ taskStats.completed }}</div><div class="stat-label">已完成</div></div>
      <div class="stat-item"><div class="stat-num blue">{{ taskStats.processing + taskStats.queued }}</div><div class="stat-label">处理中</div></div>
      <div class="stat-item"><div class="stat-num red">{{ taskStats.failed }}</div><div class="stat-label">失败/部分</div></div>
    </div>

    <Alert
      style="margin: 16px 0"
      type="info"
      show-icon
      message="跨端说明"
      description="收藏已上云：Web 与 App 的听书收藏跨端同步，换设备/清缓存都不会丢。Web 端支持在线听书、逐段播放、目录、收藏与睡眠定时；录音参考音频、本地书架等更完整的离线体验请使用移动 App。"
    />

    <!-- 积分余额 -->
    <div class="credit-card">
      <div>
        <div class="credit-label">我的积分</div>
        <div class="credit-balance">{{ balance }}</div>
        <div class="credit-sub">每次合成消耗 {{ creditsPerTask }} 积分</div>
      </div>
      <Button type="primary" :icon="h(DollarOutlined)" @click="router.push('/credits')">
        去充值
      </Button>
    </div>

    <!-- 显示与阅读 -->
    <Card class="appearance-card" title="显示与阅读" :bordered="false" style="margin: 16px 0">
      <div class="appearance-row">
        <span class="appearance-label">夜间模式</span>
        <Switch
          :checked="settings.nightMode"
          @change="(checked: unknown) => settings.setNight(checked === true)"
        />
      </div>

      <div class="appearance-row column">
        <div class="appearance-label">
          阅读字号 <span class="appearance-value">{{ settings.readerFontSize }}px</span>
        </div>
        <Slider
          :min="14"
          :max="32"
          :step="1"
          :value="settings.readerFontSize"
          @after-change="(v: unknown) => settings.setReaderFontSize(sliderValue(v, settings.readerFontSize))"
        />
      </div>

      <div class="appearance-row column">
        <div class="appearance-label">
          界面缩放 <span class="appearance-value">{{ Math.round(settings.uiScale * 100) }}%</span>
        </div>
        <Slider
          :min="0.8"
          :max="1.4"
          :step="0.05"
          :value="settings.uiScale"
          @after-change="(v: unknown) => settings.setUiScale(sliderValue(v, settings.uiScale))"
        />
      </div>

      <Alert
        style="margin-top: 8px"
        type="info"
        show-icon
        message="偏好本地保存"
        description="夜间模式、字号与缩放在本机浏览器保存，不会上传，跨设备需各自设置。"
      />
    </Card>

    <Tabs default-active-key="password">
      <Tabs.TabPane key="password" tab="修改密码">
        <div style="max-width: 400px; margin-top: 16px">
          <Form layout="vertical">
            <Form.Item label="原密码">
              <Input.Password v-model:value="pwdForm.oldPassword" placeholder="请输入原密码" />
            </Form.Item>
            <Form.Item label="新密码">
              <Input.Password v-model:value="pwdForm.newPassword" placeholder="至少 6 位" />
            </Form.Item>
            <Form.Item label="确认新密码">
              <Input.Password v-model:value="pwdForm.confirmPassword" placeholder="再次输入新密码" />
            </Form.Item>
            <Button type="primary" :loading="pwdLoading" @click="changePassword">确认修改</Button>
          </Form>
        </div>
      </Tabs.TabPane>

      <Tabs.TabPane key="references" tab="我的参考音频">
        <Space style="margin: 16px 0">
          <!-- accept 复用 utils/audioFile 的 ACCEPT：原先这里手写白名单，漏了 flac/aac/ogg/m4a，
               导致这些「校验器与后端都支持」的格式在系统文件选择器里被过滤掉 -->
          <Upload :before-upload="handleRefUpload" :show-upload-list="false" :accept="ACCEPT">
            <Button :icon="h(UploadOutlined)">上传参考音频</Button>
          </Upload>
          <Button @click="loadReferences">刷新</Button>
        </Space>
        <List
          :loading="refLoading"
          :data-source="references"
          :locale="{ emptyText: '还没有保存的参考音频，去合成页上传并勾选保存即可建立音色库' }"
        >
          <template #renderItem="{ item }">
            <List.Item
              :actions="[
                h(Button, { size: 'small', icon: h(PlayCircleOutlined), onClick: () => togglePreview(item) }, () => (previewingId === item.id ? '停止' : '试听')),
                h(Popconfirm, { title: '确定删除该参考音频？', onConfirm: () => deleteReference(item) }, {
                  default: () => h(Button, { size: 'small', danger: true, icon: h(DeleteOutlined) }, () => '删除'),
                }),
              ]"
            >
              <List.Item.Meta
                :title="item.name"
                :description="`${formatFileSize(item.fileSize)} · ${formatDateTimeShort(item.createdAt)}`"
              />
            </List.Item>
          </template>
        </List>
      </Tabs.TabPane>

      <Tabs.TabPane key="favorites" tab="我的收藏">
        <List
          :data-source="favoriteList"
          :locale="{ emptyText: '还没有收藏，去在线听书页播放时点击「收藏」即可保存精彩片段' }"
        >
          <template #renderItem="{ item }">
            <List.Item
              :actions="[
                h(Button, { size: 'small', disabled: !item.taskId, onClick: () => goFavorite(item) }, () => '去听'),
                h(Popconfirm, { title: '取消收藏？', onConfirm: () => removeFavorite(item) }, {
                  default: () => h(Button, { size: 'small', danger: true, icon: h(DeleteOutlined) }, () => '删除'),
                }),
              ]"
            >
              <List.Item.Meta>
                <template #title>
                  <Space>
                    <StarOutlined style="color: #faad14" />
                    <span>{{ item.bookName }} · 第 {{ item.segmentNumber }} 段</span>
                  </Space>
                </template>
                <template #description>
                  <span class="fav-content">{{ item.content }}</span>
                </template>
              </List.Item.Meta>
            </List.Item>
          </template>
        </List>
      </Tabs.TabPane>

      <!-- 单词本与「我的收藏」同属个人收藏，并列内嵌到个人中心 Tabs：
           改动最小、体验一致，也不必为一个二级页面新增顶层路由 / 侧栏菜单造成导航噪声。
           Tabs 默认懒渲染非激活 pane，切到本 Tab 时 WordBook 组件才 mount 并触发
           wordBook.ensureLoaded()（见 WordBook.vue），与收藏 Tab 的加载时机一致。 -->
      <Tabs.TabPane key="wordbook" tab="单词本">
        <WordBook />
      </Tabs.TabPane>
    </Tabs>
  </Card>
</template>

<style scoped>
.stats-row {
  display: flex;
  gap: 12px;
  justify-content: space-around;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
}

.stat-item {
  text-align: center;
}

.stat-num {
  font-size: 26px;
  font-weight: bold;
  color: #333;
}

.stat-num.green {
  color: #52c41a;
}

.stat-num.blue {
  color: #1677ff;
}

.stat-num.red {
  color: #ff4d4f;
}

.stat-label {
  font-size: 13px;
  color: #888;
  margin-top: 4px;
}

.fav-content {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 500px;
  display: inline-block;
}

.credit-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  margin: 16px 0;
  background: linear-gradient(135deg, #fff1f0, #fff7e6);
  border-radius: 8px;
}

.credit-label {
  font-size: 13px;
  color: #888;
}

.credit-balance {
  font-size: 32px;
  font-weight: bold;
  color: #cf1322;
  line-height: 1.2;
}

.credit-sub {
  font-size: 13px;
  color: #999;
}

.appearance-card {
  border: 1px solid #f0f0f0;
}

.appearance-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 4px;
  border-bottom: 1px solid #f5f5f5;
}

.appearance-row.column {
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}

.appearance-label {
  font-size: 14px;
  color: #333;
}

.appearance-value {
  color: #1677ff;
  font-weight: 600;
  margin-left: 4px;
}

/* 夜间模式下外观卡片翻深色 */
.dark .appearance-card {
  border-color: #303030;
}

.dark .appearance-row {
  border-bottom-color: #303030;
}

.dark .appearance-label {
  color: rgba(255, 255, 255, 0.85);
}

.dark .appearance-value {
  color: #4096ff;
}

/* 夜间模式：统计条与积分卡原本是浅灰底 / 亮色渐变，夜间整块刺眼，翻为深色面板 */
.dark .stats-row {
  background: #1f1f1f;
}

/* 多带一级 .stats-row 提升优先级，避免覆盖掉 .stat-num.green/.blue/.red 的语义色 */
.dark .stats-row .stat-num {
  color: rgba(255, 255, 255, 0.88);
}

.dark .stat-label {
  color: rgba(255, 255, 255, 0.65);
}

.dark .credit-card {
  background: linear-gradient(135deg, #2b1f1f, #2b2619);
}

.dark .credit-label {
  color: rgba(255, 255, 255, 0.65);
}

.dark .credit-balance {
  color: #ff7875;
}

/* 渐变底比 #141414 亮一档，取 0.55 保证 13px 小字仍满足 4.5:1 */
.dark .credit-sub {
  color: rgba(255, 255, 255, 0.55);
}
</style>
