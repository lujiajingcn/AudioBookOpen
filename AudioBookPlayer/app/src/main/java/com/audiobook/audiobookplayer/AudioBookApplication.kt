package com.audiobook.audiobookplayer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate

/**
 * 应用级入口：通过 ActivityLifecycleCallbacks 持续维护「当前前台 Activity」，
 * 供 [InsufficientCreditsNotifier] 等需要绑定 UI 的全局组件使用。
 *
 * 之所以需要它：OkHttp 拦截器运行在后台线程，收到 402 时要弹「去充值」引导必须知道
 * 当前哪一个 Activity 在前台。由各 Activity 自行上报既分散又易漏，统一在此集中维护更稳妥。
 */
class AudioBookApplication : Application() {

    companion object {
        /**
         * 进程级 Application Context。
         *
         * 供那些**刻意活在 Activity 生命周期之外**的组件兜底使用（典型是 UpdateDialog 的
         * 全局下载 scope——注释里写明「故意用全局 scope 以免下载中断」）。
         * 它是 Application，不存在泄漏 Activity 的问题。
         */
        @Volatile
        var appContext: Context? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this

        // 夜间模式：在任意 Activity 之前按用户偏好设定全局夜间模式
        // （-1 跟随系统 / 1 日间 / 2 夜间），Activity 创建时即套用对应主题。
        AppCompatDelegate.setDefaultNightMode(AppPrefs.getNightMode(this))

        // 离线词典：只做「读磁盘判断有没有装」，不碰网络、不解压，
        // 因此放在这里不会拖慢冷启动（真正的下载由用户主动触发）。
        DictionaryManager.init(this)

        // 中文单字词典：同样只绑定 context。⚠️ **不要**在这里调ensureInstalled() ——
        // 那会把 4 MB 的 assets 拷贝放到冷启动首帧，违反「不拖慢冷启动」的初衷。
        // 释放发生在用户第一次点「查字典」时（IO 协程里），见 ChinDictManager 的说明。
        ChinDictManager.init(this)

        // 单词本：只绑定 applicationContext，不建表不读库（建表走首次访问时的兜底
        // execSQL，与 DictionaryManager 同样不在冷启动路径上做重活）。
        WordBookRepository.init(this)

        // AuthManager.init 故意**不**挪到这里：它内部构建 EncryptedSharedPreferences
        // 主密钥在主线程上可达 100ms，放进来会直接拖慢冷启动首帧。
        // 它仍在 LoginActivity 里按需初始化（登录流程本来就要等用户操作，不急）。

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            // 以「最后 resume 的 Activity」作为前台 Activity；切换时新页面 resume 即覆盖，
            // 旧页面销毁时若仍是当前才清空，避免切换瞬间出现空窗。
            override fun onActivityResumed(activity: Activity) {
                InsufficientCreditsNotifier.setCurrentActivity(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (InsufficientCreditsNotifier.currentActivity === activity) {
                    InsufficientCreditsNotifier.setCurrentActivity(null)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }
}
