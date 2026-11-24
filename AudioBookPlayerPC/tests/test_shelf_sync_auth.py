# -*- coding: utf-8 -*-
"""PC 端「云端书架不同步」的回归测试。

线上故障（2026-11-06）：手机端（AudioBookPlayer）在书城加入了一本书，
PC 端（AudioBookPlayerPC）书架上**一直看不到**它，且界面毫无异样。

根因链（逐条已用 nginx access.log + 服务端库 + 线上实测闭环验证）：

1. 服务端 JWT 有效期 24h（``jwt.expiration: 86400000``）。PC 端 token 签发于
   2026-11-05 11:12:32 ⇒ **11-06 11:12:32 过期**。
   线上日志完全吻合：最后一次成功 validate 是 10:33:54（200，body 72 字节 =
   ``{"valid":true,"isAdmin":false}``），随后 13:36:53 那一批请求**全线 401**
   （body 57 字节 = ``{"valid":false,"message":"未登录或登录已过期"}``）。
2. 401 → ``ApiClient._handle_response`` → ``on_unauthorized`` →
   ``AuthManager.handle_unauthorized()`` → ``logout()`` ⇒ **删掉 auth.json、
   token 置 None**。
3. token 为 None ⇒ ``SyncManager.is_available()`` 返回 False ⇒
   ``_run_sync_async`` 在入口直接 return ⇒ **一次网络请求都不发**。
4. 而界面除了状态栏一行"登录已过期"之外**没有任何提示**，
   书架页看起来和登录时一模一样 ⇒ 用户完全无从判断是掉登录了。

本测试锁住修复后的行为：
  - 未登录 ⇒ 书架页**显式**提示云端不会同步（横幅 + 重新登录入口）；
  - 未登录 ⇒ 手动同步给出明确说法，而不是静默无反应；
  - 登录态 ⇒ 一次同步就能把服务端新增的书城书拉进本地 books 表；
  - ``upsert_store_shelf_book`` 的 category 两分支同源（不再首次 'store'、
    再次同步后变成服务端分类的漂移）。

运行：``python tests/test_shelf_sync_auth.py``（需 PySide6，可离线跑）。
"""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication  # noqa: E402

from app.core.config import AppConfig  # noqa: E402
from app.db.database import LocalDatabase  # noqa: E402
from app.core.sync import SyncManager, ERR_KIND_NOT_AUTHED  # noqa: E402
from app.ui.pages.shelf_page import ShelfPage  # noqa: E402


class _FakeApi:
    """最小 API 替身：覆盖 ``sync_all`` 会用到的全部接口。

    只提供书架数据，其余接口返回空（测试只关心书架这一条链路）。
    """

    def __init__(self, shelf_items=None):
        self.shelf_items = shelf_items or []
        self.get_shelf_calls = 0

    # --- 书架 ---
    def get_shelf(self):
        self.get_shelf_calls += 1
        return list(self.shelf_items)

    def remove_from_shelf(self, store_book_id):  # pragma: no cover - 不用
        return {"removed": True}

    # --- 其余同步源：返回空 ---
    def get_user_tasks_raw(self, page=1, size=100):
        return {"tasks": [], "total": 0}

    def get_favorites_raw(self):
        return {"favorites": []}

    def get_word_book_raw(self):
        return {"words": []}

    def get_audio_coverage_conditional(self, if_none_match=""):
        # 真实契约（ApiClient.get_audio_coverage_conditional）：返回 (status, etag, data)
        return 304, "", None

    # --- Phase4 LWW 两类：返回空权威全量（本测试只关心书架链路） ---
    def sync_user_settings(self, items):
        return {"success": True, "items": [], "version": 0}

    def sync_book_meta(self, items):
        return {"success": True, "items": [], "total": 0}


class ShelfSyncAuthTest(unittest.TestCase):
    def setUp(self):
        self.app = QApplication.instance() or QApplication([])
        import tempfile
        self.tmp = tempfile.mkdtemp()
        self.cfg = AppConfig(data_dir=self.tmp)
        self.db = LocalDatabase(self.cfg)

    def tearDown(self):
        try:
            self.db.close()
        except Exception:  # noqa: BLE001
            pass

    # ---------- 缺陷 1：未登录时书架页必须显式提示 ----------

    def test_shelf_page_shows_banner_when_not_logged_in(self):
        """未登录 ⇒ 书架页必须显式提示"云端书架不会同步"，并给出重新登录入口。"""
        api = _FakeApi()
        page = ShelfPage(self.cfg, api, None, self.db)
        page.refresh()
        self.assertTrue(page.banner.isVisible() or not page.banner.isHidden(),
                        "未登录时横幅必须出现，否则用户无从判断书架为何不更新")
        self.assertIn("不会同步", page.banner_lbl.text())
        self.assertFalse(page.btn_sync.isEnabled(),
                         "未登录时「同步」按钮应置灰（同步在未登录时直接跳过）")

    def test_shelf_page_hides_banner_when_logged_in(self):
        """登录态 ⇒ 横幅隐藏（否则会一直挂着"不会同步"的误导性提示）。"""
        api = _FakeApi()
        page = ShelfPage(self.cfg, api, None, self.db)
        self.cfg.token = "dummy-token-for-test"
        try:
            page.refresh()
            self.assertTrue(page.banner.isHidden(),
                            "登录后横幅必须隐藏")
            self.assertTrue(page.btn_sync.isEnabled(),
                            "登录后「同步」按钮应可用")
        finally:
            self.cfg.token = None

    def test_auth_changed_updates_banner(self):
        """登录态变化 ⇒ 横幅跟着变（用户登录成功后提示要消失）。"""
        api = _FakeApi()
        page = ShelfPage(self.cfg, api, None, self.db)
        self.cfg.token = "dummy-token-for-test"
        try:
            page.auth_changed(True, "someone")
            self.assertTrue(page.banner.isHidden())
        finally:
            self.cfg.token = None
        page.auth_changed(False, None)
        self.assertFalse(page.banner.isHidden(),
                         "登出后横幅必须重新出现")

    # ---------- 缺陷 2：未登录时同步必须给说法，而不是静默 ----------

    def test_sync_reports_not_authed(self):
        """未登录时 sync_all 必须报"未登录"错误（供 UI 展示），且不发请求。"""
        api = _FakeApi()
        sync = SyncManager(self.cfg, api, self.db)
        res = sync.sync_all()
        self.assertEqual(0, api.get_shelf_calls,
                         "未登录时不应发出任何书架请求")
        self.assertEqual(ERR_KIND_NOT_AUTHED, res.error_kinds.get("auth"))
        self.assertTrue(res.errors, "未登录时必须留下错误信息，不能静默")

    # ---------- 缺陷 3：登录态下一次同步就能拉到服务端新增的书 ----------

    def test_pull_shelf_adds_new_server_book(self):
        """服务端新增的书城书 ⇒ 一次同步就应出现在本地 books 表。"""
        api = _FakeApi(shelf_items=[{
            "storeBookId": 42, "title": "论持久战全文",
            "author": "毛泽东", "category": "军事", "hasAudio": True,
        }])
        self.cfg.token = "dummy-token-for-test"
        try:
            sync = SyncManager(self.cfg, api, self.db)
            res = sync.sync_all()
            self.assertEqual(1, api.get_shelf_calls)
            self.assertEqual([], res.errors, f"同步不应报错：{res.errors}")

            books = self.db.get_books()
            self.assertEqual(1, len(books))
            self.assertEqual(42, books[0].store_book_id)
            self.assertEqual("论持久战全文", books[0].display_name)
            self.assertTrue(books[0].has_audio)
        finally:
            self.cfg.token = None

    def test_pull_shelf_updates_existing_book_in_place(self):
        """服务端已有的书 ⇒ 更新元信息，**不新建行**（避免书架出现重复项）。"""
        api = _FakeApi(shelf_items=[{
            "storeBookId": 42, "title": "论持久战全文",
            "author": "毛泽东", "category": "军事", "hasAudio": True,
        }])
        self.cfg.token = "dummy-token-for-test"
        try:
            sync = SyncManager(self.cfg, api, self.db)
            sync.sync_all()
            # 本地把书名改掉（模拟用户改名），同步后应**保留**用户改名
            self.db._conn().execute(
                "UPDATE books SET display_name='我的改名' WHERE store_book_id=42")
            self.db._conn().commit()

            api.shelf_items = [{
                "storeBookId": 42, "title": "论持久战全文",
                "author": "毛泽东", "category": "军事", "hasAudio": True,
            }]
            sync.sync_all()
            books = self.db.get_books()
            self.assertEqual(1, len(books), "同步不应新建重复行")
            self.assertEqual("我的改名", books[0].display_name,
                             "同步不应覆盖用户改过的书名")
        finally:
            self.cfg.token = None

    # ---------- 缺陷 4：category 两分支同源 ----------

    def test_category_is_consistent_across_syncs(self):
        """同一本书：首次同步与再次同步后 category 必须一致（不能从 'store' 漂移成服务端分类）。"""
        api = _FakeApi(shelf_items=[{
            "storeBookId": 42, "title": "论持久战全文",
            "author": "毛泽东", "category": "军事", "hasAudio": True,
        }])
        self.cfg.token = "dummy-token-for-test"
        try:
            sync = SyncManager(self.cfg, api, self.db)
            sync.sync_all()
            first = self.db.get_books()[0].category
            sync.sync_all()
            second = self.db.get_books()[0].category
            self.assertEqual(first, second,
                             f"category 在两次同步之间漂移了：{first!r} → {second!r}")
            self.assertEqual("军事", second,
                             "category 应与服务端分类同源（两分支一致）")
        finally:
            self.cfg.token = None

    def test_upsert_insert_and_update_use_same_category(self):
        """直接调 upsert：INSERT 分支与 UPDATE 分支写入的 category 必须相同。"""
        self.db.upsert_store_shelf_book(store_book_id=7, title="甲",
                                        author="", category="军事", has_audio=True)
        inserted = self.db.get_book_by_store_id(7)
        self.assertEqual("军事", inserted.category,
                         "INSERT 分支丢弃了 category 参数（硬编码 'store'）")
        self.db.upsert_store_shelf_book(store_book_id=7, title="甲",
                                        author="", category="军事", has_audio=True)
        updated = self.db.get_book_by_store_id(7)
        self.assertEqual(inserted.category, updated.category,
                         "两次 upsert 之间 category 漂移")


if __name__ == "__main__":
    unittest.main(verbosity=2)
