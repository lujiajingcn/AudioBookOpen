/**
 * 电子书/任务音频播放引擎（从 Player.vue / Reader.vue 抽出的独立 composable）。
 *
 * 设计目标：默认「逐个片段顺序播放一次」。
 * 实现：Web Audio 路径——解码 → 预调度（armNext 在精确结束时刻预排下一段）→
 * 段结束账本推进并链式预排下下段 → 解码后头尾静音裁剪 → 每段两端增益淡入淡出。
 * 自动连续播放靠 AudioBufferSourceNode.onended 链式推进；armNext 在解码慢于当前段
 * 剩余时长时会兜底立即调度下一段，杜绝旧版「整句跳过」竞态。
 *
 * 公共 API：状态（isPlaying/buffering/currentTime/duration/playbackRate/volume/
 * currentIndex/currentSegment/RATE_OPTIONS）+ 控制（playSegment/nextSegment/prevSegment/
 * seekTo/onSeek/skip/onRateChange/onVolumeChange/togglePlay/pause/teardown/
 * invalidateBuffers）+ 睡眠定时。
 */
import { ref, computed, onUnmounted } from 'vue'
import type { Ref, ComputedRef } from 'vue'
import { message } from 'ant-design-vue'
import { ttsApi, assertDownloadable, extractBlobErrorMessage, type SegmentInfo } from '@/services/api'
import { usePlayProgress, type PlayProgress } from './usePlayProgress'

export interface AudioEngineOptions {
  fetchSegment?: (seg: SegmentInfo) => Promise<ArrayBuffer>
  progressStore?: {
    save: (id: number, segmentNumber: number, offset?: number) => void
    load: (id: number) => Promise<PlayProgress | null>
  }
}

export function useAudioEngine(
  segments: Ref<SegmentInfo[]>,
  taskId: ComputedRef<number | null>,
  options: AudioEngineOptions = {},
) {
  // ---------- 播放器可被模板直接消费的状态 ----------
  const currentIndex = ref(-1)
  const isPlaying = ref(false)
  const buffering = ref(false)
  const currentTime = ref(0)
  const duration = ref(0)
  const playbackRate = ref(1)
  const volume = ref(1)
  const RATE_OPTIONS = [0.75, 1, 1.25, 1.5, 2]

  const currentSegment = computed(() =>
    currentIndex.value >= 0 ? segments.value[currentIndex.value] : null,
  )

  let ended = false
  let disposed = false

  const progress = options.progressStore ?? usePlayProgress()

  // ===== 睡眠定时 =====
  const SLEEP_TIMER_OFF = 0
  const SLEEP_TIMER_END_OF_SEGMENT = -1
  const SLEEP_TIMER_SEGMENTS = -2
  const sleepTimerMode = ref(SLEEP_TIMER_OFF)
  const sleepTimerRemaining = ref(0)
  const sleepTimerSegmentsLeft = ref(0)
  let sleepDeadline = 0
  let sleepTickId: number | null = null

  const clearSleepTick = () => {
    if (sleepTickId !== null) {
      clearInterval(sleepTickId)
      sleepTickId = null
    }
  }
  const resetSleepState = () => {
    clearSleepTick()
    sleepTimerMode.value = SLEEP_TIMER_OFF
    sleepTimerRemaining.value = 0
    sleepTimerSegmentsLeft.value = 0
    sleepDeadline = 0
  }
  const sleepStopsAtBoundary = (): boolean =>
    sleepTimerMode.value === SLEEP_TIMER_END_OF_SEGMENT ||
    (sleepTimerMode.value === SLEEP_TIMER_SEGMENTS && sleepTimerSegmentsLeft.value <= 1)
  const fireSleepTimer = () => {
    clearSleepTick()
    sleepTimerMode.value = SLEEP_TIMER_OFF
    sleepTimerRemaining.value = 0
    if (currentIndex.value < 0) return
    waPauseForSleep()
    isPlaying.value = false
    message.info('睡眠定时已到，已暂停播放')
  }
  const setSleepTimer = (mode: number) => {
    clearSleepTick()
    sleepTimerRemaining.value = 0
    sleepTimerSegmentsLeft.value = 0
    if (mode > 0) {
      sleepTimerMode.value = mode
      sleepDeadline = Date.now() + mode * 60_000
      sleepTimerRemaining.value = mode * 60_000
      sleepTickId = window.setInterval(() => {
        const rem = sleepDeadline - Date.now()
        if (rem <= 0) fireSleepTimer()
        else sleepTimerRemaining.value = rem
      }, 1000)
    } else {
      sleepTimerMode.value = mode
    }
  }
  const setSleepTimerSegments = (count: number) => {
    clearSleepTick()
    sleepTimerRemaining.value = 0
    sleepTimerMode.value = SLEEP_TIMER_SEGMENTS
    sleepTimerSegmentsLeft.value = Math.max(1, Math.floor(count) || 1)
  }

  /** 段音频字节获取器 */
  const fetchSegmentBytes: (seg: SegmentInfo) => Promise<ArrayBuffer> =
    options.fetchSegment ??
    (async (seg) => {
      const res = await ttsApi.downloadSegment(seg.id)
      const blob = await assertDownloadable(res)
      if (!blob || blob.size === 0) {
        throw new Error('该片段音频内容为空，可能合成未完成或文件缺失，请重试该任务')
      }
      return await blob.arrayBuffer()
    })
  const getArrayBuffer = (seg: SegmentInfo): Promise<ArrayBuffer> => fetchSegmentBytes(seg)

  const nextCompletedIndex = (from: number): number => {
    for (let i = from + 1; i < segments.value.length; i++) {
      if (segments.value[i].status === 'completed') return i
    }
    return -1
  }

  const saveProgress = () => {
    if (currentIndex.value < 0) return
    const seg = segments.value[currentIndex.value]
    if (seg && taskId.value != null) {
      const pos = waGetTime()
      progress.save(taskId.value, seg.segmentNumber, pos)
    }
  }

  // ======================================================================
  // Web Audio 播放引擎
  // ======================================================================
  /** 句间间隔（秒）：每句播放结束后静默 0.5s 再起下一句。 */
  const SEGMENT_GAP = 0.5
  // v5/W12：不用 `as any`，用结构化的 unknown 收窄 —— 这里只需要「有没有这个构造器」，
  // 一旦类型写错，audioCtx 的每个方法都会失去类型检查。
  const AudioContextCtor: typeof AudioContext | undefined =
    window.AudioContext ||
    (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
  let audioCtx: AudioContext | null = null
  let waMasterGain: GainNode | null = null
  let waSource: AudioBufferSourceNode | null = null
  const waBuffers = new Map<number, AudioBuffer>()
  const waScheduled: { index: number; gen: number; src: AudioBufferSourceNode }[] = []
  let waPlayingIndex = -1
  let waSegStartCtxTime = 0
  let waSegStartOffset = 0
  let waPausedOffset = 0
  let waGen = 0
  let rafId: number | null = null

  const ensureCtx = (): AudioContext => {
    if (!audioCtx) {
      audioCtx = new AudioContextCtor!()
      waMasterGain = audioCtx.createGain()
      waMasterGain.gain.value = volume.value
      waMasterGain.connect(audioCtx.destination)
    }
    if (audioCtx.state === 'suspended') void audioCtx.resume()
    return audioCtx
  }

  // 连播期间用 RAF 平滑推进进度条（Web Audio 没有 timeupdate 事件）
  const startProgressTick = () => {
    if (rafId !== null) return
    const tick = () => {
      if (isPlaying.value && waPlayingIndex >= 0) {
        currentTime.value = waGetTime()
      }
      rafId = requestAnimationFrame(tick)
    }
    rafId = requestAnimationFrame(tick)
  }
  const stopProgressTick = () => {
    if (rafId !== null) {
      cancelAnimationFrame(rafId)
      rafId = null
    }
  }

  const trimSilence = (buf: AudioBuffer, threshold = 0.01): AudioBuffer => {
    const ch = buf.numberOfChannels
    const len = buf.length
    const d0 = buf.getChannelData(0)
    let s = 0
    let e = len - 1
    while (s < len && Math.abs(d0[s]) < threshold) s++
    while (e > s && Math.abs(d0[e]) < threshold) e--
    const newLen = Math.max(1, e - s + 1)
    const out = audioCtx!.createBuffer(ch, newLen, buf.sampleRate)
    for (let c = 0; c < ch; c++) {
      const src = buf.getChannelData(c)
      out.getChannelData(c).set(src.subarray(s, e + 1))
    }
    return out
  }

  const waDecode = async (index: number): Promise<AudioBuffer> => {
    const cached = waBuffers.get(index)
    if (cached) return cached
    const seg = segments.value[index]
    const ab = await getArrayBuffer(seg)
    const ctx = ensureCtx()
    let buf = await ctx.decodeAudioData(ab.slice(0))
    buf = trimSilence(buf)
    waBuffers.set(index, buf)
    return buf
  }

  const isScheduled = (index: number, gen: number): boolean =>
    waScheduled.some((s) => s.index === index && s.gen === gen)
  const markScheduled = (index: number, gen: number, src: AudioBufferSourceNode) => {
    waScheduled.push({ index, gen, src })
  }
  const removeScheduled = (index: number, gen: number) => {
    for (let i = waScheduled.length - 1; i >= 0; i--) {
      if (waScheduled[i].index === index && waScheduled[i].gen === gen) waScheduled.splice(i, 1)
    }
  }

  const waCreateSource = (index: number, gen: number, when: number): AudioBufferSourceNode => {
    const ctx = ensureCtx()
    const buf = waBuffers.get(index)!
    const src = ctx.createBufferSource()
    src.buffer = buf
    src.playbackRate.value = playbackRate.value
    // 每段两端增益淡入淡出，避免句首/句尾爆音与硬切。
    // 关键：增益曲线必须绑定「实际起播时刻 when」，而非创建 source 的时刻。
    // 预调度段在 armNext 里于未来时刻 start(whenEnd)，若用 ctx.currentTime 设曲线，
    // 曲线会在音频真正开始前就 ramp 到 0，导致该段被全程静音（上一版回归 bug 根因）。
    // 时长按 playbackRate 折算，保证倍速下淡出仍对齐实际结束点。
    const g = ctx.createGain()
    const playDur = buf.duration / Math.max(0.01, playbackRate.value)
    const fade = Math.min(0.02, playDur / 4)
    g.gain.setValueAtTime(0, when)
    g.gain.linearRampToValueAtTime(volume.value, when + fade)
    g.gain.setValueAtTime(volume.value, when + Math.max(fade, playDur - fade))
    g.gain.linearRampToValueAtTime(0, when + playDur)
    src.connect(g)
    g.connect(waMasterGain!)
    src.onended = () => waOnSourceEnded(index, gen)
    return src
  }

  const waStart = (index: number, offset: number, when: number, scheduleNext = true) => {
    const gen = ++waGen
    const src = waCreateSource(index, gen, when)
    waSource = src
    waPlayingIndex = index
    waSegStartCtxTime = when
    waSegStartOffset = offset
    currentIndex.value = index
    currentTime.value = offset
    duration.value = waBuffers.get(index)?.duration ?? 0
    src.start(when, offset)
    if (scheduleNext) {
      // 下一段起播时刻 = 本段**剩余**播放时长(折算倍速) + 句间间隔（SEGMENT_GAP）。
      // 用剩余而非整段时长：从暂停点恢复（resume, offset>0）时，预排起点对齐
      // 「本段真正结束时刻」，否则恢复后会多一段等于已播放部分的静默。
      const bufDur = waBuffers.get(index)?.duration ?? 0
      const remainingReal = Math.max(0, (bufDur - offset) / Math.max(0.01, playbackRate.value))
      armNext(index, when + remainingReal + SEGMENT_GAP, gen)
    }
    isPlaying.value = true
    startProgressTick()
  }

  const scheduleAt = (index: number, offset: number, when: number, gen: number) => {
    if (isScheduled(index, gen)) return // 防御重复调度
    const src = waCreateSource(index, gen, when)
    markScheduled(index, gen, src)
    src.start(when, offset)
  }

  const armNext = (index: number, whenEnd: number, gen: number) => {
    const nxt = nextCompletedIndex(index)
    if (nxt < 0) return
    const buf = waBuffers.get(nxt)
    if (buf) {
      if (!isScheduled(nxt, gen)) scheduleAt(nxt, 0, whenEnd, gen)
    } else {
      waDecode(nxt)
        .then(() => {
          if (gen !== waGen) return
          const b = waBuffers.get(nxt)
          if (!b) return
          if (isScheduled(nxt, gen)) return // 已被兜底路径起播，避免重复
          const t = audioCtx!.currentTime
          if (whenEnd > t) {
            scheduleAt(nxt, 0, whenEnd, gen)
          } else if (waPlayingIndex === nxt) {
            return // 已在播，避免重复调度
          } else {
            // 边界已过期（含本段仍在播尾音、onended 尚未触发的竞态窗口）：兜底立即排上 nxt。
            // 原版这里裸 return 是「整句跳过」的根因——nxt 既没被预调度排上，
            // ended 又信赖预调度只做账本推进，导致该段零声音被跳过。
            // 现在统一兜底为「立即调度 nxt」（Web Audio 会把过去时刻 clamp 到当前时刻），
            // 即便与当前段尾音有极短暂重叠，也远优于跳过整句。
            scheduleAt(nxt, 0, t, gen)
          }
        })
        .catch(() => {})
    }
  }

  const waAdvanceTo = async (nxt: number, callerGen: number) => {
    if (callerGen !== waGen || disposed) return
    if (isScheduled(nxt, callerGen)) return // 已被预调度排上，避免重复起播
    const ctx = ensureCtx()
    buffering.value = true
    if (!waBuffers.get(nxt)) {
      try {
        await waDecode(nxt)
      } catch (e) {
        if (disposed) return
        isPlaying.value = false
        buffering.value = false
        message.error(await extractBlobErrorMessage(e, '加载下一段音频失败'))
        return
      }
    }
    if (callerGen !== waGen) return
    waSegStartCtxTime = ctx.currentTime
    waSegStartOffset = 0
    waPausedOffset = 0
    waStart(nxt, 0, ctx.currentTime)
    buffering.value = false
  }

  const waOnSourceEnded = (index: number, gen: number) => {
    removeScheduled(index, gen)
    if (gen !== waGen || disposed) return
    saveProgress()
    if (sleepStopsAtBoundary()) {
      isPlaying.value = false
      currentTime.value = 0
      buffering.value = false
      resetSleepState()
      message.info('睡眠定时：已暂停播放')
      return
    }
    if (sleepTimerMode.value === SLEEP_TIMER_SEGMENTS && sleepTimerSegmentsLeft.value > 0) {
      sleepTimerSegmentsLeft.value--
    }
    const nxt = nextCompletedIndex(index)
    if (nxt < 0) {
      isPlaying.value = false
      ended = true
      currentTime.value = 0
      duration.value = 0
      buffering.value = false
      stopProgressTick()
      resetSleepState()
      message.info('已播放完当前所有已合成片段')
      return
    }
    // 段结束即把账本推进到下一段：让 UI 高亮与进度基线跟随。
    // 否则预调度路径（armNext→scheduleAt）只负责 start、不更新 currentIndex，
    // 会导致「声音连续播放但选中态永远停在起播段」的假象。
    currentIndex.value = nxt
    waPlayingIndex = nxt
    // 进度基准对齐 nxt 的真实起播时刻：本段起播时刻 + 本段**剩余**时长(折算倍速) + 句间间隔。
    // 用剩余而非整段：本段可能从暂停点恢复播放（waSegStartOffset>0），其真实结束时刻 =
    // 起播时刻 + 剩余时长；用整段会令 waSegStartCtxTime 偏前，导致 nxt 进度条读负数。
    const endedRemainingReal = Math.max(
      0,
      ((waBuffers.get(index)?.duration ?? 0) - waSegStartOffset) /
        Math.max(0.01, playbackRate.value),
    )
    waSegStartCtxTime = waSegStartCtxTime + endedRemainingReal + SEGMENT_GAP
    waSegStartOffset = 0
    waPausedOffset = 0
    duration.value = waBuffers.get(nxt)?.duration ?? 0
    currentTime.value = 0
    // 预调度下一段（继承旧版 armNext 的一步前瞻，保持「始终多预排一段」）
    // 下一分段起播时刻 = nxt 起播时刻 + nxt 实际时长(折算倍速) + 句间间隔
    if (waBuffers.get(nxt)) {
      const nxtDur = (waBuffers.get(nxt)!.duration) / Math.max(0.01, playbackRate.value)
      armNext(nxt, waSegStartCtxTime + nxtDur + SEGMENT_GAP, gen)
    } else {
      armNext(nxt, waSegStartCtxTime, gen)
    }
    // 若预调度因解码慢未排上，兜底立即起播，杜绝整句跳过
    if (!isScheduled(nxt, gen)) {
      void waAdvanceTo(nxt, gen)
    }
  }

  const waGetTime = (): number => {
    if (waPlayingIndex < 0) return 0
    const dur = waBuffers.get(waPlayingIndex)?.duration ?? 0
    if (!isPlaying.value) return Math.min(waPausedOffset, dur)
    const t = (audioCtx!.currentTime - waSegStartCtxTime) * playbackRate.value + waSegStartOffset
    return Math.min(Math.max(t, 0), dur)
  }

  const waPlaySegment = async (index: number, offsetSeconds = 0) => {
    if (index < 0 || index >= segments.value.length) return
    const seg = segments.value[index]
    if (seg.status !== 'completed') {
      message.warning('该片段尚未完成合成')
      return
    }
    const ctx = ensureCtx()
    const gen = ++waGen
    // ⭐ 切句播放前，必须先停掉当前正在发声的 waSource 与已预调度（armNext→scheduleAt）
    // 的后续句。否则旧句与预调度句到点仍会自动发声，表现为「多句叠播」。
    // waStopSource 只停声音/预调度、不动缓存与 gen；随后的 waStart 会重建新句 source 与预调度。
    // 自动连播走 waOnSourceEnded，不经过此处，不会误停。
    waStopSource()
    ended = false
    currentIndex.value = index
    currentTime.value = 0
    buffering.value = true
    if (taskId.value != null) progress.save(taskId.value, seg.segmentNumber, offsetSeconds)
    const start = () => {
      if (gen !== waGen) return
      waSegStartCtxTime = ctx.currentTime
      waSegStartOffset = offsetSeconds
      waPausedOffset = offsetSeconds
      waStart(index, offsetSeconds, ctx.currentTime)
      buffering.value = false
    }
    const buf = waBuffers.get(index)
    if (buf) start()
    else {
      try {
        await waDecode(index)
        if (gen !== waGen) return
        start()
      } catch (e) {
        if (disposed) return
        isPlaying.value = false
        buffering.value = false
        message.error(await extractBlobErrorMessage(e, '加载音频失败'))
      }
    }
  }

  const waStopSource = () => {
    stopProgressTick()
    if (waSource) {
      try { waSource.onended = null; waSource.stop() } catch { /* ignore */ }
      waSource = null
    }
    // ⭐ 暂停/睡眠/卸载时，必须一并取消**已预调度**的后续片段。
    // 连播靠 armNext→scheduleAt 把下一句的 AudioBufferSourceNode 在「上一句结束时刻」
    // 之前就 start(when) 排上未来时刻；只停当前 waSource 的话，
    // 那些预调度的节点到点仍会自动播放 —— 表现为「暂停当前句、下一句继续播」。
    // 全部置空 onended 并 stop，再从 waScheduled 清掉，恢复时由 waStart 重新预排。
    for (const s of waScheduled) {
      try { s.src.onended = null; s.src.stop() } catch { /* ignore */ }
    }
    waScheduled.length = 0
  }
  const waPauseForSleep = () => {
    if (waPlayingIndex >= 0) waPausedOffset = waGetTime()
    waStopSource()
  }

  // ======================================================================
  // 统一控制层
  // ======================================================================
  const playSegment = (index: number, offsetSeconds = 0) => {
    void waPlaySegment(index, offsetSeconds)
  }

  const nextSegment = () => {
    for (let i = currentIndex.value + 1; i < segments.value.length; i++) {
      if (segments.value[i].status === 'completed') {
        playSegment(i)
        return
      }
    }
    isPlaying.value = false
    message.info('已播放完当前所有已合成片段')
  }
  const prevSegment = () => {
    for (let i = currentIndex.value - 1; i >= 0; i--) {
      if (segments.value[i].status === 'completed') {
        playSegment(i)
        return
      }
    }
  }

  const seekTo = (pos: number) => {
    if (currentIndex.value < 0) return
    const dur = waBuffers.get(currentIndex.value)?.duration ?? 0
    const target = Math.min(Math.max(pos, 0), dur || 0)
    if (waPlayingIndex >= 0) waStart(currentIndex.value, target, audioCtx!.currentTime, false)
    currentTime.value = target
  }
  const onSeek = (value: number | [number, number]) => {
    if (Array.isArray(value)) return
    if (currentIndex.value < 0) return
    seekTo(Math.min(Math.max(value, 0), duration.value || 0))
  }
  const skip = (seconds: number) => {
    if (currentIndex.value < 0) return
    seekTo(waGetTime() + seconds)
  }
  const onRateChange = (value: any) => {
    const rate = Number(value)
    if (!Number.isFinite(rate) || rate <= 0) return
    playbackRate.value = rate
    if (waSource && audioCtx) {
      // 重置时间基准，避免 currentTime 公式跳变
      waPausedOffset = waGetTime()
      waSegStartCtxTime = audioCtx.currentTime
      waSegStartOffset = waPausedOffset
      waSource.playbackRate.value = rate
    }
  }
  const onVolumeChange = (value: number | [number, number]) => {
    if (Array.isArray(value)) return
    volume.value = value
    if (waMasterGain && audioCtx) waMasterGain.gain.value = value
  }
  const togglePlay = () => {
    const ctx = ensureCtx()
    if (currentIndex.value < 0 || ended) {
      if (currentSegment.value) playSegment(currentIndex.value)
      else if (segments.value.length > 0) playSegment(0)
      return
    }
    if (isPlaying.value) {
      waPausedOffset = waGetTime()
      waStopSource()
      isPlaying.value = false
    } else {
      waStart(waPlayingIndex >= 0 ? waPlayingIndex : currentIndex.value, waPausedOffset, ctx.currentTime)
      isPlaying.value = true
    }
  }
  const pause = () => {
    if (currentIndex.value < 0) return
    if (isPlaying.value) {
      waPausedOffset = waGetTime()
      waStopSource()
      isPlaying.value = false
    }
  }
  const teardown = () => {
    waGen++
    teardownEngine()
    currentIndex.value = -1
    isPlaying.value = false
  }
  const teardownEngine = () => {
    clearSleepTick()
    resetSleepState()
    waStopSource()
    waScheduled.length = 0
    waPlayingIndex = -1
    waPausedOffset = 0
    ended = false
    currentTime.value = 0
    duration.value = 0
    buffering.value = false
  }
  const invalidateBuffers = () => {
    waBuffers.clear()
    waScheduled.length = 0
  }

  onUnmounted(() => {
    disposed = true
    saveProgress()
    waStopSource()
    teardownEngine()
    // v5/W3：AudioContext 必须显式 close。
    // 为什么不放 teardownEngine：teardown() 在切任务/切书时也会被调（Player.vue、
    // Reader.vue 的 validateAndLoad），放那里会每切一次就销毁重建上下文，
    // 解码缓存 waBuffers 也白丢；页面卸载才是它真正的终点。
    // 不关的后果：每个 AudioContext 会一直占着音频硬件与一个渲染线程，反复进出页面会累积。
    try {
      audioCtx?.close()
    } catch {
      // 已关闭 / 从未创建：忽略
    }
    audioCtx = null
    waMasterGain = null
  })

  return {
    currentIndex,
    isPlaying,
    buffering,
    currentTime,
    duration,
    playbackRate,
    volume,
    RATE_OPTIONS,
    currentSegment,
    playSegment,
    nextSegment,
    prevSegment,
    seekTo,
    onSeek,
    skip,
    onRateChange,
    onVolumeChange,
    togglePlay,
    pause,
    teardown,
    invalidateBuffers,
    loadProgress: progress.load,
    sleepTimerMode,
    sleepTimerRemaining,
    sleepTimerSegmentsLeft,
    setSleepTimer,
    setSleepTimerSegments,
    SLEEP_TIMER_OFF,
    SLEEP_TIMER_END_OF_SEGMENT,
    SLEEP_TIMER_SEGMENTS,
  }
}
