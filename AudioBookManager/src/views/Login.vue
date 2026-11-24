<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Form, Input, Button, Card, message, Typography } from 'ant-design-vue'
import type { FormInstance, FormProps } from 'ant-design-vue'
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'
import { authApi } from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import { isAxiosError } from 'axios'

const { Title } = Typography
const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const formRef = ref<FormInstance | null>(null)
const loading = ref(false)
const formState = ref<{ username: string; password: string }>({
  username: '',
  password: '',
})

// 显式标注 Form 的 rules 类型：字面量对象与 FormProps['rules'] 结构对比时会因
// 缺少可选字段导致 vue-tsc 报错（RuleObject 联合类型），上下文标注后即可正常收窄
const rules: FormProps['rules'] = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const handleSubmit = async () => {
  const form = formRef.value
  if (!form) return
  try {
    await form.validate()
    loading.value = true

    const response = await authApi.login({
      username: formState.value.username,
      password: formState.value.password,
    })

    if (response.data.success) {
      // 必须是管理员才能进入后台管理
      if (!response.data.isAdmin) {
        message.error('无管理员权限')
        return
      }
      // 凭据已由服务端下发到 HttpOnly Cookie；响应体里的 token 仅兼容 Android 端
      authStore.login(response.data.username, response.data.isAdmin)
      message.success('登录成功')
      // 回到登录前正在看的页面（由路由守卫通过 ?redirect= 传入），与 Web 端一致
      const redirect = route.query.redirect
      router.push(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/')
    } else {
      message.error(response.data.message || '登录失败')
    }
  } catch (error: unknown) {
    // 表单校验失败的 reject（含 errorFields）直接忽略，不弹 toast
    if (error && typeof error === 'object' && 'errorFields' in error) {
      return
    }
    if (isAxiosError(error)) {
      message.error(error.response?.data?.message || '登录失败，请检查网络连接')
    } else {
      message.error('登录失败，请检查网络连接')
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-container">
    <Card class="login-card">
      <Title :level="2" class="login-title">
        有声书TTS - 后台管理
      </Title>

      <Form
        ref="formRef"
        :model="formState"
        :rules="rules"
        @finish="handleSubmit"
        size="large"
      >
        <Form.Item name="username">
          <Input
            v-model:value="formState.username"
            placeholder="管理员用户名"
          >
            <template #prefix>
              <UserOutlined />
            </template>
          </Input>
        </Form.Item>

        <Form.Item name="password">
          <Input.Password
            v-model:value="formState.password"
            placeholder="密码"
          >
            <template #prefix>
              <LockOutlined />
            </template>
          </Input.Password>
        </Form.Item>

        <Form.Item>
          <Button type="primary" html-type="submit" :loading="loading" block>
            登录
          </Button>
        </Form.Item>
      </Form>

      <div class="login-hint">
        <Typography.Text type="secondary">
          仅限管理员账号登录
        </Typography.Text>
      </div>
    </Card>
  </div>
</template>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #1f1c2c 0%, #928dab 100%);
}

.login-card {
  width: 400px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.login-title {
  text-align: center;
  margin-bottom: 30px;
}

.login-hint {
  text-align: center;
}
</style>
