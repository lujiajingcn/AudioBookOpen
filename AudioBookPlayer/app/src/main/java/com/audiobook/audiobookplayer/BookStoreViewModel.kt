package com.audiobook.audiobookplayer

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.audiobook.audiobookplayer.model.StoreBook
import com.audiobook.audiobookplayer.model.StoreCategory
import com.audiobook.audiobookplayer.model.StoreTag
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Type

private const val TAG = "BookStoreVM"

/**
 * 列表反序列化用的 `Type`，在类加载时一次性构造。
 *
 * **不能**写成 `object : TypeToken<List<StoreBook>>() {}.type`：那依赖匿名子类的
 * 泛型超类签名（Signature 属性）能通过反射读到。R8 full mode 会剥离未被 keep 的
 * 类的 Signature 属性，而本工程的 Gson 是 retrofit 2.9.0 传递进来的 **2.8.5**，
 * 其 consumer rules 尚无 `-keep class * extends com.google.gson.reflect.TypeToken`，
 * 于是运行时抛 `IllegalArgumentException: Missing type parameter.`。
 *
 * 异常被下面的 `catch (e: Exception)` 吞掉，列表恒为空 ⇒ 书城页永远显示「暂无书籍」，
 * 而网络层一切正常（HTTP 200、服务端零错误）。**仅在 release 包复现，debug 包正常**。
 * 线上实证：2026-09-19，release APK versionCode=2。
 *
 * `TypeToken.getParameterized` 直接构造 `ParameterizedType`，不依赖任何签名反射，
 * 因此在任何 R8 配置下都不会坏 —— 这是根治手段，proguard 里的 keep 规则只是兜底。
 */
private val BOOK_LIST_TYPE: Type =
    TypeToken.getParameterized(List::class.java, StoreBook::class.java).type

/** 同 [BOOK_LIST_TYPE]，分类列表用 */
private val CATEGORY_LIST_TYPE: Type =
    TypeToken.getParameterized(List::class.java, StoreCategory::class.java).type

/** 同 [BOOK_LIST_TYPE]，标签列表用 */
private val TAG_LIST_TYPE: Type =
    TypeToken.getParameterized(List::class.java, StoreTag::class.java).type

/** 下载进度回调节流间隔（毫秒）。与「百分比变化 ≥1%」任一满足才往主线程 post。 */
private const val PROGRESS_POST_INTERVAL_MS = 200L

/**
 * 下载内容类型。UI 据此决定进度对话框的标题与文案。
 *
 * 合成音频（AUDIO）自 2026-10-01 起改由
 * [StoreAudioDownloadManager] 逐段后台下载，进度不再走这里的字节级通道；
 * 这个枚举值保留，供那边的语义对齐与后续复用。
 */
enum class DownloadKind { BOOK, AUDIO }

/** 整本书下载进度（字节级），供 UI 展示进度条与「继续下载」状态 */
data class DownloadProgress(
    val bookId: Long,
    val downloaded: Long,
    val total: Long,
    val resuming: Boolean,
    val kind: DownloadKind = DownloadKind.BOOK
)

/**
 * 下载完成事件。
 *
 * [displayName] 与 [fileName] 必须分开传：书架展示名是**原始书名**，
 * 而本地落盘名沿用服务端文件名（`<32 位随机码>_<书名>.<ext>`，见 `AdminService.uploadBook`）。
 * 两者混用会让随机码又出现在书架上。
 */
data class BookDownloadResult(val displayName: String, val fileName: String)

/**
 * 书城 ViewModel：负责在线书籍的加载、搜索、分类筛选和下载。
 * 与 BookViewModel（本地书架）分离，下载完成后通过回调通知书架刷新。
 */
class BookStoreViewModel(application: Application) : AndroidViewModel(application) {

    private val gson = Gson()

    /** 本地书架存储句柄，用于下载完成后把书籍登记进书架数据库（入库不依赖 Fragment 观察者） */
    private val bookStorage = BookStorage(getApplication())

    /**
     * 书城书架跨端同步：书城下载/入库后 best-effort `POST /api/shelf/{storeBookId}`，
     * 让 PC 端下次 `sync._pull_shelf` 就能拉到这本书；未登录 / 网络失败静默降级，
     * 本机 `ebooks` 行已经落地，云端会由后续进书架页的 GET 对账自然补齐。
     */
    private val shelfSyncManager by lazy { ShelfSyncManager(getApplication()) }

    /** 当前展示的书籍列表 */
    private val _storeBooks = MutableLiveData<List<StoreBook>>(emptyList())
    val storeBooks: LiveData<List<StoreBook>> = _storeBooks

    /** 热门榜 */
    private val _hotBooks = MutableLiveData<List<StoreBook>>(emptyList())
    val hotBooks: LiveData<List<StoreBook>> = _hotBooks

    /** 新书榜 */
    private val _newBooks = MutableLiveData<List<StoreBook>>(emptyList())
    val newBooks: LiveData<List<StoreBook>> = _newBooks

    /** 分类列表 */
    private val _categories = MutableLiveData<List<StoreCategory>>(emptyList())
    val categories: LiveData<List<StoreCategory>> = _categories

    /**
     * 标签列表（标签名 + 在售书籍数）。
     * 与 [categories] 并列的第二维筛选：分类单选、标签也是单选（点一个筛一个），
     * 但一本书可以同时带多个标签 —— 「多标签」是书的属性，不是筛选器的多选。
     */
    private val _tags = MutableLiveData<List<StoreTag>>(emptyList())
    val tags: LiveData<List<StoreTag>> = _tags

    /** 是否处于"浏览全部"模式（true=显示完整列表，false=显示首页榜单） */
    private val _browseMode = MutableLiveData(false)
    val browseMode: LiveData<Boolean> = _browseMode

    /** 加载状态 */
    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    /** 当前筛选条件 */
    var currentCategory: String? = null
        private set
    var currentKeyword: String? = null
        private set
    /** 当前选中的标签（null = 不按标签筛选）；与分类、关键词是叠加关系 */
    var currentTag: String? = null
        private set

    /** 下载完成事件（通知书架刷新 + Toast 提示） */
    private val _downloadEvent = MutableLiveData<BookDownloadResult?>()
    val downloadEvent: LiveData<BookDownloadResult?> = _downloadEvent

    /**
     * 「本地书架数据被本 ViewModel 直接改动」的信号（目前只有书城书名的历史条目对齐）。
     * 与 [downloadEvent] 分开是必要的：这条不该弹出「已加入书架」，只要求界面刷新书架。
     */
    private val _libraryChanged = MutableLiveData(false)
    val libraryChanged: LiveData<Boolean> = _libraryChanged

    fun consumeLibraryChanged() {
        _libraryChanged.postValue(false)
    }

    /** 下载中状态（避免重复点击） */
    private val _downloading = MutableLiveData(false)
    val downloading: LiveData<Boolean> = _downloading

    /** 下载进度（字节级，整本书进度条用）；null 表示当前无进行中的下载 */
    private val _downloadProgress = MutableLiveData<DownloadProgress?>(null)
    val downloadProgress: LiveData<DownloadProgress?> = _downloadProgress

    /**
     * 错误提示事件（一次性，观察后需调用 [consumeErrorEvent]）。
     *
     * 原先加载失败只 Log.e 后把 loading 置 false，界面停在一个空白列表上，
     * 用户既不知道是网络问题还是书城本来就没有书，也没有重试的动机。
     */
    private val _errorEvent = MutableLiveData<String?>()
    val errorEvent: LiveData<String?> = _errorEvent

    fun consumeErrorEvent() {
        _errorEvent.postValue(null)
    }

    /** 加载书城首页（热门 + 新书 + 分类） */
    fun loadHome() {
        // 书城首页对游客开放：服务端 /api/store/home 已 permitAll，无需 token。
        viewModelScope.launch(Dispatchers.IO) {
            _loading.postValue(true)
            try {
                val response = RetrofitClient.apiService.getStoreHome()
                if (response.isSuccessful && response.body()?.get("success") == true) {
                    val body = response.body()!!
                    val hot = parseBookList(body["hotBooks"])
                    val new = parseBookList(body["newBooks"])
                    _hotBooks.postValue(hot)
                    _newBooks.postValue(new)
                    _categories.postValue(parseCategoryList(body["categories"]))
                    // 旧版服务端没有 tags 字段 → parseTagList 返回空列表，标签栏整块隐藏
                    _tags.postValue(parseTagList(body["tags"]))
                    _browseMode.postValue(false)
                    // 顺手把书架上「升级前下载、书名带服务端随机码前缀」的条目对齐成原始书名
                    alignLocalBookNames(hot + new)
                } else {
                    Log.e(TAG, "加载书城首页失败: ${response.code()}")
                    _errorEvent.postValue("加载书城失败：${response.errorMessage()}")
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "加载书城首页异常: ${e.message}")
                _errorEvent.postValue("加载书城失败：${e.toUserMessage()}")
            } finally {
                _loading.postValue(false)
            }
        }
    }

    /** 浏览全部书籍（保留当前的标签筛选：标签是叠加维度，不是浏览模式） */
    fun browseAll() {
        loadBooks(category = null, keyword = null)
    }

    /** 按分类筛选（保留当前标签与关键词） */
    fun filterByCategory(category: String) {
        loadBooks(category = category, keyword = currentKeyword)
    }

    /**
     * 按标签筛选；传 null / 空串表示取消标签筛选。
     *
     * 与分类叠加而不是互斥：用户在「历史」分类里点「战争」，期望看到的是
     * 「历史 ∩ 战争」，而不是全站的战争书。
     */
    fun filterByTag(tag: String?) {
        loadBooks(category = currentCategory, keyword = currentKeyword, tag = tag)
    }

    /** 关键词搜索（保留当前分类与标签） */
    fun search(keyword: String?) {
        val kw = keyword?.trim().takeUnless { it.isNullOrEmpty() }
        loadBooks(category = currentCategory, keyword = kw)
    }

    /** 清除筛选，回到书城首页 */
    fun backToHome() {
        currentCategory = null
        currentKeyword = null
        currentTag = null
        _browseMode.postValue(false)
    }

    private fun loadBooks(category: String?, keyword: String?, tag: String? = currentTag) {
        // 书城列表/搜索对游客开放：服务端 /api/store/books 已 permitAll，无需 token。
        currentCategory = category
        currentKeyword = keyword
        currentTag = tag
        viewModelScope.launch(Dispatchers.IO) {
            _loading.postValue(true)
            try {
                val response = RetrofitClient.apiService.getStoreBooks(
                    category, keyword, tag
                )
                if (response.isSuccessful && response.body()?.get("success") == true) {
                    val body = response.body()!!
                    val books = parseBookList(body["books"])
                    _storeBooks.postValue(books)
                    _browseMode.postValue(true)
                    alignLocalBookNames(books)
                } else {
                    Log.e(TAG, "加载书籍列表失败: ${response.code()}")
                    _errorEvent.postValue("加载书籍列表失败：${response.errorMessage()}")
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "加载书籍列表异常: ${e.message}")
                _errorEvent.postValue("加载书籍列表失败：${e.toUserMessage()}")
            } finally {
                _loading.postValue(false)
            }
        }
    }

    /**
     * 下载书籍到本地书架（复用 ebooks 目录与数据库，与手动导入路径一致）。
     * 支持「仅 WiFi 下载」开关、断点续传（Range 续传）、整本书字节级进度回调。
     */
    fun downloadBook(book: StoreBook, onComplete: (Boolean, String) -> Unit) {
        if (AuthManager.token == null) {
            onComplete(false, "未登录")
            return
        }
        if (_downloading.value == true) {
            onComplete(false, "正在下载其他书籍")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _downloading.postValue(true)
            try {
                val ctx = getApplication<Application>()
                // 仅 WiFi 下载：开关开启且当前非 WiFi 时中止，给出明确引导
                if (AppPrefs.isOnlyWifiDownload(ctx) && !NetworkUtils.isWifiConnected(ctx)) {
                    withContext(Dispatchers.Main) {
                        onComplete(false, "已开启「仅 WiFi 下载」，请连接 WiFi 后重试")
                    }
                    return@launch
                }

                // 保存到 filesDir/ebooks/（与手动导入相同的目录约定）
                val ebooksDir = File(ctx.filesDir, "ebooks")
                if (!ebooksDir.exists()) ebooksDir.mkdirs()

                // 磁盘文件名沿用服务端文件名（`<32 位随机码>_<书名>.<ext>`）。
                // 物理路径由它派生有两个好处：同名书不会互相覆盖；路径本身在应用私有目录里、
                // 用户看不到，所以「名字不好看」只该改展示名，不该动物理文件。
                val fileName = book.fileName
                // 清洗文件名并校验未逃逸 ebooks 目录（防路径穿越）
                val destFile = FileUtils.safeChildFile(ebooksDir, fileName)

                // 书架展示名 = **原始书名**。用 book.fileName 会把服务端为防重名加的随机码
                // 一起显示在书架上（如 `4ddec106...fb_中国的官办经济.pdf`）。
                val displayName = FileUtils.storeBookLocalName(
                    book.title, book.fileName, book.fileExtension
                )

                // 断点续传：本地已有部分文件则续传，否则整文件下载
                val resumeFrom = if (destFile.exists() && destFile.length() > 0) destFile.length() else 0L
                // 远端文件总长度（书籍元数据），用于判断本地是否已经下满
                val remoteTotal = book.fileSize ?: 0L

                // 文件已经下满时不能再发 Range: bytes=N-（N == 文件总长）——
                // 合规服务端会回 416 Range Not Satisfiable，走到下面的 !isSuccessful 分支直接
                // return，文件明明是好的却永远进不了书架，用户反复点下载只会反复看到「下载失败」。
                // 所以先把「已完整」识别出来，跳过网络请求直接入库。
                if (resumeFrom > 0 && remoteTotal > 0 && resumeFrom >= remoteTotal) {
                    Log.d(TAG, "文件已下载完整，直接入库: ${destFile.name} (${resumeFrom}B)")
                    registerDownloadedBook(destFile, displayName, book.id)
                    withContext(Dispatchers.Main) {
                        _downloadEvent.postValue(BookDownloadResult(displayName, destFile.name))
                        onComplete(true, displayName)
                    }
                    return@launch
                }

                val resuming = resumeFrom > 0
                val rangeHeader = if (resuming) "bytes=$resumeFrom-" else null

                val response = RetrofitClient.heavyApi.downloadStoreBook(book.id, rangeHeader)

                // 416 只可能出现在「请求起点已越界」，也就是本地文件实际上已经下满
                // （例如元数据 fileSize 缺失/偏小导致上面的预判没命中）。此时文件是完整的，
                // 同样应该入库，而不是当成失败。
                if (response.code() == 416 && resuming) {
                    Log.d(TAG, "服务端返回 416，视为已下载完整，直接入库: ${destFile.name}")
                    registerDownloadedBook(destFile, displayName, book.id)
                    withContext(Dispatchers.Main) {
                        _downloadEvent.postValue(BookDownloadResult(displayName, destFile.name))
                        onComplete(true, displayName)
                    }
                    return@launch
                }

                if (!response.isSuccessful) {
                    // errorMessage() 解析服务端 errorBody 的业务 message，比裸的 "HTTP 404" 有用得多。
                    val reason = response.errorMessage()
                    withContext(Dispatchers.Main) { onComplete(false, "下载失败：$reason") }
                    return@launch
                }

                val body = response.body()
                if (body == null) {
                    withContext(Dispatchers.Main) { onComplete(false, "下载内容为空") }
                    return@launch
                }

                // 续传成功与否只能由响应码决定，不能只看本地有没有文件。
                // 服务端忽略 Range 时会回 200 + 全量正文，此时若仍按 append 写入，
                // 完整正文会被拼到已下载的那半截文件后面（长度 = 已下 + 全量），书籍静默损坏，
                // 而后面的入库照常执行，书架上多出一本打不开的坏书。
                // 遇到这种情况：删掉不完整的本地文件，改走 append=false 的「.part + rename」全量重下。
                val append = resuming && response.code() == 206
                if (resuming && !append) {
                    Log.w(TAG, "服务端未按 Range 返回 206（code=${response.code()}），放弃续传改为全量重下: ${destFile.name}")
                    if (destFile.exists() && !destFile.delete()) {
                        withContext(Dispatchers.Main) { onComplete(false, "下载失败：无法重置已损坏的本地文件") }
                        return@launch
                    }
                }

                // 计算总字节数：206 取 Content-Range 的总长，200 取 Content-Length
                val totalBytes = if (append) {
                    parseContentRangeTotal(response)
                        ?: (resumeFrom + (body.contentLength().takeIf { it > 0 } ?: 0))
                } else {
                    body.contentLength().takeIf { it > 0 } ?: 0
                }

                // 流式落盘 + 进度回调；续传走追加模式，首下走带上限的安全临时文件
                streamWithProgress(body.byteStream(), destFile, totalBytes, book.id, append)

                Log.d(TAG, "书籍已下载: ${destFile.absolutePath} (${destFile.length()} bytes)")

                // 入库：必须在 ViewModel 内完成，不能依赖 Fragment 对 _downloadEvent 的观察者——
                // 切 Tab 后 Fragment 销毁、观察者随 viewLifecycleOwner 移除，观察者里的
                // importEbookFromFile 不会执行，书只会落盘、不进书架。
                registerDownloadedBook(destFile, displayName, book.id)

                withContext(Dispatchers.Main) {
                    _downloadEvent.postValue(BookDownloadResult(displayName, destFile.name))
                    onComplete(true, displayName)
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "下载书籍异常: ${e.message}", e)
                withContext(Dispatchers.Main) { onComplete(false, "下载失败：${e.toUserMessage()}") }
            } finally {
                _downloading.postValue(false)
                _downloadProgress.postValue(null)
            }
        }
    }

    /**
     * 把已落盘的书籍登记进书架数据库（入库必须在 ViewModel 内完成）。
     * 抽成方法是为了让「正常下完」「本地已完整」「服务端回 416」三条路径共用同一套入库逻辑。
     *
     * @param destFile    本地文件（物理路径，由服务端文件名派生）
     * @param displayName 书架展示名 = 原始书名（见 [FileUtils.storeBookLocalName]）
     * @param storeBookId 书城书籍 id —— **必须写入**，阅读页据此走
     *        `GET api/store/book/{id}/content` 取正文（零上传）；漏写就会退回
     *        「整本上传给服务端解析」，首开要传几十 MB。
     */
    // 必须是 suspend：BookStorage 的 existsByFilePath / insertEbookRecord 都是 suspend fun
    //（内部 withContext(Dispatchers.IO)），在普通函数里调用会直接编译失败。
    private suspend fun registerDownloadedBook(destFile: File, displayName: String, storeBookId: Long) {
        try {
            bookStorage.unmarkBookDeleted(displayName)
            // 去重按 file_path：物理路径不变，所以「升级前下过、重下同一本书」会命中这里
            val existingName = bookStorage.findDisplayNameByFilePath(destFile.absolutePath)
            when {
                existingName == null -> {
                    // 书城下载统一归入「书城」分类，否则落默认空值、书架上显示「未分类」，
                    // 「书城」筛选 chip 永远筛不到书。
                    bookStorage.insertEbookRecord(displayName, destFile.absolutePath, "书城", storeBookId)
                    Log.d(TAG, "书城书籍已入库: $displayName (file=${destFile.name}, storeBookId=$storeBookId)")
                }
                existingName != displayName -> {
                    // 升级前下载的条目：书名里还留着服务端随机码前缀。就地改名（含
                    // audio_segments / favorites / tasks / 动态表），而不是再插一条 ——
                    // 物理路径相同，新插一条会变成书架上两本指向同一文件的书。
                    bookStorage.unmarkBookDeleted(existingName)
                    bookStorage.realignBookName(existingName, displayName)
                    Log.d(TAG, "书城书籍已在书架，书名对齐: $existingName -> $displayName")
                }
                else -> Log.d(TAG, "书架已存在，跳过入库: $displayName")
            }
            // 三条路径都要补 id：升级前入库的条目 store_book_id 还是 -1，
            // 「重下同一本书」正是给它们补上绑定关系的最好时机（不需要等书城列表刷新）。
            bookStorage.setStoreBookIdIfMissing(destFile.absolutePath, storeBookId)

            // 通知服务端「加入书架」：让 PC 端下次 sync._pull_shelf 就能拉到这本。
            // best-effort，失败只打日志——本机 ebooks 行已经落地，云端会在下一次
            // 书架同步（进书架页/下拉刷新时 GET /api/shelf）自然对齐，
            // 不会因为这里的一次抖动出现"本机有云端没有、下次又被删掉"的错乱。
            if (storeBookId > 0L) {
                val added = shelfSyncManager.addToShelfRemote(storeBookId)
                if (!added) {
                    Log.d(TAG, "云端书架加入未成功（下次同步自愈）: storeBookId=$storeBookId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "书城书籍入库失败: ${e.message}", e)
        }
    }

    /**
     * 书城列表加载后，把本地书架里的书城书「对齐」一遍：
     *
     *  1. **回填 `store_book_id`**（v10 才有的列）—— 升级前下载的书只有文件名，没有书城 id，
     *     不回填的话打开它仍会「整本上传给服务端解析」。这里拿 `fileName ↔ 本地文件` 的
     *     对应关系补上，一次列表刷新即可完成，不需要用户重新下载。
     *  2. 把「书名带服务端随机码前缀」的旧条目改成原始书名。
     *
     * 只在书城列表加载后触发：此时才拿得到 `title ↔ 服务端文件名` 的对应关系，
     * 而本地库里只有文件名。比对与改名都在本地完成，不依赖重新下载。
     *
     * 目标名被别的书占用时 [BookStorage.realignBookName] 会放弃（不做合并），
     * 这里只是照常继续处理下一本。
     */
    private suspend fun alignLocalBookNames(books: List<StoreBook>) {
        if (books.isEmpty()) return
        val localPaths = bookStorage.getEbookPathsByName()
        if (localPaths.isEmpty()) return

        var changed = false
        for (book in books) {
            // 书架上的旧名 = 服务端文件名的清洗结果（downloadBook 当初就是用它入库的）
            val legacyName = FileUtils.sanitizeFileName(book.fileName)
            val targetName = FileUtils.storeBookLocalName(book.title, book.fileName, book.fileExtension)
            // 两种名字都认：升级前入库的是 legacyName，新版本入库的是 targetName。
            // 早先只匹配 legacyName 并在「已经对齐」时 continue，于是已经叫对名字的书
            // 永远走不到这里 —— 而 v10 的书城 id 回填恰恰要靠这次遍历。
            val localName = when {
                legacyName in localPaths -> legacyName
                targetName in localPaths -> targetName
                else -> continue
            }
            val localPath = localPaths[localName].orEmpty()
            if (localPath.isNotEmpty()) {
                bookStorage.setStoreBookIdIfMissing(localPath, book.id)
            }
            if (localName == targetName) continue
            if (bookStorage.realignBookName(localName, targetName)) {
                bookStorage.unmarkBookDeleted(localName)
                changed = true
            }
        }
        // 通知界面刷新书架：改名后条目要重新读一次才显示新书名
        if (changed) _libraryChanged.postValue(true)
    }

    /**
     * 把响应流写入 [destFile] 并上报字节级进度。
     * [append]=true 时为断点续传：直接追加到目标文件（跳过临时文件，避免覆盖已下部分）；
     * [append]=false 时先写到同目录 .part 临时文件，成功后再原子 rename，中途失败不留坏文件。
     *
     * [kind] 决定 UI 的文案（电子书 / 合成音频）；[limitBytes] 按内容类型区分上限 ——
     * 电子书 200MB（[MAX_IMPORT_BYTES]）。
     */
    private fun streamWithProgress(
        input: java.io.InputStream,
        destFile: File,
        totalBytes: Long,
        bookId: Long,
        append: Boolean,
        kind: DownloadKind = DownloadKind.BOOK,
        limitBytes: Long = MAX_IMPORT_BYTES
    ) {
        val target = if (append) destFile else File(destFile.parentFile, destFile.name + ".part")
        var done = if (append) destFile.length() else 0L
        // 节流用的时间/百分比水位，见下面的 post 条件
        var lastPostAt = 0L
        var lastPercent = -1
        // 先补发一帧 0 进度：进度对话框的弹出由「收到 progress」或「下载标志位为真」驱动，
        // 而首帧真实数据要等服务端开始应答才到（音频 zip 是边打包边写出的 chunked 流，
        // 首字节可能要等一两秒）。补这一帧能让界面立刻显示进度，而不是先空窗再突然出现。
        _downloadProgress.postValue(DownloadProgress(bookId, done, totalBytes, append, kind))
        input.use { inp ->
            java.io.FileOutputStream(target, append).use { out ->
                val buf = ByteArray(8192)
                var read: Int
                while (inp.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    done += read
                    // 体积上限保护，避免无限流写满内部存储（上限按内容类型传入）
                    if (done > limitBytes) {
                        throw java.io.IOException("文件过大，已超过 ${limitBytes / 1024 / 1024}MB 上限")
                    }
                    // 不能每 8KB 就 postValue——200MB 的文件会产生约 25600 次主线程投递，
                    // 进度条根本刷不过来，UI 卡顿甚至 ANR。故仅在「距上次 ≥200ms 或百分比变化 ≥1%」时 post。
                    val now = System.currentTimeMillis()
                    val percent = if (totalBytes > 0) ((done * 100) / totalBytes).toInt() else -1
                    if (now - lastPostAt >= PROGRESS_POST_INTERVAL_MS || (percent >= 0 && percent != lastPercent)) {
                        lastPostAt = now
                        lastPercent = percent
                        _downloadProgress.postValue(DownloadProgress(bookId, done, totalBytes, append, kind))
                    }
                }
            }
        }
        if (!append && !target.renameTo(destFile)) {
            throw java.io.IOException("无法把临时文件改名为目标文件: ${destFile.name}")
        }
        // 补一发最终进度：节流会吃掉最后一帧，不补的话进度条会停在 99% 然后被 dismiss。
        _downloadProgress.postValue(DownloadProgress(bookId, done, totalBytes, append, kind))
    }

    /** 从 Content-Range: bytes start-end/total 解析总长度 */
    private fun parseContentRangeTotal(response: retrofit2.Response<okhttp3.ResponseBody>): Long? {
        val cr = response.headers()["Content-Range"] ?: return null
        val slash = cr.indexOf('/')
        if (slash < 0) return null
        return cr.substring(slash + 1).toLongOrNull()
    }

    fun consumeDownloadEvent() {
        _downloadEvent.postValue(null)
    }

    // ---------- JSON 解析（Map<String, Any> → 强类型） ----------

    private fun parseBookList(raw: Any?): List<StoreBook> {
        if (raw == null) return emptyList()
        return try {
            val json = gson.toJson(raw)
            gson.fromJson(json, BOOK_LIST_TYPE) ?: emptyList()
        } catch (e: Exception) {
            // 打完整堆栈：这里过去只打 e.message，Missing type parameter. 这类
            // R8 泛型签名问题看不出是哪个泛型、也不知道发生在解析阶段
            Log.e(TAG, "解析书籍列表失败", e)
            emptyList()
        }
    }

    private fun parseCategoryList(raw: Any?): List<StoreCategory> {
        if (raw == null) return emptyList()
        return try {
            val json = gson.toJson(raw)
            gson.fromJson(json, CATEGORY_LIST_TYPE) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "解析分类列表失败", e)
            emptyList()
        }
    }

    private fun parseTagList(raw: Any?): List<StoreTag> {
        if (raw == null) return emptyList()
        return try {
            val json = gson.toJson(raw)
            gson.fromJson(json, TAG_LIST_TYPE) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "解析标签列表失败", e)
            emptyList()
        }
    }
}

