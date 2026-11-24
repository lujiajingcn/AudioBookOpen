<script setup lang="ts">
import { computed } from 'vue'
import { RouterView } from 'vue-router'
import { ConfigProvider, theme } from 'ant-design-vue'
import { useSettingsStore } from '@/stores/settings'

// 夜间模式：用 Ant Design 的 darkAlgorithm 驱动所有 antd 组件（Card/Tabs/List/Modal…）暗色。
// html.dark 类与 --reader-font-size 变量由 useSettingsStore 在 main.ts 启动时套用，
// 这里仅把夜间开关映射成 antd 主题算法，二者独立、互不冲突。
//
// 注意：静态 message.* / Modal.confirm / notification 直接渲染到 document.body，
// 不在 <ConfigProvider> 组件树内，拿不到 theme context，darkAlgorithm 对它们无效
//（antdv v4 的 ConfigProvider.config() 全局主题只接受 primaryColor 等基础色，不支持 algorithm）。
// 因此改用 CSS 覆盖兜底：这些浮层都挂在 body 下，而 html.dark 在 <html> 上，
// `html.dark .ant-xxx` 能命中。详见 style.css 的「静态浮层暗色覆盖」段。
const settings = useSettingsStore()
const antTheme = computed(() => (settings.nightMode ? { algorithm: theme.darkAlgorithm } : {}))
</script>

<template>
  <ConfigProvider :theme="antTheme">
    <RouterView />
  </ConfigProvider>
</template>

<style scoped></style>
