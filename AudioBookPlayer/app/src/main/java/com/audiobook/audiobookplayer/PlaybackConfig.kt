package com.audiobook.audiobookplayer

/**
 * 播放相关常量集中管理（收敛散落在 UI / Service 中的倍速与睡眠定时魔法数）。
 *
 * - 倍速档位与 Web 播放器 RATE_OPTIONS 对齐（0.75 / 1 / 1.25 / 1.5 / 2）；
 * - 倍速上下限用于非法值防御（PlaybackService 持久化恢复时 coerceIn）；
 * - 睡眠定时四态：关闭(0) / 播完本段(-1) / 若干段后(-2) / 具体分钟数(>0)。
 */
object PlaybackConfig {
    const val DEFAULT_SPEED = 1.0f
    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 3.0f

    /** 可选倍速档位（与 Web 端一致，供倍速选择对话框使用）。
     * 用不可变的 List<Float> 而非 FloatArray：原 FloatArray 是公开可变数组，
     * 任何调用方都能污染全局倍速档位。只读语义用 List 表达更明确。 */
    val SPEED_OPTIONS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    const val SLEEP_TIMER_OFF = 0
    const val SLEEP_TIMER_END_OF_SEGMENT = -1
    const val SLEEP_TIMER_SEGMENTS = -2
}
