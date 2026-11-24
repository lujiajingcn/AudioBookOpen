package com.audiobook.audiobookplayer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * 发现页容器：顶部标签页 + 可左右滑动的分页区。
 *
 * - 标签页 0「参考音频」：ReferenceAudioTabFragment，分享排行榜
 * - 标签页 1「讨论区」：DiscussionFragment，社区帖子
 *
 * 「推荐」标签页（编辑推荐书单 + 社区入口三键）已按产品需求下线，
 * 服务端 `/api/store/editor-picks` 与 [MainActivity.navigateToStore] 保留不动，
 * 未来若需要新入口可直接复用。
 *
 * 帖子列表（读）在 DiscussionFragment，发帖（写）在右下角「＋」弹出的 CreatePostBottomSheet，
 * 两者靠 Fragment Result 通信；本类只负责标签页、分页的装配，以及「＋」的显隐与弹窗。
 */
class DiscoverFragment : Fragment() {

    companion object {
        /** 标签页下标 */
        const val TAB_REFERENCE_AUDIO = 0
        const val TAB_DISCUSSION = 1
        private const val TAB_COUNT = 2

        private val TAB_TITLES = arrayOf("参考音频", "讨论区")

        /** 进入发现页时默认停留的标签页：讨论区 */
        private const val DEFAULT_TAB = TAB_DISCUSSION
    }

    /** 由 onViewCreated 注入，供 [selectTab] 跨标签页跳转 */
    private lateinit var viewPager: ViewPager2

    /** 右下角「＋」：发帖入口，仅「讨论区」标签页可见 */
    private lateinit var fabCreatePost: FloatingActionButton

    /**
     * 页码回调。存成字段是为了在 onDestroyView 反注册 ——
     * ViewPager2 持有回调，而回调会碰 FAB，不解除会在视图销毁后留下悬空引用。
     */
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            syncCreatePostFab(position)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_discover, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tabLayout = view.findViewById<TabLayout>(R.id.tabLayout)
        viewPager = view.findViewById(R.id.viewPager)
        fabCreatePost = view.findViewById(R.id.fabCreatePost)

        viewPager.adapter = DiscoverPagerAdapter(this)
        // 首次进入落在讨论区；配置变更（旋转等）后由 ViewPager2 自行恢复原页签
        if (savedInstanceState == null) {
            viewPager.setCurrentItem(DEFAULT_TAB, false)
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = TAB_TITLES[position]
        }.attach()

        // 先按当前页签同步一次（不赌 setCurrentItem 是否派发 onPageSelected），
        // 之后交给页码回调。首次不要动画，否则进页面时会看到按钮从无到有地缩放一下。
        syncCreatePostFab(viewPager.currentItem, animate = false)
        viewPager.registerOnPageChangeCallback(pageChangeCallback)

        fabCreatePost.setOnClickListener { openCreatePostSheet() }
    }

    /** FAB 只在「讨论区」出现：另外两页没有发帖语义，露出来只会误导 */
    private fun syncCreatePostFab(position: Int, animate: Boolean = true) {
        val shouldShow = position == TAB_DISCUSSION
        if (!animate) {
            fabCreatePost.visibility = if (shouldShow) View.VISIBLE else View.GONE
            return
        }
        if (shouldShow) fabCreatePost.show() else fabCreatePost.hide()
    }

    private fun openCreatePostSheet() {
        // 发帖是写操作，未登录时经宿主弹引导；登录后由 after 闭包接着开面板。
        // 发现页整体对游客开放浏览（帖子列表 / 参考音频榜单 / 编辑推荐），
        // 只有真正要写用户状态的动作才拦。
        val show = {
            // 用 childFragmentManager：本面板与 ViewPager2 里的 DiscussionFragment 同属这个
            // FragmentManager，发帖结果（Fragment Result）才能直接投递到列表页。
            CreatePostBottomSheet().show(childFragmentManager, "create_post")
            Unit
        }
        val host = activity as? MainActivity
        if (host != null) host.requireLogin("发帖需要登录后使用", show) else show()
    }

    /** 跨标签页跳转（推荐页的入口按钮调用） */
    fun selectTab(index: Int) {
        if (::viewPager.isInitialized) viewPager.currentItem = index
    }

    override fun onDestroyView() {
        // 先摘回调再拆 adapter：回调会碰 FAB，留着就成了悬空引用
        if (::viewPager.isInitialized) viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        // FragmentStateAdapter 绑定的是 viewLifecycleOwner，view 销毁时解绑，
        // 避免 ViewPager2 持有已销毁的 RecyclerView 引用（Fragment 重建时复用会泄漏）
        view?.findViewById<ViewPager2>(R.id.viewPager)?.adapter = null
        super.onDestroyView()
    }

    /** 发现页两个标签页对应的 Fragment */
    private class DiscoverPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

        override fun getItemCount(): Int = TAB_COUNT

        override fun createFragment(position: Int): Fragment = when (position) {
            TAB_REFERENCE_AUDIO -> ReferenceAudioTabFragment()
            TAB_DISCUSSION -> DiscussionFragment()
            else -> throw IllegalArgumentException("未知的发现页标签: $position")
        }
    }
}
