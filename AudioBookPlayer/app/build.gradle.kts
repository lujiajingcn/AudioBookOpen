import java.util.Properties
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release 签名密钥放在**不入库**的 local.properties，形如：
//   AUDIOBOOK_STORE_FILE=../release.jks
//   AUDIOBOOK_STORE_PASSWORD=********
//   AUDIOBOOK_KEY_ALIAS=audiobook
//   AUDIOBOOK_KEY_PASSWORD=********
// （R2-P2-24）未配置时 release 产出的 APK 依旧是未签名的，安装会报
// INSTALL_PARSE_FAILED_NO_CERTIFICATES，但配置期会打印醒目告警，不至于到发版才发现。
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 服务端地址按构建类型区分，可用 gradle 属性覆盖，避免把地址硬编码进源码：
//   ./gradlew assembleRelease -PAUDIOBOOK_PROD_BASE_URL=https://api.example.com/
//   ./gradlew assembleDebug   -PAUDIOBOOK_DEV_BASE_URL=http://10.0.2.2:8080/
//
// 两个默认值现在都指向已部署的腾讯云服务器（api.example.com），
// 这样不带任何 -P 参数即可打出连向真实后端的包。
//
// 地址写法说明：**必须是站点根 + 结尾斜杠**。
// Retrofit 的 baseUrl 是「替换式拼接」——接口注解全部写成相对路径（@GET("api/tts/voices")），
// 只有 baseUrl 以 "/" 结尾时才会拼成 http://host/api/tts/voices；
// 若写成 http://host/api 反而会丢掉最后一段变成 http://host/api/tts/voices 之外的错误路径。
// 走 nginx 的 80 端口而不是后端 8080：8080 未对公网开放，nginx 把 /api 前缀原样反代到 8080。
val devBaseUrl: String =
    (project.findProperty("AUDIOBOOK_DEV_BASE_URL") as String?) ?: "http://api.example.com/"
val prodBaseUrl: String =
    (project.findProperty("AUDIOBOOK_PROD_BASE_URL") as String?) ?: "http://api.example.com/"

// 端侧合成模块（MOSS-TTS-Nano）的托管地址。
//
// 为什么单独开一个可覆盖项，而不是拼在 BASE_URL 后面：
//   · 它指向的是 nginx 直接服务的**静态目录**（/moss/），与后端 API 是两套东西，
//     将来很可能换 CDN（几百 MB 的包不适合一直压在后端服务器上）；
//   · 开发/验证时需要指到本机起的静态服务器（配合 adb reverse），
//     这就要求它能在不改代码的前提下被替换。
//   ⚠️ 必须以 "/" 结尾：下载逻辑用字符串拼接拼文件名。
val mossModuleBaseUrl: String =
    (project.findProperty("AUDIOBOOK_MOSS_BASE") as String?) ?: "${prodBaseUrl}moss/"

// 场景 A 防呆守卫：仅在【真正构建 release】时校验，避免配置期无条件 throw
// 波及 assembleDebug 与 IDE 的 Gradle Sync。
gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { it.name.contains("Release", ignoreCase = true) }
    if (buildingRelease) {
        val prodUri = URI(prodBaseUrl)
        val prodHost = prodUri.host?.lowercase() ?: ""
        if (prodHost in setOf("127.0.0.1", "localhost", "10.0.2.2")) {
            throw GradleException(
                "release 的 BASE_URL 仍指向回环地址（$prodBaseUrl）。上架发布请改成真实官方域名，" +
                    "或构建时注入 -PAUDIOBOOK_PROD_BASE_URL=https://你的域名/"
            )
        }
        // 明文 HTTP 防呆（只在 release 有意义）：src/main 的 network_security_config
        // 是 cleartextTrafficPermitted="false"，只白名单了回环地址。若 BASE_URL 指向
        // 一个没列进白名单的 http:// 主机，应用不会被拒绝启动，而是**每个网络请求都失败**，
        // 排查成本很高（看起来像后端挂了）。这两处必须同步修改，所以这里主动提示。
        if ("http".equals(prodUri.scheme, ignoreCase = true)) {
            logger.warn(
                "BASE_URL 使用明文 http（$prodBaseUrl）。release 包默认禁止明文流量，" +
                    "请确认 $prodHost 已列入 app/src/main/res/xml/network_security_config.xml " +
                    "的 domain-config 白名单，否则所有请求会被系统拦截。"
            )
        }
    }
}

android {
    namespace = "com.audiobook.audiobookplayer"
    compileSdk = 34

    // AGP 8.x 默认关闭 BuildConfig 生成；ServerConfig 依赖 BuildConfig.BASE_URL，必须开启
    buildFeatures {
        buildConfig = true
    }

    // R2-P2-25：只保留中英文资源。appcompat / material 会带来 80+ 种语言变体，
    // 全量打进 APK 纯属浪费；`isShrinkResources` 裁不掉库里被引用到的多语言字符串。
    // 保留 en：Material 内部有对英文资源的默认引用，剔掉会出告警。
    //
    // 注意：新版 AGP 把它搬到了 `androidResources { localeFilters += ... }`，
    // 但**本工程用的 AGP 8.6.1 的 DSL 里还没有 localeFilters**（那个块只继承了
    // AaptOptions，只有 noCompress / ignoreAssetsPatterns / failOnMissingConfigEntry /
    // generateLocaleConfig）。升级 AGP 时再一并迁移。
    defaultConfig {
        // ⚠️ 只保留 arm64-v8a —— 这是本工程引入 ONNX Runtime 之后**必须**做的一件事。
        //
        // 实测（release 包）：APK 从 3.4 MB 涨到 105.6 MB，其中 102.2 MB 全是 native 库，
        // 且是按 ABI 重复打包的：
        //     x86_64      30.0 MB       ｜  x86        29.7 MB
        //     arm64-v8a   24.7 MB       ｜  armeabi-v7a 17.6 MB
        // 而**真机几乎全是 arm64-v8a**（armeabi-v7a 是 32 位老机，x86/x86_64 只在模拟器上存在）。
        // 不设这个过滤器，等于让每个真实用户多下 77 MB 永远用不到的库。
        //
        // 为什么不用 ABI splits / App Bundle：
        //   本工程的分发是"单个 APK 挂在自己服务器上、由客户端自更新"（见 UpdateManager），
        //   改成 split 会同时动到服务端版本表、下载接口与客户端更新逻辑；
        //   而一个 arm64 的包已经覆盖实际用户群，改动面最小、收益已拿到。
        //   （真要支持 32 位老机时，再加 "armeabi-v7a" 并确认模型在 32 位下的内存表现。）
        //
        // 代价：**x86/x86_64 模拟器上装 release 包会因缺少对应 .so 而无法推理**。
        // debug 包不受影响（这一行在 defaultConfig 里……见下方 buildTypes.debug 的覆盖），
        // 因此日常在模拟器上调试本机合成仍然可用。
        ndk {
            abiFilters += "arm64-v8a"
        }

        // R2-P2-25：只保留中英文资源。appcompat / material 会带来 80+ 种语言变体，
        // 全量打进 APK 纯属浪费；`isShrinkResources` 裁不掉库里被引用到的多语言字符串。
        // 保留 en：Material 内部有对英文资源的默认引用，剔掉会出告警。
        //
        // 注意：新版 AGP 把它搬到了 `androidResources { localeFilters += ... }`，
        // 但**本工程用的 AGP 8.6.1 的 DSL 里还没有 localeFilters**（那个块只继承了
        // AaptOptions，只有 noCompress / ignoreAssetsPatterns / failOnMissingConfigEntry /
        // generateLocaleConfig）。升级 AGP 时再一并迁移。
        resourceConfigurations += setOf("zh", "en")

        applicationId = "com.audiobook.audiobookplayer"
        minSdk = 24
        targetSdk = 34
        // ⚠️ 不要因为服务端 app_versions 表为空就从 1 重新开始编号。
        // versionCode 只增不减的依据是「已装在用户手机上的版本」，不是「库里的记录」：
        // 2026-09-22 生产库被清空（app_versions 0 行），而线上仍有装 1.4（code=5）的客户端，
        // 此时若发 code=1，服务端 /api/app/version/latest 的 hasUpdate 判据（latest > 当前）
        // 恒为 false ⇒ 所有老用户永远收不到更新，且这个错误发布后极难被发现。
        // 历史：1.2=2 / 1.3=4 / 1.4=5 / 1.5=6 / 1.6=7 / 1.7=8 / 1.8=9 / 1.9=10 / 1.10=11 / 1.11=12
        //       / 1.12=13 / 1.13=14 / 1.14=15 / 1.15=16 / 1.16=17 / 1.17=18 / 1.18=19 / 1.19=20
        //       / 1.20=21 / 1.21=22 / 1.22=23 / 1.23=24 / 1.24=25 / 1.25=26。
        // 1.26（2026-10-09）：新增阅读页「点读」模式（点英文单词查词并朗读）。
        // 1.27（2026-10-10）：单词收藏（查词弹窗收藏 + 「我的 - 单词本」）；释义弹窗去掉页脚版权行。
        // 1.28（2026-10-10）：参考音频管理整页改版（新增「试听原声」、按钮下移左对齐、行间分割线、
        //                     来源与大小移到名称右侧、列表刷新不再闪烁）；「我的」去掉各行右侧指示图标。
        // 1.29（2026-10-10）：单词本页隐藏底部导航栏；点读未装词典可就地询问下载并自动接续查词；
        //                     点读图标美化 + 被点单词显示为选中态；PDF 首次打开改为非阻塞解析
        //                     （顶部细进度条，解析期间目录按钮置灰不可点）。
        //                     ⚠️ 本版起改用 release.jks 签名（证书 <cert-fingerprint>…）——
        //                     code ≤ 29 线上用的是另一个签名且私钥已丢失，
        //                     故1.28 及以下用户需卸载重装才能升级（详见
        //                     E:\study_audiobook\WorkSpace_AudioBook\AudioBookPlayer-signing\README.md）。
        // 1.30（2026-10-10）：阅读页正文字号改为**局部**缩放（原先是 Configuration.fontScale 全局缩放，
        //                     会把标题栏/底导/列表/弹窗文字一起放大，已移除）；字号弹窗新增
        //                     小 A / 大 A 按钮（各 ±5 个百分点）；改字号不再 recreate（不闪、不丢滚动位置）。
        // 1.31（2026-10-10）：修点读模式「第一次点单词没反应、第二次才弹释义」——
        //                     根因是 tvText.setTextIsSelectable(true) 装的 ArrowKeyMovementMethod
        //                     会把首次 ACTION_DOWN 当作「起选区」消费掉，同一手势内不再走 performClick()，
        //                     于首次点击的 onClick 从不执行；触点本身是准确的。
        //                     修法：摘掉 tvText 的 setOnClickListener，点击所有权收进触摸探针
        //                     （摘而非「两边都留」，否则第二次点击框架自己也触发 ⇒ 查两次词/弹两次窗/朗读两遍）。
        // 1.32（2026-10-11）：书架任务行去掉「阅读并播放」按钮——
        //                     它与**书籍行点击**是同一件事（都进 BookContentActivity），
        //                     一条已完成任务因此出现两个等价入口。任务行现只保留纯任务态操作：
        //                     「重试 / 继续合成剩余 N 段」与「下载音频」，进阅读器统一走书籍行。
        // 1.33（2026-10-11）：本机合成**真正支持用参考音频克隆音色**——
        //                     此前客户端本机链完全忽略参考音频（UI 写死默认音色），
        //                     且 MOSS 模块包没有 codec encoder，选了「郭德纲.wav」也合成出默认音色。
        //                     本版新增 ReferenceWavLoader（RIFF chunk 链表解析、单/立体声、
        //                     16/24/32bit、重采样 48kHz、峰值归一、限 2~10 秒）→ encodeReferenceAudio()
        //                     出 promptAudioCodes，引擎池建立后只编码一次、整本共用；
        //                     **编码失败整本失败，不静默退回默认音色**。
        //                     ⚠️ 依赖 MOSS 模块 1.1.0（新增 moss_audio_tokenizer_encode.onnx/.data，
        //                     capabilities.customVoiceClone=true）：客户端**不比对模块版本**，
        //                     须在「本机合成模块」页手动重装模块才能拿到。
        // 1.34（2026-10-12）：PDF「降级预览」换 PDFium（io.legere:pdfiumandroid:1.0.31）——
        //                     此前用 com.tom-roush:pdfbox-android:2.0.27（Android 上无 PDFBox 3.x），
        //                     该版本在「字体子集缺 ToUnicode CMap」时直接丢字（无系统字体兜底），
        //                     实测《潜规则》145 页比服务端权威少 127 个「最」字，
        //                     令 1.14% / 2.07% 的音频段在 EbookSegmentMap 里定位失败（点读不上、高亮错位）。
        //                     换 PDFium 后与 PDFBox 3.0.2 字符级差异块 = 0、段定位失败 = 0；
        //                     不丢字靠正确解析内嵌 ToUnicode CMap，不是扫系统字体（未注册系统字体
        //                     仍输出完整 151 个「最」）⇒ 与宿主平台无关。顺带甩掉 BouncyCastle 约 11 MB。
        //                     ⚠️ 版本必须钉 1.0.31：Kotlin 1.9.20 读 classpath 上 Kotlin 类时校验
        //                     metadata 版本（上限 2.0.0）。1.0.33/1.0.35 的 class 是 major=61 看着安全，
        //                     但 metadata 是 2.2.0，编译直接失败——**只按 class major 选版会踩这个坑**。
        // 1.35（2026-10-12）：1.34 的版本号提交（内容同 1.34）。
        // 1.36（2026-10-12）：修「自动续播只听到最后一个字」+ 合成首尾裁静音与韵律接续 +
        //                     合成完成后回拉服务端正文覆盖本地缓存。客户端侧含播放修复
        //                     （normalizeEndOfSegmentOffset / END_OF_SEGMENT_GUARD_MS=3000）。
        // 1.37（2026-10-13）：**重新落地 1.34/1.35 的 PDFium 替换**——
        //                     中间为排查服务端 Java 25 / Boot 3.5 升级把客户端一起回滚到了
        //                     4c153ba（PDFBox 2.0.27，丢字问题回归），本版只把客户端那部分摘回来，
        //                     服务端保持 Spring Boot 3.2.0 + Java 17。
        //                     另含一个独立改进：debug 包也用 release 密钥签名，
        //                     否则 debug 覆盖装必 INSTALL_FAILED_UPDATE_INCOMPATIBLE、
        //                     想真机验证就得卸载，而卸载会丢书架/登录态/已下载音频/词典。
        // 1.38（2026-10-15，已在服务端发布，但版本号提升未提交，故本版从 37 直接顶到 39）：
        //                     阅读页长按交互优化——
        //                     1) 长按菜单「翻译」按语言显隐（选中含中文/非英文时不显示「翻译」按钮）；
        //                     2) 重做兜底选区拖动手柄（实心圆+方块、红色醒目、贴选区边）。
        // 1.39（2026-10-16）：修更新失败/取消卡启动页——下载失败或初始弹窗取消时进入现有安装版本，
        //                     而非卡在启动动画页（详见提交 9144ff6）。
        // 1.40（2026-10-18）：按《工程缺陷及修复建议-版本5》修 10 项——
        //                     1) 朗读中旋转屏幕不再丢状态/继续念（A4：单例回调重绑 + 恢复按钮态/播放条）；
        //                     2) 「取消下载」真的会停（A3：主动关流 + 统一翻译成 CancellationException）；
        //                     3) 上传帖子图片不再残留临时文件（A9：finally 清理）；
        //                     4) 大参考音频不再 OOM（A5 按秒封顶读取 + A2 Error 逃逸时关池）；
        //                     5) 本地 TXT 分段与阅读页同口径，高亮/段号不再错位（A6）；
        //                     6) 电子书目录层级修正（A7：NCX 标题不再双倍追加）；
        //                     7) URL 导入书籍释放连接（A1）；录音页 onStop 重复调用（A8）。
        // 1.41（2026-10-29）：游客模式 + 在线阅读 + 书架交互与合成音色多项升级——
        //                     1) 不登录即可使用应用：书城浏览、在线阅读、社区浏览对游客开放，
        //                        写操作按需登录（36 项客户端改动自 1.40 起累积发布）。
        //                     2) 书城书籍支持在线阅读 + 音频按需下载，无需先下载入书架；
        //                        合成音频优先于系统 TTS，底栏播放键可从头起播、支持朗读段落跳转。
        //                     3) 书架支持长按拖放调整顺序；合成状态行可点、新增「暂停/继续」按钮，
        //                        清理音频后任务行与存储摘要实时同步。
        //                     4) 支持 MOBI/AZW 电子书（工具/服务端/客户端三端解码口径统一）。
        //                     5) 合成音色收口：本机合成可选内置预设音色（不再写死 Junhao），
        //                        游客也能用参考音频克隆与云端预设清单；修在线合成必 500（预设音色
        //                        id 改用 demo_id，业务异常不再被兜底吞掉）。
        // 1.42（2026-11-03）：1.41 之后累积 26 个提交，集中在「等待体验」与「状态如实」——
        //                     1) 阅读：PDF 首开不再长时间白屏（本地预览真正先上屏）；
        //                        书城下载的书按 store_book_id 取正文，首开不再整本上传；
        //                        朗读改为逐句入队，高亮收窄到当前句（此前在
        //                        com.xiaomi.mibrain.speech 上一次性整行高亮）；
        //                        「回到播放处」扩展到有声书播放，滚走不再被拽回；
        //                        点通知卡片按段号直达；书内搜索结果标注所属章节。
        //                     2) 书城：下载结束后「查看下载进度」不再是死按钮；
        //                        下载弹窗「返回」移到最左，两个下载动作相邻。
        //                     3) 任务：启动与登录后同步服务端任务，重装不再丢已付费音频；
        //                        任务行显示服务端排队规模（排队数不再停在旧值）；
        //                        在线「部分完成」的任务可直接补齐剩余段（此前只剩重试）；
        //                        已暂停的任务显示「已暂停」并保留进度，不再谎称「处理中」。
        //                     4) 词典：查词弹窗新增读音按钮。
        //                     5) 社区：帖子加头像与点赞，自己的帖子可删除。
        //                     6) 合规与文案：新增用户协议与隐私政策页（登录页可离线查阅）；
        //                        积分明细时间统一 yyyy-MM-dd HH:mm；服务器共享参考音频可逐条试听；
        //                        合成页补全终态提示。
        //                        另修一批「错误提示不可信」：业务失败不再被吞成「未知错误」，
        //                        服务端 message 为空时不再把字面量 "null" 或英文原文上屏，
        //                        搜索结果被截断时不再报一个编造的总数。
        // 1.43（2026-11-05）：修书架删除「复活」两类——
        //                     1) 删除「已下载到本地的书城书」时漏通知服务端移出书架
        //                        （shelf_books 残留 → 冷启动 syncShelfFromServer 以服务端为真源
        //                        重建「在线占位条目」，书又回来）；
        //                     2) 删除「只有云端任务、无本地文件」的条目时只删本地 tasks 行、
        //                        不删服务端任务（冷启动 TaskSyncManager 把它们重新 upsert 回来）。
        //                     统一改为：删本地数据后 best-effort 通知服务端删除（书架移除 / 任务删除），
        //                     失败只打日志、不阻塞本地删除；排队任务退积分、合成中任务服务端拒绝。
        // 1.44（2026-11-06）：跨端同步——阅读位置按 bookKey 上报/合并，倍速、默认音色、
        //                     书序、最近阅读等单值设置走 LWW 合并；书城在线正文缓存加版本号；
        //                     收藏跳转按书名宽松匹配；「从此处读」起点改用长按落点。
        // 1.45（2026-11-07）：生词本收藏记录「书籍 + 当前句子位置」，单词本长按词条可「跳转到原书」
        //                     对应位置（SCROLL_TO_TEXT 优先、行号兜底）；服务端 UserWordBook 新增 6 个
        //                     可空出处字段，旧客户端不带出处时保留原值；PC / Web 同步支持。
        versionCode = 45
        versionName = "1.45"

        // ServerConfig 通过 BuildConfig.BASE_URL 读取服务器地址（场景 A：仅构建期决定，无应用内自定义）。
        // 注意：debug / release 各自在 buildTypes 里重新赋值，**这一行只对将来新增的
        // buildType / flavor 生效**；取 prod 值是为了「漏配时也不会退回模拟器回环地址」。
        buildConfigField("String", "BASE_URL", "\"$prodBaseUrl\"")
        buildConfigField("String", "MOSS_MODULE_BASE", "\"$mossModuleBaseUrl\"")

        // 真机（instrumentation）测试用的 runner。
        //
        // 加它是因为真机验证遇到一个现实障碍：部分 ROM（实测 MIUI）默认禁止
        // adb 注入输入事件，`input tap` 完全不可用，而探针页/合成页的操作全靠点击。
        // 而本机合成的**暂停/恢复**这类行为必须在真机上验证 ——
        // 模拟器内存不够（4 GB，合成跑不完就被 LMK 杀掉）。
        // instrumentation 测试跑在设备上、可直接调用服务与合成器，
        // 因此不需要任何点击，也就不受该限制影响。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // R2-P2-24：release 签名。只在 local.properties 里配了密钥时才创建该 signingConfig，
    // 没配则 signingConfigs 里查不到 "release"，下面的 findByName 返回 null = 不签名
    // （保持原行为，但配置期会告警）。
    //
    // **不要**退化成 debug keystore：那样打出的包能安装，但换机 / 升级 / 应用内更新时
    // 会因签名不一致而覆盖安装失败，比"装不上"更难排查。
    signingConfigs {
        val storeFilePath = keystoreProps.getProperty("AUDIOBOOK_STORE_FILE")
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = keystoreProps.getProperty("AUDIOBOOK_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("AUDIOBOOK_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("AUDIOBOOK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 缺陷 A-02 修复：开启 R8 混淆/收缩（配合 proguard-rules.pro 中的
            // Gson 模型保留规则，防止反射反序列化字段被重命名）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式包不能沿用 defaultConfig 里的 http://10.0.2.2（模拟器回环 + 明文），
            // 否则打出的 APK 既连不上真实服务端，又与 network_security_config
            // 「正式包禁止明文」的策略互相矛盾。通过 -PAUDIOBOOK_PROD_BASE_URL 注入真实地址。
            buildConfigField("String", "BASE_URL", "\"$prodBaseUrl\"")
            buildConfigField("String", "MOSS_MODULE_BASE", "\"$mossModuleBaseUrl\"")

            // 未配置密钥时为 null（= 不签名）。见上面 signingConfigs 的说明。
            signingConfig = signingConfigs.findByName("release")
            if (signingConfig == null) {
                logger.warn(
                    "R2-P2-24: 未配置 release 签名密钥（local.properties 缺 " +
                        "AUDIOBOOK_STORE_FILE）。assembleRelease 产出的 APK 无法安装，" +
                        "UpdateManager 的应用内更新也会卡在安装步骤。"
                )
            }
        }
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "BASE_URL", "\"$devBaseUrl\"")
            buildConfigField("String", "MOSS_MODULE_BASE", "\"$mossModuleBaseUrl\"")
            // debug 包也用 release 密钥签名（配了 release 签名配置时）。
            //
            // 原因：**debug 默认用 Android SDK 自带的 debug.keystore**，与线上 release 包
            // 证书不同 ⇒ `adb install -r` 必然失败（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
            // 想在真机上验证就得先卸载，**而卸载会清空应用数据**：
            // 书架、登录态（jwt 只在本地）、已下载的 audio_segments 与 ECDICT 词典全部丢失。
            // 为了跑一次真机验证而把用户设备上的数据清空，代价远大于收益。
            //
            // 只在 release 签名配置存在时套用（没配密钥的机器上仍是 null = 用 debug keystore），
            // 且**不影响 release 产物** —— release 块自己显式指定了 signingConfig。
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // debug 包保留全部 ABI：本机合成的开发与验证是在 x86_64 模拟器上做的，
            // 若这里也砍成 arm64，模拟器上会因缺 libonnxruntime.so 而直接崩，
            // 等于把唯一能低成本验证端侧推理的环境弄没了。
            // 真实用户拿到的 release 包才做 ABI 收敛（见 defaultConfig 里的说明）。
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // EbookSegmentMap 这类纯逻辑单测内部会用 android.util.Log 留痕，
        // 而 AGP 默认的 android.jar 桩方法会抛 "not mocked"；
        // 让它返回默认值（Log 变哑巴）即可在 JVM 上直接跑这些类。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // 发现页顶部标签页（TabLayout + ViewPager2 组合）
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.fragment:fragment-ktx:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.media:media:1.7.0")
    // 缺陷 A-03 修复：1.1.0-alpha06 为 alpha 版（API 仍可能变动，且不建议用于生产）；
    // 降级到唯一稳定版 1.0.0。注意 1.0.0 与 1.1.0-alpha API 不兼容：
    // 1.0.0 没有 MasterKey 类，须使用 MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    // 及 create(fileName, masterKeyAlias, context, ...) 旧签名（见 AuthManager.init）。
    implementation("androidx.security:security-crypto:1.0.0")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // RechargeActivity 用 ZXing 把支付链接渲染成二维码
    implementation("com.google.zxing:core:3.5.2")

    // 书城封面加载（BookStoreAdapter 使用 coil.load / addHeader / crossfade）
    implementation("io.coil-kt:coil:2.5.0")
    // 偏好设置（原项目依赖，保留以避免潜在符号缺失）
    implementation("androidx.preference:preference-ktx:1.2.1")

    // PDF「降级预览」用的本地解析器（**PDFium**，Chromium 的 PDF 引擎）。
    //
    // ## 为什么是 PDFium 而不是 PDFBox-Android
    //
    // 之前这里用的是 `com.tom-roush:pdfbox-android:2.0.27.0`（Android 上没有 PDFBox 3.x）。
    // 代价是**真实丢字**：跨主版本在「字体子集缺 ToUnicode CMap」时行为不同 ——
    // 3.0 扫系统字体兜底，2.0 直接丢。实测《潜规则》145 页，2.0.27 比服务端权威少
    // **127 个「最」字**，导致约 1.14% / 2.07% 的音频段在 EbookSegmentMap 里定位失败
    // （点读不上、高亮错位）。
    //
    // 换成 PDFium 后实测（桌面 pypdfium2，同一份服务端后处理）：
    // - 与服务端 PDFBox 3.0.2 的**字符级差异块 = 0**（2.0.27 是 127 / 320）
    // - 真实 `audio_segments` 段定位失败 **0**（2.0.27 是 1.14% / 2.07%）
    // - PDFium 126 与 156 两个版本输出**逐字节一致**（跨 30 个 Chromium 版本稳定）
    // - **不丢字是靠正确解析内嵌 ToUnicode CMap，不是靠扫系统字体**
    //   （pypdfium2 默认不注册系统字体，未注册仍输出完整 151 个「最」）⇒ 可外推到 Android
    //
    // ## 为什么必须钉 **1.0.31**（这个版本号是实测试出来的，别随手升）
    //
    // Kotlin 编译器读 classpath 上的 Kotlin 类时会校验 **metadata 版本**，
    // 与 class 文件的 major 无关。本工程 Kotlin = **1.9.20**，可读上限 = metadata **2.0.0**。
    // 实测各版本（判据取 pom 声明的 kotlin-stdlib，它反映编译时的 Kotlin 版本）：
    //
    // | 版本 | kotlin-stdlib | 结论 |
    // |---|---|---|
    // | 1.0.1 ~ 1.0.15 | 1.8.21 / 1.9.22 | 可用（更老，PDFium 也更老） |
    // | **1.0.31** | **2.0.21** | ✅ **metadata 2.0.0，恰在上限内** |
    // | 1.0.33 / 1.0.35 | 2.2.0 | ❌ 报 `metadata is 2.2.0, but compiler 1.9.0 can read up to 2.0.0` |
    // | 2.0.x / 3.0.x | 2.4.10 | ❌ 同上，且 class 是 major=65(Java 21) |
    //
    // ⚠️ **1.0.35 的报错具有欺骗性**：它的 class 是 major=61（看着像 Java 17 很安全），
    // 但 metadata 是 2.2.0，编译直接失败。**只按 class major 选版会踩这个坑。**
    // ⚠️ 不要用 `-Xskip-metadata-version-check` 绕过：那只是让编译器别报这个错，
    // 真正用到 2.1+ 才有的语言特性时仍会错，且失败点离根因很远。升级 Kotlin 是独立工程。
    //
    // 体积：单 aar 自带 4 个 ABI 的 `.so`；本工程 release 只打 arm64-v8a（abiFilters），
    // debug 额外留 x86_64 给模拟器。净收益是**甩掉 BouncyCastle 三件套约 11 MB**
    // —— 为 PDF 内部解密引入一个加密库本就是过度依赖。
    //
    // ⚠️ 16KB 内存页（Android 15）：实测 arm64-v8a / x86_64 的 `libpdfium*.so`
    // PT_LOAD 对齐为 **0x4000**，满足要求（armeabi-v7a / x86 是 0x1000，但本工程不打这两个 ABI）。
    //
    // ⚠️ 这里的产出**只是降级预览**：权威解析仍走服务端（readServerRowsWithCache）
    // 并在成功后覆盖缓存。**不要**拿本地结果当权威、也不要给它建 audio_segments。
    implementation("io.legere:pdfiumandroid:1.0.31")

    // 「原文行 ↔ 音频片段」定位算法的单测。这份映射是本次改动的核心风险点
    // （两端文本只要不同源就会静默错位），必须有可离线跑的用例兜住。
    testImplementation("junit:junit:4.13.2")
    // 分词一致性测试要读 JSON 向量表。**不能用 org.json**：那在 JVM 单测里只是
    // android.jar 的桩（配合 unitTests.isReturnDefaultValues=true 会静默返回 null，
    // 测试假装通过 —— 比直接报错危险得多）。Gson 在运行时已由 retrofit 传递引入，
    // 这里显式加到测试类路径只是为了让它对单测可见。
    testImplementation("com.google.code.gson:gson:2.8.5")

    // 真机（instrumentation）测试依赖。用途见 defaultConfig 里 testInstrumentationRunner
    // 的说明：在禁止 adb 注入输入事件的机型上，这是唯一能自动验证
    // 本机合成暂停/恢复等真机行为的手段。
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // ========================================================================
    // 端侧语音合成：MOSS-TTS-Nano（ONNX Runtime）
    //
    // 为什么是 ONNX Runtime 而不是 PyTorch Mobile / LiteRT：
    //   MOSS-TTS-Nano 官方只导出了 ONNX 权重，且官方仓库自带
    //   examples/android_onnx_runtime 示例就是跑在 ONNX Runtime 上的
    //   （实测其引擎只用到 prefill / decode_step / local_fixed_sampled_frame
    //     + 音频分词器 decode_full 四个图）。用别的运行时等于自己重新导出，风险大得多。
    //
    // ⚠️ 体积：onnxruntime-android 会把 libonnxruntime.so / libonnxruntime4j_jni.so
    //    打进 APK，release 经 R8/资源收缩后包体仍会增加几十 MB。这是"本机合成"的必然代价。
    //
    // ⚠️ **版本必须 >= 1.20，不能退回 1.19.x**：Android 15 起系统支持 16 KB 内存页，
    //    而 1.19.2 的 .so 其 LOAD 段只按 4 KB 对齐。实测在 API 37 模拟器上装 1.19.2 会直接弹
    //    「Android App Compatibility — This app isn't 16 KB compatible」，
    //    只能靠系统的"页面大小兼容模式"降级运行（有性能损失，且高版本 Android 会逐步收紧）。
    //    Microsoft 从 1.20 起提供了 16 KB 对齐的构建，这里用 1.24.3（该系列的稳定版）。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
}
