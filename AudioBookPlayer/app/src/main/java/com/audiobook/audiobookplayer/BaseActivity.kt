package com.audiobook.audiobookplayer

import androidx.appcompat.app.AppCompatActivity

/**
 * 各业务 Activity 的公共基类。
 *
 * ## 关于字体缩放（历史包袱，已移除）
 *
 * 早期本类覆写 [attachBaseContext]，把 [AppPrefs.getFontScale] 存的比例注入
 * `Configuration.fontScale`，靠「一个字段放大整个 Activity 的所有 sp 文本」来实现字号设置。
 *
 * **这条全局缩放已按需求移除**：用户要求「调整字体大小只调整阅读页中电子书内容的
 * 字体大小，不调整应用中的其他部分」。而 `fontScale` 是 Activity 级全局量，
 * 一旦生效，标题栏、底部导航、书城列表、发现页、弹窗、按钮文字**全部**跟着一起变大变小，
 * 与需求正好相反，且没有任何办法只放大正文。
 *
 * 现在字号走局部路径：阅读页正文 TextView 由 [LineAdapter] 用
 * `setTextSize(COMPLEX_UNIT_SP, 16f × 比例)` 直接量，比例存
 * [AppPrefs.getReaderTextScale]。**不再需要 recreate() 才能生效。**
 *
 * ⚠️ 顺带修掉的一个隐患：`Configuration.fontScale` 覆写会把**用户系统级的字体大小
 * 设置**（设置 → 显示 → 字体大小）一起顶掉 —— 系统那份 `fontScale` 被整体替换成了
 * App 偏好值。移除后系统设置重新按预期生效。
 *
 * 保留本类是因为 8 个 Activity 仍在继承它，是它们共同的类型锚点；
 * 继承链本身没有别的行为，改动时无需动这些 Activity。
 */
open class BaseActivity : AppCompatActivity()