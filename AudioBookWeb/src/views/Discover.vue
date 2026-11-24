<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue'
import {
  Tabs,
  TabPane,
  List,
  Button,
  Input,
  Card,
  Tag,
  Empty,
  message,
} from 'ant-design-vue'
import {
  FireOutlined,
  MessageOutlined,
  PlayCircleOutlined,
  StopOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons-vue'
import { discoveryApi, type Post, type SharedReference } from '@/services/discovery'
import { formatFileSize, formatDateTimeShort } from '@audiobook/shared'
import { useAudioPreview } from '@/composables/useAudioPreview'

const activeTab = ref<string>('rank')
const MAX_CONTENT = 500

// ---------------- Tab 1：参考音频排行 ----------------
const references = ref<SharedReference[]>([])
const rankLoading = ref(false)

// 试听统一交给 useAudioPreview：自动处理单实例播放、blob URL 释放、自动播放拒绝与组件卸载清理
const { previewingId, toggle: togglePreviewById } = useAudioPreview(
  (id) => discoveryApi.previewSharedReference(id),
  () => message.error('试听失败'),
)

const loadReferences = async () => {
  try {
    rankLoading.value = true
    const res = await discoveryApi.listSharedReferences(50)
    if (res.data.success) {
      references.value = res.data.references || []
    } else {
      message.error(res.data.message || '加载参考音频排行失败')
    }
  } catch {
    message.error('加载参考音频排行失败，请稍后重试')
  } finally {
    rankLoading.value = false
  }
}

const togglePreview = (row: SharedReference) => togglePreviewById(row.id)

const handleUse = async (row: SharedReference) => {
  try {
    const res = await discoveryApi.useSharedReference(row.id)
    if (res.data.success) {
      if (res.data.counted) {
        row.useCount = res.data.useCount
        message.success('已记录使用，感谢分享！')
      } else {
        message.info('这是你自己分享的音频，不计入使用量')
      }
    } else {
      message.error(res.data.message || '操作失败')
    }
  } catch {
    message.error('操作失败，请稍后重试')
  }
}

// ---------------- Tab 2：讨论 ----------------
const posts = ref<Post[]>([])
const discussLoading = ref(false)
const loadingMore = ref(false)
const page = ref(0)
const hasMore = ref(false)
const total = ref(0)

const draft = ref('')
const posting = ref(false)
// 按 UTF-16 码元计数，与 antd Input.TextArea :maxlength（按 UTF-16 码元）口径一致，
// 含 emoji 时计数与可输入上限不再错位，canPost 不会被误判
const contentLen = computed(() => draft.value.length)
const canPost = computed(
  () => draft.value.trim().length > 0 && contentLen.value <= MAX_CONTENT && !posting.value,
)

const loadPosts = async (reset: boolean) => {
  // v5/W4：加载更多必须有在途守卫。原实现无守卫，连点会让同一页被请求多次、
  // 并把同一批帖子重复追加（page 在同一批响应里各自 +1，最终列表出现重复项）。
  if (!reset && loadingMore.value) return
  if (reset) {
    discussLoading.value = true
    page.value = 0
  } else {
    loadingMore.value = true
  }
  try {
    const res = await discoveryApi.listPosts(page.value, 20)
    if (res.data.success) {
      const list = res.data.posts || []
      posts.value = reset ? list : posts.value.concat(list)
      hasMore.value = res.data.hasMore
      total.value = res.data.total
      // 无条件递增：reset 时上面已把 page 归 0 并请求了第 0 页（后端 page 是 0-based），
      // 若只在「加载更多」时递增，首次点「加载更多」会再次请求 page=0，
      // 把第 1 页的内容重复追加到列表尾部。
      page.value += 1
    } else {
      message.error(res.data.message || '加载帖子失败')
    }
  } catch {
    message.error('加载帖子失败，请稍后重试')
  } finally {
    discussLoading.value = false
    loadingMore.value = false
  }
}

const handlePost = async () => {
  if (!canPost.value) return
  posting.value = true
  try {
    const res = await discoveryApi.createPost(draft.value.trim())
    if (res.data.success) {
      const newPost: Post = {
        id: res.data.id,
        username: res.data.username,
        content: res.data.content,
        createdAt: res.data.createdAt,
      }
      posts.value.unshift(newPost)
      total.value += 1
      draft.value = ''
      message.success('发布成功')
    } else {
      message.error(res.data.message || '发布失败')
    }
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
    message.error(msg || '发布失败，请稍后重试')
  } finally {
    posting.value = false
  }
}

onMounted(() => {
  loadReferences()
  loadPosts(true)
})
</script>

<template>
  <div class="discover">
    <Tabs v-model:activeKey="activeTab" class="discover-tabs">
      <!-- Tab 1：参考音频分享 / 排行 -->
      <TabPane key="rank">
        <template #tab><span><FireOutlined /> 参考音频排行</span></template>

        <div class="rank-toolbar">
          <span class="rank-count">共 {{ references.length }} 个分享音频</span>
          <Button size="small" @click="loadReferences">
            <ReloadOutlined /> 刷新
          </Button>
        </div>

        <List :loading="rankLoading" class="ref-list">
          <List.Item v-for="item in references" :key="item.id" class="ref-item">
            <div class="ref-rank" :class="{ top: item.rank <= 3 }">{{ item.rank }}</div>
            <div class="ref-body">
              <div class="ref-title">
                <span class="ref-name">{{ item.name }}</span>
                <Tag v-if="item.mine" color="blue">我的</Tag>
                <Tag color="default">已用 {{ item.useCount }}</Tag>
              </div>
              <div class="ref-meta">
                <span>分享者：{{ item.ownerName }}</span>
                <span>{{ formatFileSize(item.fileSize) }}</span>
                <span v-if="item.sharedAt">分享于 {{ formatDateTimeShort(item.sharedAt) }}</span>
              </div>
              <div v-if="item.description" class="ref-desc">{{ item.description }}</div>
            </div>
            <div class="ref-actions">
              <Button
                size="small"
                :icon="h(previewingId === item.id ? StopOutlined : PlayCircleOutlined)"
                @click="togglePreview(item)"
              >
                {{ previewingId === item.id ? '停止' : '试听' }}
              </Button>
              <Button size="small" type="primary" @click="handleUse(item)">使用</Button>
            </div>
          </List.Item>
        </List>

        <Empty
          v-if="!rankLoading && references.length === 0"
          description="还没有人分享参考音频"
        />
      </TabPane>

      <!-- Tab 2：讨论 -->
      <TabPane key="discuss">
        <template #tab><span><MessageOutlined /> 讨论</span></template>

        <Card class="post-box" :bordered="false">
          <Input.TextArea
            v-model:value="draft"
            :rows="3"
            :maxlength="MAX_CONTENT"
            placeholder="说点什么吧……（纯文字，最多 500 字）"
          />
          <div class="post-actions">
            <span class="counter" :class="{ over: contentLen > MAX_CONTENT }">
              {{ contentLen }} / {{ MAX_CONTENT }}
            </span>
            <Button
              type="primary"
              :icon="h(PlusOutlined)"
              :disabled="!canPost"
              :loading="posting"
              @click="handlePost"
            >
              发布
            </Button>
          </div>
        </Card>

        <List :loading="discussLoading" class="post-list">
          <List.Item v-for="item in posts" :key="item.id" class="post-item">
            <div class="post-avatar">{{ (item.username || '?').slice(0, 1).toUpperCase() }}</div>
            <div class="post-body">
              <div class="post-head">
                <span class="post-user">{{ item.username }}</span>
                <span v-if="item.createdAt" class="post-time">{{ formatDateTimeShort(item.createdAt) }}</span>
              </div>
              <div class="post-content">{{ item.content }}</div>
            </div>
          </List.Item>
        </List>

        <div v-if="!discussLoading && posts.length === 0" class="empty-posts">
          <Empty description="还没有帖子，来发第一条吧" />
        </div>

        <div v-if="hasMore" class="load-more">
          <!-- v5/W4：:loading 只挡视觉，不挡点击；必须同时 :disabled -->
          <Button :loading="loadingMore" :disabled="loadingMore" @click="loadPosts(false)">加载更多</Button>
        </div>
        <div v-else-if="posts.length > 0" class="load-more">
          <span class="no-more">没有更多了</span>
        </div>
      </TabPane>
    </Tabs>
  </div>
</template>

<style scoped>
.discover {
  max-width: 880px;
  margin: 0 auto;
}

.discover-tabs :deep(.ant-tabs-nav) {
  margin-bottom: 16px;
}

/* ----- 参考音频排行 ----- */
.rank-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.rank-count {
  color: #888;
  font-size: 13px;
}

.ref-list .ref-item {
  align-items: flex-start;
  padding: 14px 8px;
}

.ref-rank {
  flex: 0 0 28px;
  height: 28px;
  line-height: 28px;
  text-align: center;
  border-radius: 50%;
  background: #f0f0f0;
  color: #666;
  font-weight: 600;
  margin-right: 12px;
}

.ref-rank.top {
  background: #fff1f0;
  color: #cf1322;
}

.ref-body {
  flex: 1 1 auto;
  min-width: 0;
}

.ref-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ref-name {
  font-weight: 600;
  font-size: 15px;
}

.ref-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: #999;
  font-size: 12px;
  margin-top: 4px;
}

.ref-desc {
  margin-top: 6px;
  color: #555;
  font-size: 13px;
  word-break: break-word;
}

.ref-actions {
  flex: 0 0 auto;
  display: flex;
  gap: 8px;
  margin-left: 12px;
}

/* ----- 讨论 ----- */
.post-box {
  margin-bottom: 16px;
  background: #fafafa;
}

.post-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}

.counter {
  font-size: 12px;
  color: #999;
}

.counter.over {
  color: #cf1322;
}

.post-list .post-item {
  align-items: flex-start;
  padding: 14px 4px;
}

.post-avatar {
  flex: 0 0 36px;
  height: 36px;
  line-height: 36px;
  text-align: center;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  font-weight: 600;
  margin-right: 12px;
}

.post-body {
  flex: 1 1 auto;
  min-width: 0;
}

.post-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.post-user {
  font-weight: 600;
}

.post-time {
  color: #bbb;
  font-size: 12px;
}

.post-content {
  margin-top: 4px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}

.empty-posts {
  padding: 40px 0;
}

.load-more {
  text-align: center;
  padding: 16px 0;
}

.no-more {
  color: #bbb;
  font-size: 13px;
}

/* 夜间模式：排行榜/讨论区的自定义浅色块与深灰文字在暗色下翻转为深色主题色 */
.dark .rank-count {
  color: rgba(255, 255, 255, 0.65);
}

.dark .ref-rank {
  background: #262626;
  color: rgba(255, 255, 255, 0.65);
}

.dark .ref-rank.top {
  background: #2b1f1f;
  color: #ff7875;
}

.dark .ref-meta {
  color: rgba(255, 255, 255, 0.45);
}

.dark .ref-desc {
  color: rgba(255, 255, 255, 0.85);
}

.dark .post-box {
  background: #1f1f1f;
}

/* 超字数计数在调深后的 .post-box 上仅约 3.0:1，改用 antd dark 的 error-4 */
.dark .counter.over {
  color: #ff7875;
}
</style>
