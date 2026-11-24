package com.audiobook.audiobookplayer

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.audiobook.audiobookplayer.model.CreatePostRequest
import com.audiobook.audiobookplayer.model.SharedReferenceAudio
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.UUID

/**
 * 「讨论区」右下角「＋」弹出的发帖面板。
 *
 * 从底部滑出的表单：标题（可选）/ 正文（≤500 字）/ 图片 / 嵌入参考音频，
 * 发布成功后通过 Fragment Result 通知列表刷新，然后自行关闭。
 *
 * 三个设计要点：
 * - **表单逻辑整块搬来这里**，不再在 DiscussionFragment 里内联渲染，列表因此能占满整屏；
 * - 结果回传用 Fragment Result API（而不是持有宿主引用回调）：本面板与 DiscussionFragment
 *   同为 DiscoverFragment 的 childFragment，`parentFragmentManager` 就是同一个
 *   childFragmentManager，结果能直接投递到还在（或将来才创建）的列表页；
 * - 图片选择、参考音频选择都走本类自己的 registerForActivityResult / 子对话框，
 *   关闭面板即整体销毁，不会有跨页残留的「草稿」状态。
 */
class CreatePostBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "CreatePostSheet"

        /** 结果回传键；列表页用同一个 key 监听 */
        const val REQUEST_KEY = "create_post_request"

        /** 结果内容：条数（本次一律 1），列表页据此决定是否刷新 */
        const val RESULT_POSTED_COUNT = "posted_count"

        /** 与服务端 PostController.MAX_CONTENT_LENGTH 保持一致 */
        private const val MAX_CONTENT_LENGTH = 500

        /** 图片上限 10MB，与服务端一致 */
        private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

        private val ALLOWED_IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif")

        /** 拉取可嵌入的参考音频排行条数 */
        private const val REF_AUDIO_LIMIT = 50

        /**
         * 面板高度**上限**（占屏比）。
         *
         * 面板本身是「随内容」高度的，这个值只在小屏 / 横屏时兜底夹一下：
         * 上方始终留出约 1/3 的空间，让人一眼看出「这是个浮层、底下还是讨论区」，
         * 而不是让面板一直顶到状态栏。
         */
        private const val HEIGHT_SCREEN_RATIO = 0.66f
    }

    private lateinit var etPostTitle: EditText
    private lateinit var etPostContent: EditText
    private lateinit var tvCharCount: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnPublish: Button
    private lateinit var btnAddImage: Button
    private lateinit var btnAddRefAudio: Button
    private lateinit var llAttachments: LinearLayout

    /** 本次发帖已选的图片相对地址 */
    private val selectedImageUrls = mutableListOf<String>()

    /** 本次发帖嵌入的参考音频 */
    private var selectedRefAudioId: Long? = null
    private var selectedRefAudioName: String? = null

    /** 选图后上传 */
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadImage(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_create_post, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etPostTitle = view.findViewById(R.id.etPostTitle)
        etPostContent = view.findViewById(R.id.etPostContent)
        tvCharCount = view.findViewById(R.id.tvCharCount)
        tvResult = view.findViewById(R.id.tvResult)
        btnPublish = view.findViewById(R.id.btnPublish)
        btnAddImage = view.findViewById(R.id.btnAddImage)
        btnAddRefAudio = view.findViewById(R.id.btnAddRefAudio)
        llAttachments = view.findViewById(R.id.llAttachments)

        etPostContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tvCharCount.text = "${s?.length ?: 0} / $MAX_CONTENT_LENGTH"
            }
        })

        btnAddImage.setOnClickListener { imagePicker.launch("image/*") }
        btnAddRefAudio.setOnClickListener { addRefAudio() }
        btnPublish.setOnClickListener {
            Log.d(TAG, "点击发布，输入框长度=${etPostContent.text?.length ?: -1}")
            publishPost()
        }

        // 直接进正文：省掉一次多余的点击（标题是可选的，多数帖子不填）
        etPostContent.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return

        // 键盘弹出时把整个面板顶到键盘之上，而不是让键盘盖住底部的「发布」按钮。
        //
        // ⚠️ 这个常量在 API 30 起被标记 deprecated，但**仍然必须设置**：那条废弃说明针对的是
        // 「已改成边到边（decorFitsSystemWindows=false）的窗口应改用 insets 处理」，
        // 而对话框窗口默认仍是 fits-system-windows —— 不设就完全不会 resize。
        // 2026-09-29 设备实测（Android 17 / API 37）：此前按 SDK_INT >= R 跳过设置，
        // 结果键盘直接盖住整行按钮，「发布」的坐标落进了键盘区域、点下去面板纹丝不动
        // （截图 cp_06_after_publish.png 留证）。
        @Suppress("DEPRECATION")
        val resizeMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        dialog.window?.setSoftInputMode(resizeMode)

        val sheet = dialog.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return

        // 高度随内容，而不是写死屏高比例 —— 理由见 dialog_create_post.xml 顶部注释
        sheet.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        sheet.requestLayout()

        BottomSheetBehavior.from(sheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            // 内容高于默认锚点时不要缩成「半屏」态，否则一打开就是折叠的
            skipCollapsed = true
            isFitToContents = true
        }

        // 兜底：小屏上内容仍可能超过屏高的 HEIGHT_SCREEN_RATIO，此时夹住高度，
        // 交给根部的 NestedScrollView 滚动，而不是把面板顶到状态栏上
        sheet.post {
            val max = (resources.displayMetrics.heightPixels * HEIGHT_SCREEN_RATIO).toInt()
            if (sheet.height > max) {
                sheet.layoutParams.height = max
                sheet.requestLayout()
            }
        }
    }

    // ---------- 发帖 ----------

    private fun publishPost() {
        if (AuthManager.token == null) {
            Log.w(TAG, "发帖中止：token 为空（未登录）")
            showResult("发帖失败：未登录（token 为空）", isError = true)
            context?.let { Toast.makeText(it, "请先登录", Toast.LENGTH_SHORT).show() }
            return
        }

        val content = etPostContent.text?.toString()?.trim().orEmpty()
        if (content.isEmpty()) {
            Log.w(TAG, "发帖中止：内容为空")
            showResult("发帖失败：内容为空", isError = true)
            context?.let { Toast.makeText(it, "请输入帖子内容", Toast.LENGTH_SHORT).show() }
            return
        }
        if (content.length > MAX_CONTENT_LENGTH) {
            Log.w(TAG, "发帖中止：内容超长 ${content.length} 字")
            showResult("发帖失败：内容 ${content.length} 字，超过上限 $MAX_CONTENT_LENGTH", isError = true)
            context?.let { Toast.makeText(it, "内容不能超过 $MAX_CONTENT_LENGTH 字", Toast.LENGTH_SHORT).show() }
            return
        }

        // ?.trim() 已经让接收者变成 String?，再直接 .takeIf 属于可空接收者上的非安全调用，
        // 必须继续用 ?. 链式下去。
        val title = etPostTitle.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        showResult("正在发布…", isError = false)
        Log.d(TAG, "发出 POST /api/posts，内容长度=${content.length}")
        btnPublish.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val req = CreatePostRequest(
                    content = content,
                    title = title,
                    imageUrls = if (selectedImageUrls.isEmpty()) null else selectedImageUrls.toList(),
                    refAudioId = selectedRefAudioId
                )
                val errorMsg = withContext(Dispatchers.IO) {
                    val resp = RetrofitClient.apiService.createPost(req)
                    if (resp.isSuccessful) null else resp.errorMessage()
                }

                if (errorMsg == null) {
                    Log.d(TAG, "发帖成功")
                    Toast.makeText(requireContext(), "发布成功", Toast.LENGTH_SHORT).show()
                    notifyPostedAndDismiss()
                } else {
                    showResult("发帖失败：$errorMsg", isError = true)
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = "${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "发帖异常（请求未成功发出或响应解析失败）", e)
                showResult("发帖失败：$detail", isError = true)
                context?.showError("发布失败：${e.toUserMessage()}")
            } finally {
                // 面板可能在等待期间被用户划走（view 已销毁），此时不要再碰按钮
                if (isAdded && view != null) btnPublish.isEnabled = true
            }
        }
    }

    /**
     * 回传「已发布」并关闭面板。
     *
     * 注意 API 归属：setFragmentResult 是 **FragmentManager** 上的方法，Fragment 自身只有
     * setFragmentResultListener 的兄弟 API（实为 FragmentManager 的同名方法）——
     * 在 Fragment 里直接调 setFragmentResult 会 Unresolved reference（已实测）。
     * 这里用 parentFragmentManager，即「显示本面板的那个 FragmentManager」=
     * DiscoverFragment 的 childFragmentManager，与 DiscussionFragment 注册监听用的是同一个。
     *
     * dismiss 用 dismissAllowingStateLoss：发帖是网络回调，回来时宿主 Activity 可能刚好
     * 进入后台（state 已保存），此时普通 dismiss 会抛 IllegalStateException ——
     * 而帖子其实已经发出去了，不该因为关不掉弹窗就崩一次。
     */
    private fun notifyPostedAndDismiss() {
        parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_POSTED_COUNT to 1))
        dismissAllowingStateLoss()
    }

    // ---------- 图片上传 ----------

    private fun uploadImage(uri: Uri) {
        if (AuthManager.token == null) {
            context?.let { Toast.makeText(it, "请先登录", Toast.LENGTH_SHORT).show() }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ext = uri.getFileName(requireContext())
                    ?.substringAfterLast('.', "")
                    ?.lowercase()
                    ?.takeIf { it in ALLOWED_IMAGE_EXT } ?: "jpg"
                val temp = withContext(Dispatchers.IO) { uriToTempFile(uri, ext) }
                if (temp == null) {
                    context?.showError("无法读取所选图片")
                    return@launch
                }
                // v5/A9：临时文件必须在 finally 里删。
                // 原实现只在「上传成功且响应可解析」那条路 `temp.delete()`，
                // 上传抛异常 / 提前 return 都会把文件留在 cacheDir —— 反复选图会一路涨。
                try {
                    val part = MultipartBody.Part.createFormData(
                        "file", "upload.$ext",
                        temp.asRequestBody("image/*".toMediaType())
                    )
                    val resp = withContext(Dispatchers.IO) { RetrofitClient.apiService.uploadPostImage(part) }
                    if (!resp.isSuccessful || resp.body()?.get("success") != true) {
                        Log.e(TAG, "图片上传失败: HTTP ${resp.code()}")
                        context?.showError("图片上传失败：${resp.errorMessage()}")
                        return@launch
                    }
                    val url = resp.body()?.get("url") as? String
                    if (url.isNullOrBlank()) {
                        context?.showError("图片上传失败：未返回地址")
                        return@launch
                    }
                    selectedImageUrls.add(url)
                    renderAttachments()
                    Toast.makeText(requireContext(), "已添加 1 张图片", Toast.LENGTH_SHORT).show()
                } finally {
                    temp.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.showError("图片上传失败：${e.toUserMessage()}")
            }
        }
    }

    private suspend fun uriToTempFile(uri: Uri, ext: String): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(requireContext().cacheDir, "post_images").apply { mkdirs() }
            val file = File(dir, "up_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext")
            val input = requireContext().contentResolver.openInputStream(uri) ?: return@withContext null
            input.saveToWithLimit(file, MAX_IMAGE_BYTES)
            if (file.length() == 0L) {
                file.delete()
                return@withContext null
            }
            file
        } catch (e: Exception) {
            Log.w(TAG, "复制图片到临时文件失败: ${e.message}")
            null
        }
    }

    // ---------- 嵌入参考音频 ----------

    private fun addRefAudio() {
        if (AuthManager.token == null) {
            context?.let { Toast.makeText(it, "请先登录", Toast.LENGTH_SHORT).show() }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getSharedReferences(REF_AUDIO_LIMIT)
                }
                if (!resp.isSuccessful) {
                    context?.showError("加载参考音频失败")
                    return@launch
                }
                val body = resp.body() ?: return@launch
                @Suppress("UNCHECKED_CAST")
                val raw = body["references"] as? List<Map<String, Any>> ?: return@launch
                if (raw.isEmpty()) {
                    Toast.makeText(requireContext(), "还没有可嵌入的分享参考音频", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val items = raw.mapNotNull { m ->
                    val id = (m["id"] as? Number)?.toLong() ?: return@mapNotNull null
                    val name = m["name"] as? String ?: return@mapNotNull null
                    SharedReferenceAudio(
                        id = id,
                        name = name,
                        rank = (m["rank"] as? Number)?.toInt() ?: 0,
                        fileName = m["fileName"] as? String,
                        useCount = (m["useCount"] as? Number)?.toLong() ?: 0,
                        ownerName = m["ownerName"] as? String ?: ""
                    )
                }
                withContext(Dispatchers.Main) { showRefAudioPicker(items) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.showError("加载参考音频失败：${e.toUserMessage()}")
            }
        }
    }

    private fun showRefAudioPicker(items: List<SharedReferenceAudio>) {
        val labels = items.map { "${it.name}（使用 ${it.useCount} 次）" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("嵌入哪条参考音频")
            .setItems(labels) { _, which ->
                selectedRefAudioId = items[which].id
                selectedRefAudioName = items[which].name
                btnAddRefAudio.isEnabled = false
                renderAttachments()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 附件筹码（图片 / 参考音频） ----------

    private fun renderAttachments() {
        val ctx = context ?: return
        llAttachments.removeAllViews()
        selectedImageUrls.forEachIndexed { idx, _ ->
            llAttachments.addView(makeChip(ctx, "图片${idx + 1} ×") {
                selectedImageUrls.removeAt(idx)
                renderAttachments()
            })
        }
        if (selectedRefAudioId != null) {
            llAttachments.addView(makeChip(ctx, "参考音频：${selectedRefAudioName ?: selectedRefAudioId} ×") {
                selectedRefAudioId = null
                selectedRefAudioName = null
                btnAddRefAudio.isEnabled = true
                renderAttachments()
            })
        }
    }

    /**
     * 附件筹码。底色/文字色写死不跟主题 —— 浅色底(#E0E0E0)配深字(#333333)
     * 在 DayNight 两种主题下都成立（筹码本身不承载正文，不需要夜间降亮）。
     */
    private fun makeChip(
        ctx: android.content.Context,
        label: String,
        onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = label
        textSize = 12f
        setTextColor(0xFF333333.toInt())
        setPadding(16, 6, 16, 6)
        setBackgroundColor(0xFFE0E0E0.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = 16 }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun showResult(message: String, isError: Boolean) {
        tvResult.text = message
        tvResult.setTextColor(if (isError) 0xFFCF1322.toInt() else 0xFF2E7D32.toInt())
        tvResult.visibility = View.VISIBLE
    }
}
