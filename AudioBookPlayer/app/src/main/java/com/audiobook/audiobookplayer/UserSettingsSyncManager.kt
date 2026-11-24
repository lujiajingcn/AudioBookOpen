package com.audiobook.audiobookplayer

import android.app.Application
import android.content.Context
import android.util.Log
import com.audiobook.audiobookplayer.model.BookMetaItemRequest
import com.audiobook.audiobookplayer.model.SyncBookMetaRequest
import com.audiobook.audiobookplayer.model.SyncUserSettingsRequest
import com.audiobook.audiobookplayer.model.UserSettingItemRequest
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 跨端「单值偏好 + 每书元数据」同步（客户端 LWW 合并）。
 *
 * ## 只同步 4 项
 *  - `user_settings`：`playback_speed`（Float，0.5~3.0）、`default_voice_id`（String）；
 *  - `book_meta`：`sort_order`（Int）、`last_read_at`（Long 毫秒）。
 *
 * ⛔ **绝不同步 `category`**：Android 是用户手工分类、PC 是文件扩展名/书城固定值，
 * 同步会互相污染。也不同步夜间模式 / 字号 / MOSS 本机路径 / token 等本机偏好。
 * 响应里出现的其它 key 一律忽略、不落本地、不记影子。
 *
 * ## LWW 规则（服务端逐项比较 updatedAt，仅当上报值严格大于服务端版本才覆盖）
 *  - **干净项**（本地值 == 影子快照）⇒ 原样回传上次拿到的服务端版本 ⇒ 不覆盖服务端；
 *  - **脏项**（本地值 != 影子快照，或从未同步过、无影子）⇒
 *    `updatedAt = max(System.currentTimeMillis(), 全局最高版本 + 1)` ⇒ 覆盖服务端；
 *  - 收到响应后用**响应值静默覆盖本地**（`playback_settings` SP / AppPrefs / ebooks 两列），
 *    同时把影子快照、每项服务端版本、全局版本水位更新为响应值 ⇒ 本地 == 影子 ⇒ 不产生二次上报。
 *
 * ## 影子快照方案（关键：不会循环）
 * 倍速变化靠「每次同步直接读 `playback_settings` SP 的当前值 与 影子快照比对」捕获，
 * **不在** `onPlaybackSpeedChanged` / `loadEbooks()` / `mergeShelf()` / LiveData 回调里埋上报钩子。
 * 应用远端值时同步前移影子快照，因此即便 UI 因写库刷新，也不会被误判为一次本地修改再上报。
 *
 * ## 失败一律 best-effort
 * 未登录跳过；inFlight CAS 去重；异常只打日志、保留本地、不抛给调用方
 * （[CancellationException] 原样抛）。绝不因一次网络失败清空本地偏好 / 书籍行。
 *
 * 独立 SP 文件 [PREFS] 存：每项影子快照、每项服务端版本、待上报脏标记、全局版本水位。
 */
object UserSettingsSyncManager {

    private const val TAG = "UserSettingsSync"

    /** 同步状态专用 SP，与 app_settings / playback_settings 隔离，避免被业务清理误伤。 */
    private const val PREFS = "user_settings_sync"

    /** 倍速由 PlaybackService 独立存放在这个 SP 文件、这个键（与 PlaybackService.kt 常量对齐）。 */
    private const val PLAYBACK_PREFS = "playback_settings"
    private const val KEY_SPEED = "playback_speed"

    private const val SETTING_SPEED = "playback_speed"
    private const val SETTING_VOICE = "default_voice_id"
    /** 允许同步的白名单：绝不放宽到其它偏好。 */
    private val SETTING_KEYS = listOf(SETTING_SPEED, SETTING_VOICE)

    /** 全局最高版本水位（取历次响应顶层 version 与所有 item.updatedAt 的最大值）。 */
    private const val KEY_GLOBAL_VERSION = "global.version"

    private fun shadowKey(key: String) = "us.shadow.$key"
    private fun verKey(key: String) = "us.ver.$key"
    private fun dirtyKey(key: String) = "us.dirty.$key"
    private fun bmShadowKey(bk: String) = "bm.shadow.$bk"
    private fun bmVerKey(bk: String) = "bm.ver.$bk"
    private fun bmDirtyKey(bk: String) = "bm.dirty.$bk"

    /** 与 App 同生命周期，不跟随任何 Activity。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 两类同步各自去重，互不阻塞。 */
    private val settingsInFlight = AtomicBoolean(false)
    private val metaInFlight = AtomicBoolean(false)

    private val api by lazy { RetrofitClient.apiService }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 本地值读取（统一成 canonical String，供脏项比对） ----------

    /** Float -> String 用 `toString()` 保证「读本地」与「影子」用同一表示，避免 1.5 / 1.50 误判。 */
    private fun canonicalSpeed(f: Float): String = f.toString()

    private fun readSpeed(context: Context): Float =
        context.getSharedPreferences(PLAYBACK_PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, PlaybackConfig.DEFAULT_SPEED)
            .coerceIn(PlaybackConfig.MIN_SPEED, PlaybackConfig.MAX_SPEED)

    private fun localSpeed(context: Context): String = canonicalSpeed(readSpeed(context))
    private fun localVoice(context: Context): String = AppPrefs.getDefaultVoiceId(context)

    /** book_meta 的组合值：sortOrder 或 lastReadAt 任一变化即整项变脏。 */
    private fun canonicalBookMeta(sortOrder: Int, lastReadAt: Long): String = "$sortOrder|$lastReadAt"

    /** 响应 value 统一转 String；缺字段 / 非 String|Number 返回 null（跳过该项）。 */
    private fun valueToString(v: Any?): String? = when (v) {
        is String -> v
        is Number -> v.toString()
        else -> null
    }

    /**
     * 把一条响应值**静默写回本地**并返回其 canonical 影子值；非法值返回 null（调用方跳过）。
     *
     * 倍速直接写 `playback_settings` SP，不走 `PlaybackService.setPlaybackSpeed`，
     * 因此不触发 `onPlaybackSpeedChanged` 回调、不会二次上报；音色写 AppPrefs（无回调）。
     */
    private fun applyRemoteSetting(context: Context, key: String, value: String): String? {
        return when (key) {
            SETTING_SPEED -> {
                val f = value.toFloatOrNull()
                    ?.coerceIn(PlaybackConfig.MIN_SPEED, PlaybackConfig.MAX_SPEED)
                    ?: return null
                context.getSharedPreferences(PLAYBACK_PREFS, Context.MODE_PRIVATE)
                    .edit().putFloat(KEY_SPEED, f).apply()
                canonicalSpeed(f)
            }
            else -> {
                AppPrefs.setDefaultVoiceId(context, value)
                value
            }
        }
    }

    // ---------- 对外入口（fire-and-forget，启动 / 登录 / 进书架页各挂一次） ----------

    /** 单值偏好：一次 PUT 完成「拉 + 推」。未登录跳过、在途去重。 */
    fun syncAsync(context: Application) {
        if (!AuthManager.isAuthenticated) return
        if (!settingsInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                syncSettings(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "单值偏好同步异常: ${e.message}")
            } finally {
                settingsInFlight.set(false)
            }
        }
    }

    /**
     * 每书元数据：全量上报 + 拉全量，按 bookKey 落地两列。未登录跳过、在途去重。
     *
     * @param onApplied 写库成功且确有行被更新时回调（携带更新行数，运行在 IO 线程）。
     *   供上层（BookViewModel）在远端值落地后刷新书架 UI —— 只是读库刷新、
     *   **不会**触发任何上报，影子快照已同步前移，不产生二次同步。
     */
    fun syncBookMetaAsync(context: Application, onApplied: ((Int) -> Unit)? = null) {
        if (!AuthManager.isAuthenticated) return
        if (!metaInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                syncBookMeta(context, onApplied)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "每书元数据同步异常: ${e.message}")
            } finally {
                metaInFlight.set(false)
            }
        }
    }

    // ---------- 单值偏好 ----------

    private suspend fun syncSettings(context: Application) = withContext(Dispatchers.IO) {
        if (!AuthManager.isAuthenticated) return@withContext
        val sp = prefs(context)
        try {
            val now = System.currentTimeMillis()
            val watermark = sp.getLong(KEY_GLOBAL_VERSION, 0L)
            val dirtyVersion = maxOf(now, watermark + 1)

            // 1) 组装全量上报项：干净项回传服务端版本，脏项用放大时间戳。
            val items = ArrayList<UserSettingItemRequest>(SETTING_KEYS.size)
            val editor = sp.edit()
            for (key in SETTING_KEYS) {
                val current = when (key) {
                    SETTING_SPEED -> localSpeed(context)
                    else -> localVoice(context)
                }
                val hasShadow = sp.contains(shadowKey(key))
                val shadow = if (hasShadow) sp.getString(shadowKey(key), "") ?: "" else null
                val serverVer = sp.getLong(verKey(key), 0L)
                val dirty = shadow == null || current != shadow
                // ⭐ 首次同步（无影子）且本地还是默认值 ⇒ 回传中性版本 0。
                // 否则「新装设备第一次启动、用默认倍速 1.0」会把另一端设的 1.75 直接冲掉，
                // 表现为「换台设备后设置全丢了」。本地已有非默认值（老用户首次上云）仍要推上去。
                val isDefault = when (key) {
                    SETTING_SPEED -> current == canonicalSpeed(PlaybackConfig.DEFAULT_SPEED)
                    else -> current.isEmpty()
                }
                val updatedAt = when {
                    shadow == null && isDefault -> 0L
                    dirty -> dirtyVersion
                    else -> serverVer
                }
                // 待上报脏标记（诊断用；实际判定仍以影子比对为准）
                editor.putBoolean(dirtyKey(key), dirty)
                items.add(UserSettingItemRequest(key = key, value = current, updatedAt = updatedAt))
            }
            editor.apply()

            // 2) 一次 PUT 完成拉 + 推。
            val resp = api.putUserSettings(SyncUserSettingsRequest(items))
            if (!resp.isSuccessful) {
                Log.w(TAG, "单值偏好同步：HTTP ${resp.code()}，保留本地")
                return@withContext
            }
            val body = resp.body()
            if (body == null || body["success"] != true) {
                Log.w(TAG, "单值偏好同步：响应结构异常，保留本地")
                return@withContext
            }

            // 3) 用响应权威值静默覆盖本地 + 前移影子/版本/水位。
            //    本地写入（playback_settings / AppPrefs）与影子都在成功分支里完成，
            //    失败（异常）时 e2 不会 apply ⇒ 影子不动 ⇒ 下次仍按脏项重推本地值。
            val rawItems = body["items"] as? List<*> ?: emptyList<Any>()
            val e2 = sp.edit()
            var applied = 0
            for (o in rawItems) {
                val m = o as? Map<*, *> ?: continue
                val key = m["key"] as? String ?: continue
                if (key !in SETTING_KEYS) continue // ⛔ 其它偏好一律不落本地
                val value = valueToString(m["value"]) ?: continue
                val ver = (m["updatedAt"] as? Number)?.toLong() ?: 0L
                // 静默写回本地 + 取 canonical 影子值（非法倍速会被跳过）
                val canonical = applyRemoteSetting(context, key, value) ?: continue
                e2.putString(shadowKey(key), canonical)
                    .putLong(verKey(key), ver)
                    .putBoolean(dirtyKey(key), false)
                applied++
            }
            // 全局版本水位 = 历史 / 响应顶层 version / 所有 item.updatedAt 三者最大。
            val topVersion = (body["version"] as? Number)?.toLong() ?: 0L
            var maxVer = maxOf(watermark, topVersion)
            for (o in rawItems) {
                val m = o as? Map<*, *> ?: continue
                maxVer = maxOf(maxVer, (m["updatedAt"] as? Number)?.toLong() ?: 0L)
            }
            e2.putLong(KEY_GLOBAL_VERSION, maxVer).apply()
            Log.d(TAG, "单值偏好同步完成：上报 ${items.size} 项 / 落地 $applied 项 / 水位 $maxVer")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "单值偏好同步异常（保留本地）: ${e.message}", e)
        }
    }

    // ---------- 每书元数据 ----------

    private suspend fun syncBookMeta(context: Application, onApplied: ((Int) -> Unit)? = null) =
        withContext(Dispatchers.IO) {
        if (!AuthManager.isAuthenticated) return@withContext
        val sp = prefs(context)
        try {
            val bookStorage = BookStorage(context)
            val ebooks = bookStorage.getEbooks()
            // bookKey 用现成口径（CoverageCodec + 借 tasks 表按书名反查 serverTaskId），与 PC 对齐。
            val idToKey = bookStorage.getShelfBookKeyMap()

            val now = System.currentTimeMillis()
            val watermark = sp.getLong(KEY_GLOBAL_VERSION, 0L)
            val dirtyVersion = maxOf(now, watermark + 1)

            // 1) 全量上报：每本有 bookKey 的书一项，只带 sortOrder / lastReadAt。
            val items = ArrayList<BookMetaItemRequest>(ebooks.size)
            // 本次上报时每本书的本地现值，供响应阶段筛掉「值没变」的项，
            // 避免每轮同步都无条件 UPDATE 全表并刷新书架 UI（会闪一下）。
            val localByKey = HashMap<String, Pair<Int, Long>>()
            val editor = sp.edit()
            for (book in ebooks) {
                val bk = idToKey[book.id.toLong()] ?: continue // 算不出 bookKey：跳过
                if (bk.isEmpty()) continue
                localByKey[bk] = book.sortOrder to book.lastReadAt
                val current = canonicalBookMeta(book.sortOrder, book.lastReadAt)
                val hasShadow = sp.contains(bmShadowKey(bk))
                val shadow = if (hasShadow) sp.getString(bmShadowKey(bk), "") ?: "" else null
                val serverVer = sp.getLong(bmVerKey(bk), 0L)
                val dirty = shadow == null || current != shadow
                // ⭐ 首次同步且本地全默认（sortOrder=0 / lastReadAt=0）⇒ 中性版本 0，
                // 别让「刚装好的空书架」冲掉另一端已排好的顺序。
                val isDefault = book.sortOrder == 0 && book.lastReadAt == 0L
                val updatedAt = when {
                    shadow == null && isDefault -> 0L
                    dirty -> dirtyVersion
                    else -> serverVer
                }
                editor.putBoolean(bmDirtyKey(bk), dirty)
                items.add(
                    BookMetaItemRequest(
                        bookKey = bk,
                        sortOrder = book.sortOrder,
                        lastReadAt = book.lastReadAt,
                        updatedAt = updatedAt
                    )
                )
            }
            editor.apply()

            // 2) POST 全量同步。
            val resp = api.syncBookMeta(SyncBookMetaRequest(items))
            if (!resp.isSuccessful) {
                Log.w(TAG, "每书元数据同步：HTTP ${resp.code()}，保留本地")
                return@withContext
            }
            val body = resp.body()
            if (body == null || body["success"] != true) {
                Log.w(TAG, "每书元数据同步：响应结构异常，保留本地")
                return@withContext
            }

            // 3) 解析权威全量：准备写库载荷 + 影子/版本/水位更新（先在 editor 里攒着，稍后 apply）。
            val rawItems = body["items"] as? List<*> ?: emptyList<Any>()
            val applyList = ArrayList<Map<String, Any?>>(rawItems.size)
            val e2 = sp.edit()
            var maxVer = watermark
            for (o in rawItems) {
                val m = o as? Map<*, *> ?: continue
                val bk = (m["bookKey"] as? String)?.trim().orEmpty()
                if (bk.isEmpty()) continue
                val sort = (m["sortOrder"] as? Number)?.toInt() ?: continue
                val last = (m["lastReadAt"] as? Number)?.toLong() ?: continue
                val ver = (m["updatedAt"] as? Number)?.toLong() ?: 0L
                // 只把「与本地现值不同」的项交给写库层；影子/版本无论如何都要前移，
                // 否则下一轮会误判成脏项把旧值推回去覆盖服务端。
                val local = localByKey[bk]
                if (local != null && (local.first != sort || local.second != last)) {
                    applyList.add(
                        mapOf<String, Any?>("bookKey" to bk, "sortOrder" to sort, "lastReadAt" to last)
                    )
                }
                e2.putString(bmShadowKey(bk), canonicalBookMeta(sort, last))
                    .putLong(bmVerKey(bk), ver)
                    .putBoolean(bmDirtyKey(bk), false)
                maxVer = maxOf(maxVer, ver)
            }
            e2.putLong(KEY_GLOBAL_VERSION, maxVer)

            // 4) 静默写库（只 UPDATE 两列、不 INSERT/DELETE、不碰 category、不通知上层）。
            //    写库失败会抛异常 → 下面 e2.apply() 不执行 → 影子不前移，避免旧值被误推回服务端。
            val rows = bookStorage.applyBookMetaFromServer(applyList)
            e2.apply() // 写库成功后才落影子/版本/水位
            Log.d(
                TAG,
                "每书元数据同步完成：上报 ${items.size} 项 / 服务端 ${rawItems.size} 项 / 更新 $rows 行 / 水位 $maxVer"
            )
            // 远端值已落地：通知上层刷新书架 UI（纯读库，不触发上报）
            if (rows > 0) onApplied?.invoke(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "每书元数据同步异常（保留本地）: ${e.message}", e)
        }
    }
}
