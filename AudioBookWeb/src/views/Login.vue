<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Form, Input, Button, Card, message, Typography, Spin } from 'ant-design-vue'
import type { Rule } from 'ant-design-vue/es/form'
import { UserOutlined, LockOutlined, AndroidOutlined } from '@ant-design/icons-vue'
import QRCode from 'qrcode'
import {
  authApi,
  appVersionApi,
  resolveApkDownloadUrl,
  APK_FALLBACK_PATH,
} from '@/services/api'
import { useAuthStore } from '@/stores/auth'
import { sanitizeRedirect, keepRedirectQuery } from '@/utils/redirect'

const { Title, Text } = Typography
const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const formRef = ref()
const loading = ref(false)
const formState = ref({
  username: '',
  password: '',
})

// ---------- Android APK 扫码下载 ----------
// 二维码内容必须指向「当前站点」的公开下载地址，所以用 location.origin 拼绝对 URL。
// 目标是 /api/app/version/latest/apk?platform=android —— 这是「最新已发布版本」的稳定地址，
// 以后换 APK 只需在后台发布新版本，这个链接和二维码都不用改。
const qrCodeUrl = ref('')
const qrLoading = ref(true)
const qrError = ref(false)
const apkVersionName = ref('')
const apkSize = ref(0)
const apkDownloadUrl = ref('')
const apkSizeText = computed(() => {
  const s = apkSize.value
  if (!s) return ''
  return s >= 1048576
    ? `${(s / 1048576).toFixed(2)} MB`
    : `${Math.round(s / 1024)} KB`
})

/** 把二维码图片挂在卡片里；失败则退化为「点击直接下载」按钮 */
const buildQrCode = async () => {
  qrLoading.value = true
  qrError.value = false
  // 先按兜底路径生成，保证即使版本接口挂了也能扫出可用的下载地址。
  // 服务端返回的 downloadUrl 是相对路径，两条路径都经过 resolveApkDownloadUrl 转绝对 URL。
  let target = resolveApkDownloadUrl(APK_FALLBACK_PATH)
  try {
    const res = await appVersionApi.getLatest('android')
    const body = res.data
    if (body.hasUpdate && body.latest) {
      apkSize.value = body.latest.fileSize || 0
      apkVersionName.value = body.latest.versionName || ''
    }
    if (body.downloadUrl) {
      target = resolveApkDownloadUrl(body.downloadUrl)
    }
  } catch {
    // 版本接口不可用（后端未启动 / 网络异常）不阻塞登录页，
    // 静默沿用兜底地址；二维码本身仍然是有效的下载链接
    apkVersionName.value = ''
  }
  apkDownloadUrl.value = target
  try {
    qrCodeUrl.value = await QRCode.toDataURL(target, {
      width: 160,
      margin: 1,
      errorCorrectionLevel: 'M',
    })
  } catch {
    qrError.value = true
  } finally {
    qrLoading.value = false
  }
}

onMounted(buildQrCode)

const rules: Record<string, Rule[]> = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const handleSubmit = async () => {
  try {
    await formRef.value.validate()
    loading.value = true
    
    const response = await authApi.login({
      username: formState.value.username,
      password: formState.value.password,
    })
    
    if (response.data.success) {
      // 凭据已由服务端下发到 HttpOnly Cookie；响应体里的 token 仅兼容 Android 端，
      // Web 端不保存
      authStore.login(response.data.username)
      message.success('登录成功')
      // 回到登录前正在看的页面（由路由守卫/401 拦截器通过 ?redirect= 传入）
      router.push(sanitizeRedirect(route.query.redirect) ?? '/')
    } else {
      message.error(response.data.message || '登录失败')
    }
  } catch (error: any) {
    if (error.errorFields) {
      return
    }
    message.error(error.response?.data?.message || '登录失败，请检查网络连接')
  } finally {
    loading.value = false
  }
}

const goToRegister = () => {
  // 透传 ?redirect=：否则「访问 /store 被拦 → 去注册 → 注册成功」会落到首页而不是 /store
  router.push({ path: '/register', query: keepRedirectQuery(route.query.redirect) })
}
</script>

<template>
  <div class="login-container">
    <Card class="login-card">
      <Title :level="2" class="login-title">
        有声书TTS系统
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

        <Form.Item>
          <Button type="primary" html-type="submit" :loading="loading" block>
            登录
          </Button>
        </Form.Item>
      </Form>

      <div class="register-link">
        <Button type="link" @click="goToRegister">
          还没有账号？立即注册
        </Button>
      </div>
    </Card>

    <!-- Android 客户端扫码下载：放在登录卡片下方。
         用 <a :href> 包住二维码，桌面上不解码也能直接点击下载。 -->
    <Card class="download-card">
      <div class="download-title">
        <AndroidOutlined />
        <span>Android 客户端</span>
      </div>

      <div class="download-body">
        <div class="qr-box">
          <Spin v-if="qrLoading" />
          <img v-else-if="qrCodeUrl" :src="qrCodeUrl" alt="Android 客户端下载二维码" />
          <div v-else-if="qrError" class="qr-failed">二维码生成失败</div>
        </div>

        <div class="download-tips">
          <Text type="secondary">手机扫码下载安装 APK</Text>
          <Text v-if="apkVersionName" type="secondary" class="download-meta">
            v{{ apkVersionName }}<template v-if="apkSizeText"> · {{ apkSizeText }}</template>
          </Text>
          <a :href="apkDownloadUrl" class="download-direct">点此直接下载</a>
        </div>
      </div>
    </Card>
  </div>
</template>

<style scoped>
.login-container {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  /* 两张卡片纵排后总高可能超过视口，留出内边距避免贴边 */
  padding: 24px 0;
  box-sizing: border-box;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-card {
  width: 400px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.login-title {
  text-align: center;
  margin-bottom: 30px;
}

.register-link {
  text-align: center;
}

/* ---------- Android 客户端扫码下载 ---------- */
.download-card {
  width: 400px;
  margin-top: 16px;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.download-title {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 14px;
  color: rgba(0, 0, 0, 0.85);
}

.download-body {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20px;
  margin-top: 12px;
}

.qr-box {
  width: 160px;
  height: 160px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.qr-box img {
  width: 160px;
  height: 160px;
  display: block;
}

.qr-failed {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.download-tips {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  line-height: 18px;
}

.download-meta {
  font-size: 12px;
}

.download-direct {
  font-size: 12px;
  margin-top: 4px;
}
</style>
