<script setup lang="ts">
import { ref, onMounted, h } from 'vue'
import {
  Table,
  Button,
  Space,
  Modal,
  Input,
  Upload,
  message,
  Card,
  Typography,
  Progress,
  Tag,
} from 'ant-design-vue'
import {
  AudioOutlined,
  UploadOutlined,
  DeleteOutlined,
  EditOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons-vue'
import type { UploadFile } from 'ant-design-vue/es/upload/interface'
import type { ColumnsType } from 'ant-design-vue/es/table'
import { ttsApi, type UserReferenceAudio } from '@/services/api'
import { formatFileSize, formatDateTimeShort } from '@audiobook/shared'
import { useConfigStore } from '@/stores/config'
import { useAudioPreview } from '@/composables/useAudioPreview'
import { ACCEPT, validateAudioFile } from '@/utils/audioFile'

const { Title, Text } = Typography
const configStore = useConfigStore()

const audios = ref<UserReferenceAudio[]>([])
const loading = ref(false)

// ---------- 上传 ----------
const showUploadModal = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const uploadFile = ref<UploadFile[]>([])
const audioName = ref('')
const audioDescription = ref('')

// ---------- 编辑 ----------
const showEditModal = ref(false)
const editing = ref(false)
const editTarget = ref<UserReferenceAudio | null>(null)
const editName = ref('')
const editDescription = ref('')
const editFile = ref<UploadFile[]>([])
const editReplaceFile = ref(false)

// 试听：统一由 useAudioPreview 管理（组件销毁时释放音频与 blob URL，
// 并捕获 play() 的自动播放拒绝）
const { previewingId, toggle: togglePreviewById, stop: stopPreview } = useAudioPreview(
  (id) => ttsApi.previewUserReference(id),
  () => message.error('试听失败'),
)

const togglePreview = (row: UserReferenceAudio) => togglePreviewById(row.id)

const columns: ColumnsType<UserReferenceAudio> = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
  { title: '名称', dataIndex: 'name', key: 'name', width: 180 },
  {
    title: '描述',
    dataIndex: 'description',
    key: 'description',
    ellipsis: true,
    customRender: ({ text }) => text || '-',
  },
  {
    title: '文件大小',
    dataIndex: 'fileSize',
    key: 'fileSize',
    width: 120,
    customRender: ({ text }) => formatFileSize(text),
  },
  {
    title: '上传时间',
    dataIndex: 'createdAt',
    key: 'createdAt',
    width: 180,
    customRender: ({ text }) => formatDateTimeShort(text),
  },
  {
    title: '操作',
    key: 'action',
    width: 200,
    fixed: 'right',
    customRender: ({ record }) => {
      const r = record as UserReferenceAudio
      return h(Space, { size: 'small' }, {
        default: () => [
          h(Button, {
            size: 'small',
            icon: () => h(PlayCircleOutlined),
            onClick: () => togglePreview(r),
          }, () => (previewingId.value === r.id ? '停止' : '试听')),
          h(Button, {
            size: 'small',
            icon: () => h(EditOutlined),
            onClick: () => openEdit(r),
          }, () => '修改'),
          h(Button, {
            size: 'small',
            danger: true,
            icon: () => h(DeleteOutlined),
            onClick: () => confirmDelete(r),
          }, () => '删除'),
        ],
      })
    },
  },
]

const loadAudios = async () => {
  try {
    loading.value = true
    const res = await ttsApi.listUserReferences()
    if (res.data.success) {
      audios.value = res.data.references || []
    }
  } catch {
    message.error('加载我的参考音频失败')
  } finally {
    loading.value = false
  }
}

// ---------- 上传逻辑 ----------
/**
 * 兼容两种形态：`@change` 链路给的是 `UploadFile`（`originFileObj` 有值），
 * 但当 antd 走 `onBatchStart` 的「Blob 克隆降级」分支时，`originFileObj` 会是
 * `undefined`，此时直接用 `file` 本身（`Upload` 组件把它包装成了 File）。
 * 与 AdminBooks.vue / AdminReference.vue 的 `originFileObj ?? picked` 同源。
 */
const resolveRawFile = (picked: UploadFile | null): File | null => {
  if (!picked) return null
  return (picked.originFileObj as File | undefined) ?? (picked as unknown as File)
}

const handleUploadChange = (info: { file: UploadFile; fileList: UploadFile[] }) => {
  uploadFile.value = info.fileList.slice(-1)
}

const resetUploadForm = () => {
  uploadFile.value = []
  audioName.value = ''
  audioDescription.value = ''
  uploadProgress.value = 0
}

const confirmUpload = async () => {
  const file = resolveRawFile(uploadFile.value[0] ?? null)
  if (!file) {
    message.warning('请选择音频文件')
    return
  }
  if (!validateAudioFile(file)) return

  uploading.value = true
  try {
    const res = await ttsApi.uploadUserReference(
      file,
      audioName.value || undefined,
      audioDescription.value || undefined,
      (percent) => { uploadProgress.value = percent },
    )
    if (res.data.success) {
      message.success('参考音频已保存到我的库')
      showUploadModal.value = false
      resetUploadForm()
      loadAudios()
    } else {
      message.error(res.data.message || '上传失败')
    }
  } catch (e: any) {
    message.error(e?.response?.data?.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

// ---------- 编辑逻辑 ----------
const openEdit = (row: UserReferenceAudio) => {
  editTarget.value = row
  editName.value = row.name
  editDescription.value = row.description || ''
  editFile.value = []
  editReplaceFile.value = false
  showEditModal.value = true
}

const handleEditFileChange = (info: { file: UploadFile; fileList: UploadFile[] }) => {
  editFile.value = info.fileList.slice(-1)
}

const resetEditForm = () => {
  editTarget.value = null
  editName.value = ''
  editDescription.value = ''
  editFile.value = []
  editReplaceFile.value = false
}

const confirmEdit = async () => {
  if (!editTarget.value) return
  // v5/W7：名称是必填（表单标签写着「名称 *」），但原实现没校验 —— 清空后点确定，
  // 服务端会存一个空名字，列表里就出现一条认不出来的音频。
  if (!editName.value.trim()) {
    message.warning('请填写名称')
    return
  }
  const replaceFile = editReplaceFile.value ? resolveRawFile(editFile.value[0] ?? null) : null
  if (replaceFile && !validateAudioFile(replaceFile)) return

  editing.value = true
  try {
    const res = await ttsApi.updateUserReference(editTarget.value.id, {
      name: editName.value,
      description: editDescription.value,
      file: replaceFile ?? undefined,
    })
    if (res.data.success) {
      message.success('已更新')
      showEditModal.value = false
      resetEditForm()
      loadAudios()
    } else {
      message.error(res.data.message || '更新失败')
    }
  } catch (e) {
    // v5/W12：`catch (e: any)` 会让 `e` 全程失去类型检查；用 unknown + 收窄，
    // 只有 axios 错误才有 response.data.message。
    const err = e as { response?: { data?: { message?: string } } }
    message.error(err.response?.data?.message || '更新失败')
  } finally {
    editing.value = false
  }
}

// ---------- 删除 ----------
const confirmDelete = (row: UserReferenceAudio) => {
  Modal.confirm({
    title: '确认删除',
    icon: () => h(ExclamationCircleOutlined),
    content: `确定要删除参考音频「${row.name}」吗？此操作不可恢复，且其他用户的合成任务不受影响（仅你本人可见）。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await ttsApi.deleteUserReference(row.id)
        if (res.data.success) {
          message.success('已删除')
          if (previewingId.value === row.id) {
            stopPreview()
          }
          loadAudios()
        } else {
          message.error(res.data.message || '删除失败')
        }
      } catch (e: any) {
        message.error(e?.response?.data?.message || '删除失败')
      }
    },
  })
}

onMounted(() => {
  configStore.fetchConfig()
  loadAudios()
})
</script>

<template>
  <Card>
    <div class="header">
      <Title :level="4">
        <AudioOutlined /> 我的参考音频库
      </Title>
      <Space>
        <Button @click="loadAudios">
          <ReloadOutlined /> 刷新
        </Button>
        <Button type="primary" @click="showUploadModal = true">
          <UploadOutlined /> 上传参考音频
        </Button>
      </Space>
    </div>

    <Text type="secondary" class="tip">
      这些音频仅你本人可见可用，用于音色克隆合成。支持 WAV/MP3 等音频，也可上传含人声的视频（自动提取音轨转 WAV）。
    </Text>

    <Table
      :columns="columns"
      :data-source="audios"
      row-key="id"
      :loading="loading"
      :pagination="{ pageSize: 10, showSizeChanger: true, showTotal: (t: number) => `共 ${t} 条` }"
    />

    <!-- 上传弹窗 -->
    <Modal
      v-model:open="showUploadModal"
      title="上传参考音频"
      ok-text="上传"
      cancel-text="取消"
      :confirm-loading="uploading"
      @ok="confirmUpload"
      @cancel="resetUploadForm"
      width="600px"
    >
      <div class="upload-form">
        <div class="form-item">
          <label>音频文件 *</label>
          <Upload
            :file-list="uploadFile"
            :max-count="1"
            :accept="ACCEPT"
            :show-upload-list="true"
            @change="handleUploadChange"
          >
            <Button>
              <UploadOutlined /> 选择文件
            </Button>
          </Upload>
          <span class="hint">支持音频格式（WAV/MP3 等）和视频格式（自动提取音频），最大 {{ formatFileSize(configStore.maxFileSizeBytes) }}</span>
        </div>

        <div v-if="uploading" class="form-item">
          <Progress :percent="uploadProgress" status="active" />
        </div>

        <div class="form-item">
          <label>名称 *</label>
          <Input v-model:value="audioName" placeholder="留空则使用文件名" />
        </div>

        <div class="form-item">
          <label>描述</label>
          <Input.TextArea v-model:value="audioDescription" :rows="2" placeholder="音频描述（可选）" />
        </div>
      </div>
    </Modal>

    <!-- 编辑弹窗 -->
    <Modal
      v-model:open="showEditModal"
      title="修改参考音频"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="editing"
      @ok="confirmEdit"
      @cancel="resetEditForm"
      width="600px"
    >
      <div class="upload-form">
        <div class="form-item">
          <label>名称 *</label>
          <Input v-model:value="editName" placeholder="音频名称" />
        </div>

        <div class="form-item">
          <label>描述</label>
          <Input.TextArea v-model:value="editDescription" :rows="2" placeholder="音频描述（可选）" />
        </div>

        <div class="form-item">
          <label>
            替换音频文件
            <a @click="editReplaceFile = !editReplaceFile">{{ editReplaceFile ? '取消替换' : '选择新文件替换' }}</a>
          </label>
          <template v-if="editReplaceFile">
            <Upload
              :file-list="editFile"
              :max-count="1"
              :accept="ACCEPT"
              :show-upload-list="true"
              @change="handleEditFileChange"
            >
              <Button>
                <UploadOutlined /> 选择文件
              </Button>
            </Upload>
            <span class="hint">不选择则仅修改名称/描述，保留原文件。视频会自动转 WAV。</span>
          </template>
          <Tag v-else color="default">保留原音频文件</Tag>
        </div>
      </div>
    </Modal>
  </Card>
</template>

<style scoped>
.header {
  margin-bottom: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.tip {
  display: block;
  margin-bottom: 16px;
}

.upload-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-item label {
  font-weight: 500;
}

.hint {
  font-size: 12px;
  color: #999;
}

/* 夜间模式：表单提示文字的 #999 在暗色下偏暗，按规范取最次级白 */
.dark .hint {
  color: rgba(255, 255, 255, 0.45);
}
</style>
