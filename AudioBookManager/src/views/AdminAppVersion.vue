<script setup lang="ts">
import { computed, h, onMounted, ref, type VNode } from 'vue'
import {
  Alert, AutoComplete, Button, Card, Input, InputNumber, Modal, Progress,
  Space, Switch, Table, Tag, Tooltip, Typography, Upload, message,
} from 'ant-design-vue'
import {
  AndroidOutlined, DeleteOutlined, ExclamationCircleOutlined,
  MobileOutlined, ReloadOutlined, UploadOutlined, WarningOutlined,
} from '@ant-design/icons-vue'
import type { UploadFile } from 'ant-design-vue/es/upload/interface'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  adminApi,
  type AdminAppVersion,
} from '@/services/api'
import { isHandledByInterceptor } from '@/services/apiClient'
import { formatDateTime, formatFileSize } from '@audiobook/shared'
import { isAxiosError, isCancel } from 'axios'

const { Title, Text } = Typography

/**
 * 容器对 multipart 的上限（与参考音频/电子书共用 spring.servlet.multipart.max-file-size=50MB）。
 * 这里提前按同一口径拦一道，避免传到大文件才被服务端 413 拒掉、只留下一个难懂的错误。
 */
const MAX_APK_SIZE = 50 * 1024 * 1024

/** 常用平台标识；也允许手填其它值（后端只校验 ^[a-zA-Z0-9_-]{1,32}$）。 */
const PLATFORM_OPTIONS = ['android', 'ios', 'harmonyos', 'windows', 'macos'].map((v) => ({ value: v }))

const platformFilter = ref('android')
const versions = ref<AdminAppVersion[]>([])
const loading = ref(false)

const showUploadModal = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const uploadFileList = ref<UploadFile[]>([])
const uploadAbort = ref<AbortController | null>(null)

const formPlatform = ref('android')
const formVersionCode = ref<number | undefined>(undefined)
const formVersionName = ref('')
const formChangelog = ref('')
const formForceUpdate = ref(false)
const formReleaseNow = ref(false)

/** 当前列表中最大的 versionCode —— 用于「建议下一个版本号」的预填。 */
const maxVersionCode = computed(() =>
  versions.value.reduce((mx, v) => (typeof v.versionCode === 'number' && v.versionCode > mx ? v.versionCode : mx), 0),
)

const releasedCount = computed(() => versions.value.filter((v) => v.released).length)

const loadVersions = async () => {
  loading.value = true
  try {
    const response = await adminApi.getAppVersions(platformFilter.value)
    if (response.data.success) {
      versions.value = response.data.versions ?? []
    } else {
      message.error(response.data.message || '加载版本列表失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '加载版本列表失败')
    }
  } finally {
    loading.value = false
  }
}

const resetUploadForm = () => {
  uploadFileList.value = []
  formPlatform.value = platformFilter.value || 'android'
  formVersionCode.value = undefined
  formVersionName.value = ''
  formChangelog.value = ''
  formForceUpdate.value = false
  formReleaseNow.value = false
  uploadProgress.value = 0
}

const openUploadModal = () => {
  resetUploadForm()
  // 预填「建议的下一个版本号」：手填最容易出错、也最致命的一步是版本号没递增，
  // 填小了客户端永远查不到更新；给一个基于当前最大值的默认能挡住大部分误填（仍可改）。
  formVersionCode.value = maxVersionCode.value + 1
  showUploadModal.value = true
}

const handleUploadCancel = () => {
  if (uploadAbort.value) {
    uploadAbort.value.abort()
    uploadAbort.value = null
  }
  resetUploadForm()
}

/** 只保留最后一个选择的文件，取消 antd 默认的自动上传 */
const handleFileChange = (info: { file: UploadFile; fileList: UploadFile[] }) => {
  uploadFileList.value = info.fileList.slice(-1)
}

const validateApk = (file: File): boolean => {
  if (!file.name.toLowerCase().endsWith('.apk')) {
    message.warning('只支持上传 .apk 文件')
    return false
  }
  if (file.size === 0) {
    message.warning('文件内容为空')
    return false
  }
  if (file.size > MAX_APK_SIZE) {
    message.warning('APK 不能超过 50MB（服务端上传上限）')
    return false
  }
  return true
}

/** versionName 白名单，与服务端 SAFE_VERSION_NAME 保持一致，避免传到服务端才 400 */
const VERSION_NAME_RE = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$/

const confirmUpload = async () => {
  // before-upload 返回 false 时，不同版本的 ant-design-vue 可能把原始 File 放在
  // originFileObj，也可能直接把 File 自身塞进 fileList，这里两种形态都兼容
  const picked = uploadFileList.value[0] as (UploadFile & { originFileObj?: File }) | undefined
  const file = (picked?.originFileObj ?? (picked as unknown as File | undefined)) as File | undefined
  if (!file || typeof file.size !== 'number') {
    message.warning('请选择 APK 文件')
    return
  }
  if (!validateApk(file)) return

  const versionCode = formVersionCode.value
  if (versionCode == null || !Number.isInteger(versionCode) || versionCode <= 0) {
    message.warning('versionCode 必须为正整数')
    return
  }
  const versionName = formVersionName.value.trim()
  if (!versionName) {
    message.warning('请填写 versionName（如 1.2.0）')
    return
  }
  if (!VERSION_NAME_RE.test(versionName)) {
    message.warning('versionName 只能包含字母、数字、点、下划线、连字符和加号，且以字母或数字开头（最长 64 位）')
    return
  }
  const platform = formPlatform.value.trim() || 'android'
  if (!/^[a-zA-Z0-9_-]{1,32}$/.test(platform)) {
    message.warning('平台标识只能是字母、数字、下划线或连字符，最长 32 位')
    return
  }

  const controller = new AbortController()
  uploadAbort.value = controller
  uploading.value = true
  uploadProgress.value = 0
  try {
    const response = await adminApi.uploadAppVersion(
      file,
      {
        versionCode,
        versionName,
        platform,
        changelog: formChangelog.value.trim() || undefined,
        forceUpdate: formForceUpdate.value,
        release: formReleaseNow.value,
      },
      (p) => (uploadProgress.value = p),
      controller.signal,
    )
    if (response.data.success) {
      // 上传成功后把筛选切到该平台，用户能立刻看到刚传的版本
      message.success(response.data.message || '已上传')
      showUploadModal.value = false
      resetUploadForm()
      if (platformFilter.value !== platform) {
        platformFilter.value = platform
      }
      loadVersions()
    } else {
      message.error(response.data.message || '上传失败')
    }
  } catch (error: unknown) {
    if (isCancel(error)) return
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '上传失败')
    }
  } finally {
    uploading.value = false
    uploadProgress.value = 0
    uploadAbort.value = null
  }
}

const doRelease = async (record: AdminAppVersion) => {
  try {
    const response = await adminApi.releaseAppVersion(record.id)
    if (response.data.success) {
      message.success(response.data.message || '已发布')
      await loadVersions()
    } else {
      message.error(response.data.message || '发布失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '发布失败')
    }
  }
}

const confirmUnrelease = (record: AdminAppVersion) => {
  Modal.confirm({
    title: '确认下线该版本',
    icon: () => h(ExclamationCircleOutlined),
    content: `确定下线 v${record.versionName}（versionCode ${record.versionCode}）吗？`
      + '下线后客户端将不再收到这个版本的更新提示。记录与安装包都保留，可随时重新发布。',
    okText: '下线',
    cancelText: '取消',
    onOk: async () => {
      try {
        const response = await adminApi.unreleaseAppVersion(record.id)
        if (response.data.success) {
          message.success(response.data.message || '已下线')
          await loadVersions()
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

const confirmDelete = (record: AdminAppVersion) => {
  Modal.confirm({
    title: '确认删除版本',
    icon: () => h(ExclamationCircleOutlined),
    content: `确定删除 v${record.versionName}（versionCode ${record.versionCode}）吗？`
      + '这会一并删除服务器上的安装包文件，操作不可恢复。若只是想停止对外提示更新，请改用「下线」。'
      + (record.released ? '\n注意：这是一个已发布的当前版本，删除即下线。' : ''),
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const response = await adminApi.deleteAppVersion(record.id)
        if (response.data.success) {
          message.success(response.data.message || '已删除')
          await loadVersions()
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

const columns: ColumnsType<AdminAppVersion> = [
  {
    title: '状态',
    key: 'status',
    width: 150,
    customRender: ({ record }) => {
      const tags: VNode[] = []
      tags.push(
        h(Tag, { color: record.released ? 'success' : 'default' }, () => (record.released ? '当前版本' : '未发布')),
      )
      if (!record.fileExists) {
        tags.push(
          h(Tooltip, { title: '服务器上的安装包文件已丢失，发布/下载都会失败，请删除后重新上传', placement: 'top' }, {
            default: () => h(Tag, { color: 'error' }, () => [h(WarningOutlined), ' 文件丢失']),
          }),
        )
      }
      return h(Space, { size: 4 }, () => tags)
    },
  },
  {
    title: 'versionCode',
    dataIndex: 'versionCode',
    key: 'versionCode',
    width: 120,
    sorter: (a, b) => a.versionCode - b.versionCode,
  },
  {
    title: '版本名',
    dataIndex: 'versionName',
    key: 'versionName',
    width: 130,
    customRender: ({ text }) => h('span', { style: 'font-weight: 500;' }, text || '-'),
  },
  {
    title: '大小',
    dataIndex: 'fileSize',
    key: 'fileSize',
    width: 100,
    customRender: ({ text }) => formatFileSize(text as number | null | undefined),
  },
  {
    title: '强更',
    dataIndex: 'forceUpdate',
    key: 'forceUpdate',
    width: 80,
    customRender: ({ text }) => (text ? h(Tag, { color: 'volcano' }, () => '强制') : h(Text, { type: 'secondary' }, () => '-')),
  },
  {
    title: '更新说明',
    dataIndex: 'changelog',
    key: 'changelog',
    ellipsis: true,
    customRender: ({ text }) => (text
      ? h(Tooltip, { title: text, placement: 'topLeft' }, { default: () => h('span', String(text)) })
      : h(Text, { type: 'secondary' }, () => '-')),
  },
  {
    title: 'MD5',
    dataIndex: 'md5',
    key: 'md5',
    width: 140,
    ellipsis: true,
    customRender: ({ text }) => (text
      ? h(Tooltip, { title: String(text), placement: 'topLeft' }, {
          default: () => h('code', { style: 'font-size: 12px;' }, String(text).slice(0, 12) + '…'),
        })
      : '-'),
  },
  {
    title: '发布时间',
    dataIndex: 'releasedAt',
    key: 'releasedAt',
    width: 150,
    customRender: ({ text }) => formatDateTime(text as string | null),
  },
  {
    title: '上传时间',
    dataIndex: 'createdAt',
    key: 'createdAt',
    width: 150,
    customRender: ({ text }) => formatDateTime(text as string | null),
  },
  {
    title: '操作',
    key: 'action',
    width: 190,
    fixed: 'right',
    customRender: ({ record }) => {
      const buttons: VNode[] = []
      if (record.released) {
        buttons.push(
          h(Button, { size: 'small', type: 'link', onClick: () => confirmUnrelease(record) }, () => '下线'),
        )
      } else {
        const canRelease = record.fileExists
        buttons.push(
          h(
            Tooltip,
            canRelease ? { title: '发布后同平台其它版本会自动下线' } : { title: '安装包文件已丢失，无法发布' },
            {
              default: () => h(
                Button,
                { size: 'small', type: 'link', disabled: !canRelease, onClick: () => doRelease(record) },
                () => '发布',
              ),
            },
          ),
        )
      }
      buttons.push(
        h(Button, { size: 'small', type: 'link', danger: true, onClick: () => confirmDelete(record) }, () => [h(DeleteOutlined), ' 删除']),
      )
      return h(Space, { size: 4 }, () => buttons)
    },
  },
]

onMounted(loadVersions)
</script>

<template>
  <Card>
    <div class="header">
      <Title :level="4">
        <MobileOutlined /> 客户端版本管理
      </Title>
      <Space>
        <span class="platform-filter">
          <AndroidOutlined style="margin-right: 6px; color: #999;" />
          <AutoComplete
            v-model:value="platformFilter"
            :options="PLATFORM_OPTIONS"
            style="width: 160px;"
            placeholder="平台"
            @select="loadVersions"
          />
        </span>
        <Button :loading="loading" @click="loadVersions">
          <ReloadOutlined /> 刷新
        </Button>
        <Button type="primary" @click="openUploadModal">
          <UploadOutlined /> 上传新版本
        </Button>
      </Space>
    </div>

    <Alert
      type="info"
      show-icon
      class="hint"
      message="发布唯一 & 版本递增"
      description="同一平台同时只允许一个「当前版本」：点「发布」会自动下线该平台其它已发布版本。
        versionCode 必须严格大于当前最大值，否则客户端检查不到更新、服务端也会拒绝上传。APK 上限 50MB。"
    />

    <div v-if="versions.length" class="summary-line">
      <Text type="secondary">
        平台 {{ platformFilter || 'android' }} · 共 {{ versions.length }} 个版本 · 当前已发布 {{ releasedCount }} 个 · 最大 versionCode {{ maxVersionCode }}
      </Text>
    </div>

    <Table
      row-key="id"
      :columns="columns"
      :data-source="versions"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: 1250 }"
      size="middle"
    />

    <Modal
      v-model:open="showUploadModal"
      title="上传新版本 APK"
      :confirm-loading="uploading"
      ok-text="上传"
      cancel-text="取消"
      :mask-closable="false"
      @ok="confirmUpload"
      @cancel="handleUploadCancel"
    >
      <div class="form-row">
        <label>APK 文件</label>
        <Upload
          :file-list="uploadFileList"
          :max-count="1"
          accept=".apk"
          :before-upload="() => false"
          @change="handleFileChange"
        >
          <Button>
            <UploadOutlined /> 选择 .apk 文件
          </Button>
        </Upload>
        <div class="form-tip">只支持 .apk，大小不超过 50MB。</div>
      </div>

      <div class="form-row form-row-inline">
        <div class="form-col">
          <label>平台</label>
          <AutoComplete v-model:value="formPlatform" :options="PLATFORM_OPTIONS" placeholder="android" />
        </div>
        <div class="form-col">
          <label>versionCode</label>
          <InputNumber v-model:value="formVersionCode" :min="1" :precision="0" :step="1" style="width: 100%;" />
          <div class="form-tip">整数，须 &gt; 当前最大 {{ maxVersionCode }}；已为你预填 {{ maxVersionCode + 1 }}。</div>
        </div>
      </div>

      <div class="form-row">
        <label>版本名 versionName</label>
        <Input v-model:value="formVersionName" placeholder="例如 1.2.0" :maxlength="64" />
        <div class="form-tip">字母或数字开头，可含点、下划线、连字符、加号；会作为下载文件名一部分。</div>
      </div>

      <div class="form-row">
        <label>更新说明 changelog</label>
        <Input.TextArea v-model:value="formChangelog" :rows="3" placeholder="本次更新内容（可选）" />
      </div>

      <div class="form-row form-row-inline">
        <div class="form-col">
          <Space>
            <Switch v-model:checked="formForceUpdate" size="small" />
            <span>强制更新</span>
          </Space>
          <div class="form-tip">开启后客户端将强制升级。</div>
        </div>
        <div class="form-col">
          <Space>
            <Switch v-model:checked="formReleaseNow" size="small" />
            <span>上传后立即发布</span>
          </Space>
          <div class="form-tip">关闭则作为草稿，稍后可在列表里手动「发布」。</div>
        </div>
      </div>

      <Progress
        v-if="uploading"
        :percent="uploadProgress"
        :status="uploadProgress >= 100 ? 'success' : 'active'"
      />
      <div v-if="uploading && uploadProgress >= 100" class="form-tip">
        文件已传完，服务器正在保存与校验，请稍候…
      </div>
    </Modal>
  </Card>
</template>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.platform-filter {
  display: inline-flex;
  align-items: center;
}

.hint {
  margin-bottom: 12px;
}

.summary-line {
  margin-bottom: 12px;
}

.form-row {
  margin-bottom: 14px;
}

.form-row label {
  display: block;
  margin-bottom: 6px;
  font-weight: 500;
}

.form-row-inline {
  display: flex;
  gap: 16px;
}

.form-col {
  flex: 1;
}

.form-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #999;
  line-height: 1.5;
}
</style>
