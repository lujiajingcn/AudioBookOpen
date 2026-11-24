<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Form, Input, Button, Card, message, Typography } from 'ant-design-vue'
import type { Rule } from 'ant-design-vue/es/form'
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'
import { authApi } from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import { sanitizeRedirect, keepRedirectQuery } from '@/utils/redirect'

const { Title } = Typography
const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const formRef = ref()
const loading = ref(false)
const formState = ref({
  username: '',
  password: '',
  confirmPassword: '',
})

const rules: Record<string, Rule[]> = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度至少为6位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    {
      validator: (_rule: any, value: string) => {
        if (!value || formState.value.password === value) {
          return Promise.resolve()
        }
        return Promise.reject(new Error('两次输入的密码不一致'))
      },
      trigger: 'blur',
    },
  ],
}

const handleSubmit = async () => {
  try {
    await formRef.value.validate()
    loading.value = true
    
    const response = await authApi.register({
      username: formState.value.username,
      password: formState.value.password,
    })
    
    if (response.data.success) {
      authStore.login(response.data.username)
      message.success('注册成功')
      // 回到注册前正在看的页面（由路由守卫/401 拦截器通过 ?redirect= 传入），与 Login 一致
      router.push(sanitizeRedirect(route.query.redirect) ?? '/')
    } else {
      message.error(response.data.message || '注册失败')
    }
  } catch (error: any) {
    if (error.errorFields) {
      return
    }
    message.error(error.response?.data?.message || '注册失败，请检查网络连接')
  } finally {
    loading.value = false
  }
}

const goToLogin = () => {
  // 透传 ?redirect=：否则「访问 /store 被拦 → 去注册 → 转登录 → 登录成功」会落到首页
  router.push({ path: '/login', query: keepRedirectQuery(route.query.redirect) })
}
</script>

<template>
  <div class="register-container">
    <Card class="register-card">
      <Title :level="2" class="register-title">
        用户注册
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
            placeholder="用户名"
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

        <Form.Item name="confirmPassword">
          <Input.Password
            v-model:value="formState.confirmPassword"
            placeholder="确认密码"
          >
            <template #prefix>
              <LockOutlined />
            </template>
          </Input.Password>
        </Form.Item>

        <Form.Item>
          <Button type="primary" html-type="submit" :loading="loading" block>
            注册
          </Button>
        </Form.Item>
      </Form>

      <div class="login-link">
        <Button type="link" @click="goToLogin">
          已有账号？立即登录
        </Button>
      </div>
    </Card>
  </div>
</template>

<style scoped>
.register-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.register-card {
  width: 400px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.register-title {
  text-align: center;
  margin-bottom: 30px;
}

.login-link {
  text-align: center;
}
</style>
