<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, h } from 'vue'
import {
  Table, Button, Space, Modal, Input, InputNumber, Upload, message, Card, Typography, Progress,
  Select, Tag, Tooltip,
} from 'ant-design-vue'
import {
  AudioOutlined, DeleteOutlined, EditOutlined, ExclamationCircleOutlined,
  UploadOutlined, FolderOutlined, PlusOutlined,
} from '@ant-design/icons-vue'
import type { UploadFile } from 'ant-design-vue/es/upload/interface'
import type { ColumnsType } from 'ant-design-vue/es/table'
import { adminApi, type AdminReferenceAudio, type AdminReferenceTopic } from '@/services/api'
import { isHandledByInterceptor } from '@/services/apiClient'
import { formatDateTime, formatFileSize } from '@audiobook/shared'
import { isAxiosError, isCancel } from 'axios'

const { Title } = Typography

/** 主题下拉的「不归属」哨兵值：Select 的 value 不能是 undefined/null，需要与真实主题 id 区分开 */
const NONE_TOPIC = '__none__'

const audios = ref<AdminReferenceAudio[]>([])
const loading = ref(false)

// ---------- 参考音频主题 ----------
const topics = ref<AdminReferenceTopic[]>([])
const topicsLoading = ref(false)
/** 列表筛选：'all' 全部 / 'none' 未归类 / 其余为主题 id 的字符串形式 */
const topicFilter = ref<string>('all')

/** id → 主题名。列表要展示主题名，用 Map 一次建好，避免每行都线性查找 */
const topicMap = computed(() => new Map(topics.value.map((t) => [t.id, t.name])))

const unassignedCount = computed(() => audios.value.filter((a) => a.topicId == null).length)

/** 筛选下拉：把每个主题的音频数带在标签里，一眼能看出哪些主题还没塞素材 */
const topicOptions = computed(() => [
  { value: 'all', label: `全部主题（${audios.value.length}）` },
  { value: 'none', label: `未归类（${unassignedCount.value}）` },
  ...topics.value.map((t) => ({ value: String(t.id), label: `${t.name}（${t.audioCount ?? 0}）` })),
])

/** 上传/编辑弹窗里选归属用的下拉，第一项固定是「不归属主题」 */
const assignableTopicOptions = computed(() => [
  { value: NONE_TOPIC, label: '不归属主题' },
  ...topics.value.map((t) => ({ value: String(t.id), label: t.name })),
])

const filteredAudios = computed(() => {
  const f = topicFilter.value
  if (f === 'all') return audios.value
  if (f === 'none') return audios.value.filter((a) => a.topicId == null)
  return audios.value.filter((a) => a.topicId != null && String(a.topicId) === f)
})

/** 把弹窗里的下拉值还原成入库用的 topicId（哨兵 → null = 未归类） */
const toTopicId = (value: string): number | null =>
  value === NONE_TOPIC ? null : Number(value)

/** 主题管理弹窗 */
const showTopicModal = ref(false)
const savingTopic = ref(false)
const newTopicName = ref('')
const newTopicDescription = ref('')

// ---------- 主题编辑 ----------
const showTopicEditModal = ref(false)
const savingTopicEdit = ref(false)
const editingTopicId = ref<number | null>(null)
const editTopicName = ref('')
const editTopicDescription = ref('')
const editTopicSortOrder = ref<number>(0)

const showUploadModal = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const uploadFile = ref<UploadFile[]>([])
const audioName = ref('')
const audioDescription = ref('')
/** 上传时选的所属主题，默认「不归属主题」，保持老行为不变 */
const audioTopic = ref<string>(NONE_TOPIC)
// 用于在取消时中断正在上传的请求
const uploadAbort = ref<AbortController | null>(null)

// ---------- 参考音频编辑 ----------
const showEditModal = ref(false)
const savingEdit = ref(false)
const editingAudioId = ref<number | null>(null)
const editName = ref('')
const editDescription = ref('')
const editTopic = ref<string>(NONE_TOPIC)

const openEdit = (audio: AdminReferenceAudio) => {
  editingAudioId.value = audio.id
  editName.value = audio.name
  editDescription.value = audio.description || ''
  // 无归属（null）落到哨兵项，否则反解回原主题
  editTopic.value = audio.topicId != null ? String(audio.topicId) : NONE_TOPIC
  showEditModal.value = true
}

const confirmEdit = async () => {
  if (!editingAudioId.value) return
  if (!editName.value.trim()) {
    message.warning('名称不能为空')
    return
  }
  savingEdit.value = true
  try {
    const response = await adminApi.updateReferenceAudio(editingAudioId.value, {
      name: editName.value.trim(),
      description: editDescription.value,
      // 显式下发 topicId（含 null）：不传会被服务端理解为「不改主题」，就没法把音频移出主题
      topicId: toTopicId(editTopic.value),
    })
    if (response.data.success) {
      message.success('参考音频已更新')
      showEditModal.value = false
      // 改了归属会牵动各主题的音频数，两个列表都要刷
      loadAudios()
      loadTopics()
    } else {
      message.error(response.data.message || '编辑失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '编辑失败')
    }
  } finally {
    savingEdit.value = false
  }
}

/** 上传前校验：格式与大小提前拦截（与模板 accept 列表一致，含视频自动提取音频） */
const validateAudioFile = (file: File): boolean => {
  const ext = file.name.split('.').pop()?.toLowerCase() || ''
  const allowed = ['wav', 'mp3', 'flac', 'm4a', 'aac', 'ogg', 'mp4', 'avi', 'mkv', 'mov', 'flv', 'wmv', 'webm', 'm4v', '3gp']
  if (!allowed.includes(ext)) {
    message.warning(`不支持的格式 .${ext}，支持常见音频格式与视频格式（自动提取音频）`)
    return false
  }
  if (file.size > 50 * 1024 * 1024) {
    message.warning('文件大小不能超过 50MB')
    return false
  }
  return true
}

/**
 * 主题单元格。
 * 主题被外部改动删掉时（库里 topic_id 指向已不存在的行）必须显式提示，
 * 静默回落成「未归类」会让管理员以为这条音频本来就没归类，从而永远找不到原因。
 */
const renderTopic = (record: AdminReferenceAudio) => {
  const tid = record.topicId
  if (tid == null) {
    return h(Tag, { style: { color: '#999' } }, () => '未归类')
  }
  const name = topicMap.value.get(tid)
  if (!name) {
    return h(Tooltip, { title: `主题 #${tid} 已不存在，可在「编辑」里重新指定归属` }, () =>
      h(Tag, { color: 'orange' }, () => `#${tid} 已删除`))
  }
  return h(Tooltip, { title: '点击只看该主题下的音频' }, () =>
    h(Tag, {
      color: 'blue',
      style: { cursor: 'pointer' },
      onClick: () => { topicFilter.value = String(tid) },
    }, () => name))
}

const columns: ColumnsType<AdminReferenceAudio> = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
  { title: '名称', dataIndex: 'name', key: 'name', width: 180 },
  {
    title: '主题',
    dataIndex: 'topicId',
    key: 'topicId',
    width: 170,
    customRender: ({ record }) => renderTopic(record),
  },
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
    width: 200,
    customRender: ({ text }) => formatDateTime(text),
  },
  {
    title: '操作',
    key: 'action',
    width: 180,
    fixed: 'right',
    customRender: ({ record }) =>
      h(Space, { size: 'small' }, {
        default: () => [
          h(Button, { size: 'small', icon: h(EditOutlined), onClick: () => openEdit(record) }, () => '编辑'),
          h(Button, { size: 'small', danger: true, icon: h(DeleteOutlined), onClick: () => confirmDelete(record) }, () => '删除'),
        ],
      }),
  },
]

const loadAudios = async () => {
  try {
    loading.value = true
    const response = await adminApi.getReferenceAudios()
    if (response.data.success) {
      audios.value = response.data.content ?? []
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msg = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msg || '加载参考音频列表失败')
    }
  } finally {
    loading.value = false
  }
}

const loadTopics = async () => {
  try {
    topicsLoading.value = true
    const response = await adminApi.getReferenceTopics()
    if (response.data.success) {
      topics.value = response.data.content ?? []
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msgText = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msgText || '加载主题列表失败')
    }
  } finally {
    topicsLoading.value = false
  }
}

const handleUploadFileChange = (info: { file: UploadFile; fileList: UploadFile[] }) => {
  if (info.fileList.length > 0) {
    uploadFile.value = info.fileList.slice(-1)
  } else {
    uploadFile.value = []
  }
}

const confirmUpload = async () => {
  // before-upload 返回 false 时，不同版本的 ant-design-vue 可能把原始 File 放在
  // originFileObj，也可能直接把 File 自身塞进 fileList，这里两种形态都兼容
  const picked = uploadFile.value[0] as (UploadFile & { originFileObj?: File }) | undefined
  const file = (picked?.originFileObj ?? (picked as unknown as File | undefined)) as File | undefined
  if (!file || typeof file.size !== 'number') {
    message.warning('请选择音频文件')
    return
  }

  if (!validateAudioFile(file)) return

  const controller = new AbortController()
  uploadAbort.value = controller

  uploading.value = true
  uploadProgress.value = 0
  try {
    const response = await adminApi.uploadReferenceAudio(
      file,
      audioName.value || undefined,
      audioDescription.value || undefined,
      toTopicId(audioTopic.value),
      (p) => (uploadProgress.value = p),
      controller.signal,
    )
    if (response.data.success) {
      message.success('参考音频上传成功')
      showUploadModal.value = false
      resetUploadForm()
      // 归属变了，主题的音频数跟着变
      loadAudios()
      loadTopics()
    } else {
      message.error(response.data.message || '上传失败')
    }
  } catch (error: unknown) {
    // 主动取消上传：不要弹出"上传成功"，也不要当作失败报错
    if (isCancel(error)) {
      return
    }
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

const handleUploadCancel = () => {
  if (uploadAbort.value) {
    uploadAbort.value.abort()
    uploadAbort.value = null
  }
  resetUploadForm()
}

const resetUploadForm = () => {
  uploadFile.value = []
  audioName.value = ''
  audioDescription.value = ''
  audioTopic.value = NONE_TOPIC
}

const confirmDelete = (audio: AdminReferenceAudio) => {
  Modal.confirm({
    title: '确认删除',
    icon: () => h(ExclamationCircleOutlined),
    content: `确定要删除参考音频「${audio.name}」吗？此操作不可恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const response = await adminApi.deleteReferenceAudio(audio.id)
        if (response.data.success) {
          message.success('参考音频已删除')
          // 删音频会让原主题的计数减少
          loadAudios()
          loadTopics()
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

// ---------- 主题增删改 ----------

const submitNewTopic = async () => {
  const name = newTopicName.value.trim()
  if (!name) {
    message.warning('主题名称不能为空')
    return
  }
  savingTopic.value = true
  try {
    const response = await adminApi.createReferenceTopic({
      name,
      description: newTopicDescription.value || undefined,
    })
    if (response.data.success) {
      message.success('主题已创建')
      newTopicName.value = ''
      newTopicDescription.value = ''
      loadTopics()
    } else {
      message.error(response.data.message || '创建主题失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      // 重名等业务校验失败：服务端 message 是「主题名称已存在：xxx」，直接透出更有用
      const msgText = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msgText || '创建主题失败')
    }
  } finally {
    savingTopic.value = false
  }
}

const openTopicEdit = (topic: AdminReferenceTopic) => {
  editingTopicId.value = topic.id
  editTopicName.value = topic.name
  editTopicDescription.value = topic.description || ''
  editTopicSortOrder.value = topic.sortOrder ?? 0
  showTopicEditModal.value = true
}

const confirmTopicEdit = async () => {
  if (!editingTopicId.value) return
  const name = editTopicName.value.trim()
  if (!name) {
    message.warning('主题名称不能为空')
    return
  }
  savingTopicEdit.value = true
  try {
    const response = await adminApi.updateReferenceTopic(editingTopicId.value, {
      name,
      description: editTopicDescription.value,
      sortOrder: editTopicSortOrder.value,
    })
    if (response.data.success) {
      message.success('主题已更新')
      showTopicEditModal.value = false
      loadTopics()
    } else {
      message.error(response.data.message || '编辑主题失败')
    }
  } catch (error: unknown) {
    if (!isHandledByInterceptor(error)) {
      const msgText = isAxiosError(error) ? error.response?.data?.message : null
      message.error(msgText || '编辑主题失败')
    }
  } finally {
    savingTopicEdit.value = false
  }
}

const confirmDeleteTopic = (topic: AdminReferenceTopic) => {
  Modal.confirm({
    title: '确认删除主题',
    icon: () => h(ExclamationCircleOutlined),
    content: topic.audioCount
      ? `主题「${topic.name}」下还有 ${topic.audioCount} 个参考音频，需要先把这些音频移出主题或删除，才能删除该主题。`
      : `确定要删除主题「${topic.name}」吗？`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const response = await adminApi.deleteReferenceTopic(topic.id)
        if (response.data.success) {
          message.success('主题已删除')
          // 若正按这个主题筛选，删掉后筛选值会失效，回到「全部」
          if (topicFilter.value === String(topic.id)) {
            topicFilter.value = 'all'
          }
          loadTopics()
        } else {
          message.error(response.data.message || '删除主题失败')
        }
      } catch (error: unknown) {
        if (!isHandledByInterceptor(error)) {
          // 主题非空时服务端会拒删并带上剩余数量，原样透出引导管理员先移音频
          const msgText = isAxiosError(error) ? error.response?.data?.message : null
          message.error(msgText || '删除主题失败')
        }
      }
    },
  })
}

const topicColumns: ColumnsType<AdminReferenceTopic> = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
  { title: '主题名称', dataIndex: 'name', key: 'name', width: 180 },
  {
    title: '描述',
    dataIndex: 'description',
    key: 'description',
    ellipsis: true,
    customRender: ({ text }) => text || '-',
  },
  {
    title: '音频数',
    dataIndex: 'audioCount',
    key: 'audioCount',
    width: 90,
    customRender: ({ text }) => `${text ?? 0} 个`,
  },
  {
    title: '排序',
    dataIndex: 'sortOrder',
    key: 'sortOrder',
    width: 80,
    customRender: ({ text }) => (text ?? 0),
  },
  {
    title: '操作',
    key: 'action',
    width: 150,
    customRender: ({ record }) =>
      h(Space, { size: 'small' }, {
        default: () => [
          h(Button, { size: 'small', icon: h(EditOutlined), onClick: () => openTopicEdit(record) }, () => '编辑'),
          h(Button, {
            size: 'small',
            danger: true,
            icon: h(DeleteOutlined),
            // 非空主题直接禁用：与其让管理员点一次再被服务端拒绝，不如当场说明原因
            disabled: !!record.audioCount,
            onClick: () => confirmDeleteTopic(record),
          }, () => '删除'),
        ],
      }),
  },
]

onMounted(() => {
  loadAudios()
  loadTopics()
})

// 切换路由（组件卸载）时若有正在进行的上传请求，主动取消，避免游离请求
onUnmounted(() => {
  uploadAbort.value?.abort()
})
</script>

<template>
  <Card>
    <div class="header">
      <Title :level="4">
        <AudioOutlined /> 参考音频管理
      </Title>
      <Space>
        <Button @click="loadAudios(); loadTopics()">
          刷新
        </Button>
        <Button @click="showTopicModal = true">
          <FolderOutlined /> 主题管理
        </Button>
        <Button type="primary" @click="showUploadModal = true">
          <UploadOutlined /> 上传参考音频
        </Button>
      </Space>
    </div>

    <div class="filter-bar">
      <Select
        v-model:value="topicFilter"
        :options="topicOptions"
        :loading="topicsLoading"
        style="width: 280px"
        placeholder="按主题筛选"
      />
      <span class="hint">
        同一来源的素材可归入同一主题（如「电视剧雍正王朝」下放各角色台词片段），点击表格里的主题标签可快速筛选
      </span>
    </div>

    <Table
      :columns="columns"
      :data-source="filteredAudios"
      row-key="id"
      :loading="loading"
      :pagination="{
        pageSize: 10,
        showSizeChanger: true,
        showTotal: (total: number) => `共 ${total} 条`,
      }"
    />

    <Modal
      v-model:open="showUploadModal"
      title="上传参考音频"
      :ok-text="'上传'"
      :cancel-text="'取消'"
      :confirm-loading="uploading"
      @ok="confirmUpload"
      @cancel="handleUploadCancel"
      width="600px"
    >
      <div class="upload-form">
        <div class="form-item">
          <label>音频文件 *</label>
          <Upload
            :file-list="uploadFile"
            :max-count="1"
            accept=".wav,.mp3,.flac,.aac,.ogg,.m4a,.mp4,.avi,.mkv,.mov,.flv,.wmv,.webm,.m4v,.3gp"
            :show-upload-list="true"
            :before-upload="() => false"
            @change="handleUploadFileChange"
          >
            <Button>
              <UploadOutlined /> 选择文件
            </Button>
          </Upload>
          <span class="hint">支持音频格式（WAV/MP3）和视频格式（会自动提取音频），最大 50MB</span>
        </div>

        <div v-if="uploading" class="form-item">
          <Progress :percent="uploadProgress" :status="uploadProgress >= 100 ? 'success' : 'active'" />
          <span v-if="uploadProgress >= 100" class="hint">文件已传完，服务器正在转码保存（视频需提取音频），请稍候…</span>
        </div>

        <div class="form-item">
          <label>名称（可选，留空则使用文件名）</label>
          <Input v-model:value="audioName" placeholder="留空则使用文件名" />
        </div>

        <div class="form-item">
          <label>描述</label>
          <Input.TextArea v-model:value="audioDescription" :rows="2" placeholder="音频描述（可选）" />
        </div>

        <div class="form-item">
          <label>所属主题</label>
          <Select
            v-model:value="audioTopic"
            :options="assignableTopicOptions"
            :loading="topicsLoading"
            placeholder="选择所属主题（可不选）"
          />
          <span class="hint">还没有主题？先在「主题管理」里建一个，例如「电视剧雍正王朝」</span>
        </div>
      </div>
    </Modal>

    <Modal
      v-model:open="showEditModal"
      title="编辑参考音频"
      :ok-text="'保存'"
      :cancel-text="'取消'"
      :confirm-loading="savingEdit"
      @ok="confirmEdit"
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
          <label>所属主题</label>
          <Select
            v-model:value="editTopic"
            :options="assignableTopicOptions"
            :loading="topicsLoading"
            placeholder="选择所属主题（可不选）"
          />
          <span class="hint">改成「不归属主题」即可把音频移出当前主题</span>
        </div>
      </div>
    </Modal>

    <Modal
      v-model:open="showTopicModal"
      title="主题管理"
      :footer="null"
      width="860px"
      :destroy-on-close="true"
    >
      <div class="topic-panel">
        <div class="topic-create">
          <Input
            v-model:value="newTopicName"
            placeholder="新主题名称，如：电视剧雍正王朝"
            :maxlength="200"
            @press-enter="submitNewTopic"
          />
          <Input
            v-model:value="newTopicDescription"
            placeholder="主题描述（可选），如：雍正王朝各角色台词片段"
          />
          <Button type="primary" :loading="savingTopic" @click="submitNewTopic">
            <PlusOutlined /> 新建主题
          </Button>
        </div>
        <Table
          :columns="topicColumns"
          :data-source="topics"
          row-key="id"
          size="small"
          :loading="topicsLoading"
          :pagination="false"
          :scroll="{ y: 360 }"
        />
      </div>
    </Modal>

    <Modal
      v-model:open="showTopicEditModal"
      title="编辑主题"
      :ok-text="'保存'"
      :cancel-text="'取消'"
      :confirm-loading="savingTopicEdit"
      @ok="confirmTopicEdit"
      width="600px"
    >
      <div class="upload-form">
        <div class="form-item">
          <label>主题名称 *</label>
          <Input v-model:value="editTopicName" placeholder="主题名称" />
          <span class="hint">主题名全局唯一；改名不影响已归属的音频（归属按主题 id 关联）</span>
        </div>

        <div class="form-item">
          <label>描述</label>
          <Input.TextArea v-model:value="editTopicDescription" :rows="2" placeholder="主题描述（可选）" />
        </div>

        <div class="form-item">
          <label>排序</label>
          <InputNumber
            v-model:value="editTopicSortOrder"
            :min="0"
            :max="9999"
            style="width: 160px"
          />
          <span class="hint">数值越小越靠前，同样大小按创建顺序排列</span>
        </div>
      </div>
    </Modal>
  </Card>
</template>

<style scoped>
.header {
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filter-bar {
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.topic-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.topic-create {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
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
</style>
