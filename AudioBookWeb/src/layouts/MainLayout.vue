<script setup lang="ts">
import { ref, computed, h, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Layout, Menu, Avatar, Dropdown, Drawer, Button, type MenuProps } from 'ant-design-vue'
import {
  AudioOutlined,
  CalendarOutlined,
  UserOutlined,
  LogoutOutlined,
  ShopOutlined,
  SoundOutlined,
  DollarOutlined,
  MenuOutlined,
  CompassOutlined,
  BookOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
} from '@ant-design/icons-vue'
import { performLogout } from '@audiobook/shared'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'
import { useCreditsStore } from '@/stores/credits'
import { authApi } from '@/services/api'

const { Header, Sider, Content } = Layout
const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const configStore = useConfigStore()
const creditsStore = useCreditsStore()

// ---------- 移动端断点（< 768px 收起侧栏，改用抽屉导航） ----------
const windowWidth = ref(window.innerWidth)
const onResize = () => {
  windowWidth.value = window.innerWidth
}
const isMobile = computed(() => windowWidth.value < 768)
const drawerVisible = ref(false)

// ---------- 桌面端侧栏收起/展开 ----------
// 折叠后宽度 80px、只留图标；状态持久化到 localStorage，刷新与路由跳转都不丢。
// 只作用于桌面端：移动端本就不渲染 Sider（走抽屉），折叠态对它无副作用。
const SIDER_COLLAPSED_KEY = 'audiobook.sider.collapsed'

function readSiderCollapsed(): boolean {
  // 隐私模式 / 禁用存储时 localStorage 会抛异常，此时按默认展开处理，不阻断渲染
  try {
    return localStorage.getItem(SIDER_COLLAPSED_KEY) === '1'
  } catch {
    return false
  }
}

const siderCollapsed = ref(readSiderCollapsed())

const toggleSider = () => {
  siderCollapsed.value = !siderCollapsed.value
  try {
    localStorage.setItem(SIDER_COLLAPSED_KEY, siderCollapsed.value ? '1' : '0')
  } catch {
    /* 写不进去只失去持久化，当前会话内的折叠行为仍然正确 */
  }
}

const menuItems = computed(() => [
  {
    key: '/store',
    icon: () => h(ShopOutlined),
    label: '书城',
  },
  {
    key: '/shelf',
    icon: () => h(BookOutlined),
    label: '我的书架',
  },
  {
    key: '/discover',
    icon: () => h(CompassOutlined),
    label: '发现',
  },
  {
    key: '/tts',
    icon: () => h(AudioOutlined),
    label: 'TTS合成',
  },
  {
    key: '/references',
    icon: () => h(SoundOutlined),
    label: '我的参考音频',
  },
  {
    key: '/tasks',
    icon: () => h(CalendarOutlined),
    label: '任务列表',
  },
  {
    key: '/credits',
    icon: () => h(DollarOutlined),
    label: '积分充值',
  },
  {
    key: '/profile',
    icon: () => h(UserOutlined),
    label: '个人中心',
  },
])

const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
  router.push(key as string)
}

// 抽屉导航点击：先收起抽屉再跳转，避免返回后抽屉仍展开
const handleDrawerMenuClick: MenuProps['onClick'] = ({ key }) => {
  drawerVisible.value = false
  router.push(key as string)
}

/**
 * 退出登录：清服务端 HttpOnly Cookie → 清本地登录态 → 回登录页。
 * 抽成函数供「顶栏用户下拉」与「侧栏/抽屉底部按钮」两个入口复用，避免逻辑漂移。
 */
const doLogout = () =>
  performLogout({
    serverLogout: () => authApi.logout(),
    clearAuth: () => authStore.logout(),
    navigateToLogin: () => router.push('/login'),
  })

/**
 * 顶栏用户下拉的菜单项。
 * 注意：必须由 <Menu :items> 承载并挂在 Dropdown 的 #overlay 插槽里。
 * ant-design-vue 4.2.6 的 Dropdown 只消费 overlay（prop/slot），**不消费 menu prop**
 * （见 es/dropdown/dropdown.js 的 renderOverlay：`props.overlay || slots.overlay?.()`，
 * 全文没有读取 props.menu）。此前写成 `:menu="{ items: userMenuItems }"` 是 antd
 * React v5 的写法，在 Vue 版下下拉能展开但内容恒为空 —— 即「点用户名什么都看不到、
 * 无法退出登录」的根因。
 */
const userMenuItems = computed(() => [
  {
    key: 'logout',
    icon: () => h(LogoutOutlined),
    label: '退出登录',
  },
])

const handleUserMenuClick: MenuProps['onClick'] = ({ key }) => {
  if (key === 'logout') doLogout()
}

// 进入已登录布局即拉取全局配置（上传大小上限等）与积分余额。
// 余额走全局 credits store：充值/合成/个人中心任一处刷新后，顶栏自动同步，
// 无需 provide/inject 手动联动。
onMounted(() => {
  window.addEventListener('resize', onResize)
  configStore.fetchConfig()
  creditsStore.fetchBalance()
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
})
</script>

<template>
  <Layout class="main-layout">
    <!-- 收起后宽度 80：只保留图标，Menu 靠 inline-collapsed 切到图标模式。
         trigger 置 null —— 触发按钮不放侧栏底部（那里已被「退出登录」占用），
         统一放在顶栏最左侧，与移动端的抽屉按钮同位置，视觉上不来回跳。 -->
    <Sider
      v-if="!isMobile"
      :width="200"
      :collapsed-width="80"
      :collapsed="siderCollapsed"
      :trigger="null"
      collapsible
      theme="dark"
      class="app-sider"
    >
      <div class="logo" :class="{ 'logo-collapsed': siderCollapsed }">
        <AudioOutlined v-if="siderCollapsed" class="logo-mark" />
        <span v-else>有声伴读</span>
      </div>
      <Menu
        theme="dark"
        mode="inline"
        :inline-collapsed="siderCollapsed"
        :selected-keys="[route.path]"
        :items="menuItems"
        @click="handleMenuClick"
      />
      <!-- 常驻退出入口：下拉菜单藏在头像里不易发现，侧栏底部再给一个明确的位置 -->
      <div class="sider-footer">
        <Button type="text" block class="logout-btn" @click="doLogout">
          <LogoutOutlined />
          <span class="logout-text">退出登录</span>
        </Button>
      </div>
    </Sider>

    <Layout>
      <Header class="header">
        <!-- 桌面端：收起/展开侧栏。图标语义用 antd 的 MenuFold/MenuUnfold，
             折叠态朝右（可展开）、展开态朝左（可收起）。 -->
        <Button
          v-if="!isMobile"
          class="menu-toggle"
          type="text"
          :icon="h(siderCollapsed ? MenuUnfoldOutlined : MenuFoldOutlined)"
          :aria-label="siderCollapsed ? '展开导航栏' : '收起导航栏'"
          :title="siderCollapsed ? '展开导航栏' : '收起导航栏'"
          @click="toggleSider"
        />
        <Button
          v-if="isMobile"
          class="menu-toggle"
          type="text"
          :icon="h(MenuOutlined)"
          aria-label="打开导航菜单"
          @click="drawerVisible = true"
        />
        <div class="header-right">
          <div class="header-balance" @click="router.push('/credits')">
            <DollarOutlined />
            <span>{{ creditsStore.balance }} 积分</span>
          </div>
          <!-- 退出登录入口：trigger 用 click（默认的 hover 在触屏上点不开）。
               菜单内容必须走 #overlay 插槽，:menu 在本版 antd-vue 下不生效。 -->
          <Dropdown placement="bottomRight" :trigger="['click']">
            <div class="user-info">
              <Avatar :icon="h(UserOutlined)" />
              <span>{{ authStore.username }}</span>
            </div>
            <template #overlay>
              <Menu :items="userMenuItems" @click="handleUserMenuClick" />
            </template>
          </Dropdown>
        </div>
      </Header>

      <Content class="content">
        <RouterView />
      </Content>
    </Layout>

    <!-- 移动端抽屉导航 -->
    <Drawer
      v-model:open="drawerVisible"
      placement="left"
      :width="220"
      :closable="false"
      :body-style="{ padding: 0, background: '#001529' }"
    >
      <div class="logo">
        有声伴读
      </div>
      <Menu
        theme="dark"
        mode="inline"
        :selected-keys="[route.path]"
        :items="menuItems"
        @click="handleDrawerMenuClick"
      />
      <!-- 移动端同样给常驻退出入口（抽屉内），避免只能靠头像下拉操作 -->
      <div class="sider-footer">
        <Button type="text" block class="logout-btn" @click="doLogout">
          <LogoutOutlined />
          <span class="logout-text">退出登录</span>
        </Button>
      </div>
    </Drawer>
  </Layout>
</template>

<style scoped>
.main-layout {
  min-height: 100vh;
}

.logo {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 18px;
  font-weight: bold;
  white-space: nowrap;
}

/* 收起态：logo 位只剩一个图标，去掉文字宽度带来的抖动 */
.logo-collapsed {
  font-size: 20px;
}

.logo-mark {
  font-size: 20px;
}

/* 侧栏固定为视口高度：内容区比视口高时，若让侧栏跟着页面拉伸，
   底部的退出按钮会被顶到滚动区之外（要点到最底部才看得见）。 */
.app-sider {
  position: sticky;
  top: 0;
  height: 100vh;
  overflow: auto;
  /* 收起时 Menu 的 tooltip 走 portal，不应产生横向滚动条 */
  overflow-x: hidden;
}

/* 收起态只留图标：退出按钮的文案让位，按钮自动居中。
   `ant-layout-sider-collapsed` 与 app-sider 落在同一个根元素上，不能用后代选择器 */
.app-sider.ant-layout-sider-collapsed .logout-text {
  display: none;
}

.app-sider :deep(.ant-menu-inline-collapsed) {
  width: 100%;
}

/* 侧栏（含移动端抽屉）内容改为纵向弹性布局，让底部退出按钮始终贴底 */
.main-layout :deep(.ant-layout-sider-children) {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.sider-footer {
  margin-top: auto;
  padding: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.12);
}

.logout-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: rgba(255, 255, 255, 0.65);
}

.logout-btn:hover {
  color: #fff;
}

.header {
  background: #fff;
  padding: 0 24px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

/* 夜间模式：顶栏白底翻转为深色，避免夜里刺眼 */
.dark .header {
  background: #1f1f1f;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.4);
}

.header-right {
  display: flex;
  align-items: center;
  margin-left: auto;
}

.menu-toggle {
  font-size: 18px;
}

.user-info {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-balance {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  color: #cf1322;
  font-weight: 600;
  padding: 6px 12px;
  border-radius: 16px;
  background: #fff1f0;
  margin-right: 16px;
}

/* 夜间模式：积分胶囊用深暖底，红色取 antd dark 的 error-4，避免亮粉底在暗色下刺眼 */
.dark .header-balance {
  background: #2b1f1f;
  color: #ff7875;
}

.content {
  margin: 24px;
}

/* 移动端：收窄内容边距，顶栏用户名只留头像空间 */
@media (max-width: 768px) {
  .header {
    padding: 0 12px;
  }

  .content {
    margin: 12px;
  }
}
</style>
