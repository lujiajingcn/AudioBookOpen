import { message } from 'ant-design-vue'
import { useConfigStore } from '@/stores/config'
import { formatFileSize } from '@audiobook/shared'

// 支持的格式（音频 + 视频，视频后端自动提取音轨转 WAV）
export const ALLOWED_EXT = [
  'wav',
  'mp3',
  'flac',
  'aac',
  'ogg',
  'm4a',
  'mp4',
  'avi',
  'mkv',
  'mov',
  'flv',
  'wmv',
  'webm',
  'm4v',
  '3gp',
]
export const ACCEPT = '.' + ALLOWED_EXT.join(',.')

/** 校验上传的参考音频文件：扩展名 + 大小（取自 configStore.maxFileSizeBytes）。
 *  供参考音频库与「我的参考音频」上传入口复用，避免各页面各写一份校验导致口径漂移。 */
export const validateAudioFile = (file: File): boolean => {
  const configStore = useConfigStore()
  const ext = file.name.split('.').pop()?.toLowerCase() || ''
  if (!ALLOWED_EXT.includes(ext)) {
    message.warning(`不支持的格式 .${ext}，支持常见音频格式与视频格式（视频会自动提取音频）`)
    return false
  }
  if (file.size > configStore.maxFileSizeBytes) {
    message.warning(`文件大小不能超过 ${formatFileSize(configStore.maxFileSizeBytes)}`)
    return false
  }
  return true
}
