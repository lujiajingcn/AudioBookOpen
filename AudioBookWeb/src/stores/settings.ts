import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 显示与阅读偏好（Web 端）：夜间模式 + 阅读字号 + 界面整体缩放。
 *
 * 与 Android 端 AppPrefs 同源：本地持久化（localStorage），无后端依赖。
 * - nightMode：是否启用暗色主题，驱动 <html class="dark"> 与 Ant Design 的 darkAlgorithm。
 * - readerFontSize：阅读/在线听书正文的 px 字号，注入到 --reader-font-size CSS 变量，
 *   Player 的 .now-text / .seg-text 引用它，实现「晚上听/读不刺眼 + 字号可调」。
 * - uiScale：界面整体缩放（zoom），覆盖其余固定 px 文本场景。
 */
const K_NIGHT = 'ab_appearance_night'
const K_READER_FS = 'ab_appearance_reader_fs'
const K_UI_SCALE = 'ab_appearance_ui_scale'

function loadBool(key: string, def: boolean): boolean {
  const v = localStorage.getItem(key)
  if (v == null) return def
  return v === '1'
}

function loadNum(key: string, def: number): number {
  const v = localStorage.getItem(key)
  const n = v == null ? NaN : Number(v)
  return Number.isFinite(n) ? n : def
}

export const useSettingsStore = defineStore('settings', () => {
  const nightMode = ref(loadBool(K_NIGHT, false))
  const readerFontSize = ref(loadNum(K_READER_FS, 18))
  const uiScale = ref(loadNum(K_UI_SCALE, 1))

  function apply() {
    const root = document.documentElement
    root.classList.toggle('dark', nightMode.value)
    root.style.setProperty('--reader-font-size', `${readerFontSize.value}px`)
    const app = document.getElementById('app')
    if (app) (app.style as any).zoom = String(uiScale.value)
  }

  function setNight(v: boolean) {
    nightMode.value = v
    localStorage.setItem(K_NIGHT, v ? '1' : '0')
    apply()
  }

  function setReaderFontSize(px: number) {
    readerFontSize.value = px
    localStorage.setItem(K_READER_FS, String(px))
    apply()
  }

  function setUiScale(s: number) {
    uiScale.value = s
    localStorage.setItem(K_UI_SCALE, String(s))
    apply()
  }

  function init() {
    apply()
  }

  return { nightMode, readerFontSize, uiScale, setNight, setReaderFontSize, setUiScale, init }
})
