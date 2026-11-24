<script setup lang="ts">
import { computed, h } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Layout, Menu, Avatar, Dropdown, type MenuProps } from 'ant-design-vue'
import {
  UserOutlined,
  LogoutOutlined,
  BookOutlined,
  AudioOutlined,
  SettingOutlined,
  DollarOutlined,
  CompassOutlined,
  ApiOutlined,
  MobileOutlined,
} from '@ant-design/icons-vue'
import { performLogout } from '@audiobook/shared'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/services/api'

const { Header, Sider, Content } = Layout
const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const menuItems = computed(() => [
  {
    key: '/users',
    icon: () => h(UserOutlined),
    label: '用户管理',
  },
  {
    key: '/books',
    icon: () => h(BookOutlined),
    label: '书城管理',
  },
  {
    key: '/reference',
    icon: () => h(AudioOutlined),
    label: '参考音频管理',
  },
  {
    key: '/tts-nodes',
    icon: () => h(ApiOutlined),
    label: '合成节点管理',
  },
  {
    key: '/credits',
    icon: () => h(DollarOutlined),
    label: '积分管理',
  },
  {
    key: '/app-version',
    icon: () => h(MobileOutlined),
    label: '客户端版本',
  },
  {
    key: '/discover',
    icon: () => h(CompassOutlined),
    label: '发现内容审核',
  },
])

const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
  router.push(key as string)
}

const userMenuItems = computed(() => [
  {
    key: 'logout',
    icon: () => h(LogoutOutlined),
    label: '退出登录',
    onClick: () =>
      performLogout({
        serverLogout: () => authApi.logout(),
        clearAuth: () => authStore.logout(),
        navigateToLogin: () => router.push('/login'),
      }),
  },
])
</script>

<template>
  <Layout class="main-layout">
    <Sider :width="200" theme="dark">
      <div class="logo">
        <SettingOutlined /> 后台管理
      </div>
      <Menu
        theme="dark"
        mode="inline"
        :selected-keys="[route.path]"
        :items="menuItems"
        @click="handleMenuClick"
      />
    </Sider>

    <Layout>
      <Header class="header">
        <div class="header-title">
          <SettingOutlined /> 有声书TTS 管理系统
        </div>
        <!--
          下拉必须走 #overlay 插槽：ant-design-vue 4.2.6 的 Dropdown 只消费 overlay
          （prop 与插槽），完全不读 menu prop。写成 :menu="{ items }"（antd React v5 语法）
          会静默失效——容器照常展开、内容恒为空，表现为「退出登录点不开」。
          trigger 显式指定 click：默认是 hover，触屏上点不出来。
        -->
        <Dropdown :trigger="['click']" placement="bottomRight">
          <div class="user-info">
            <Avatar :icon="h(UserOutlined)" />
            <span>{{ authStore.username }}</span>
          </div>
          <template #overlay>
            <Menu :items="userMenuItems" />
          </template>
        </Dropdown>
      </Header>

      <Content class="content">
        <RouterView />
      </Content>
    </Layout>
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
  gap: 8px;
  color: white;
  font-size: 18px;
  font-weight: bold;
}

.header {
  background: #fff;
  padding: 0 24px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
  color: #333;
}

.user-info {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
}

.content {
  margin: 24px;
}
</style>
