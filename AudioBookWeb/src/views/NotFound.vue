<script setup lang="ts">
import { Button, Result } from 'ant-design-vue'
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="not-found">
    <Result status="404" title="404" sub-title="抱歉，您访问的页面不存在或已被移除。">
      <template #extra>
        <Button type="primary" @click="router.push('/')">返回首页</Button>
      </template>
    </Result>
  </div>
</template>

<style scoped>
.not-found {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
}

/* 夜间模式：背景透明以露出暗色 body，避免整页白底在夜里刺眼 */
.dark .not-found {
  background: transparent;
}
</style>
