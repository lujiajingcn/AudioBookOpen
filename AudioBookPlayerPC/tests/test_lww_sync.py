# -*- coding: utf-8 -*-
"""跨端「单值偏好 / 每书元数据」LWW 同步的回归测试（PC 端，可离线跑）。

背景：现有 9 类跨端同步全是**集合型**（add-no-delete 并集 / 按项对账）。
新增的 user_settings、book_meta 是**单值覆盖型** —— 若套用并集语义，
用户在 PC 把倍速从 1.0 改成 1.5 会被旧值永远压住，表现为「改了设置同步不过去」
且不报任何错。本测试锁住以下不变式：

  1. 影子快照判脏：本地没改 ⇒ 原样回传服务端版本（服务端一次库写入都不做）；
     本地改过 ⇒ 用严格更大的版本覆盖。
  2. 首次同步的默认值必须用**中性版本 0**，否则新装设备会用 1.0 冲掉另一端的 1.75。
  3. 应用远端后影子同步前移 ⇒ 下一轮判为干净，绝不产生「拉取→写本地→又上报」循环。
  4. book_meta 只 UPDATE sort_order / last_read_at 两列，⛔ 不碰 category
     （PC 该列是文件类型/书城固定值，Android 是用户手工分类，同步会互相污染），
     ⛔ 不 INSERT 书籍行。
  5. 失败不写「假已同步」水位；新增两类的失败必须进入 ``SyncResult.errors``
     并带中文功能名（自动同步失败不能静默）。

运行：``python tests/test_lww_sync.py``
"""
from __future__ import annotations

import copy
import os
import sys
import tempfile
import unittest

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

# ⚠ 必须在 import app.core.config 之前设置：config 模块在 import 时就固定了 _CONFIG_DIR，
#   否则 config.save() 会写到用户真实的 AppData 目录。
_CFG_DIR = tempfile.mkdtemp(prefix="abpc_lww_")
os.environ["AUDIOBOOK_PC_CONFIG"] = _CFG_DIR
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from app.core.config import AppConfig  # noqa: E402
from app.core import coverage  # noqa: E402
from app.core.sync import (  # noqa: E402
    SyncManager, SyncResult, _canon_speed, _iso_to_millis, _millis_to_iso,
    _json_state, _write_state, _SS_SHADOW, _SS_VERSIONS,
    _BM_SHADOW, _BM_SERVER, _BM_VERSIONS,
)
from app.db.database import LocalDatabase  # noqa: E402
from app.models.models import Book  # noqa: E402


class _FakeApi:
    """内存版「服务端」：按与 UserSettingsService/BookMetaService 相同的 LWW 规则裁决。"""

    CLOCK = 1_000  # 固定的服务端 now（远小于真实 epoch，便于断言版本来源）

    def __init__(self):
        self.settings_state = {}       # key -> (value, version)
        self.book_meta_state = {}      # bookKey -> (sort_order, last_read_ms, version)
        self.settings_calls = []
        self.book_meta_calls = []
        self.settings_resp_override = None
        self.book_meta_resp_override = None
        self.fail = False

    def _check(self):
        if self.fail:
            raise RuntimeError("模拟网络失败")

    def sync_user_settings(self, items):
        self._check()
        self.settings_calls.append(copy.deepcopy(items))
        if self.settings_resp_override is not None:
            return self.settings_resp_override
        for it in items:
            key = it["key"]
            old = self.settings_state.get(key)
            if old is None:
                ver = max(int(it.get("updatedAt") or 0), self.CLOCK)
                self.settings_state[key] = (str(it.get("value") or ""), ver)
            elif int(it.get("updatedAt") or 0) > old[1]:
                ver = max(int(it["updatedAt"]), old[1] + 1)
                self.settings_state[key] = (str(it.get("value") or ""), ver)
        return {
            "success": True,
            "items": [{"key": k, "value": v, "updatedAt": ver}
                      for k, (v, ver) in self.settings_state.items()],
            "version": max([0] + [v for _, v in self.settings_state.values()]),
        }

    def sync_book_meta(self, items):
        self._check()
        self.book_meta_calls.append(copy.deepcopy(items))
        if self.book_meta_resp_override is not None:
            return self.book_meta_resp_override
        for it in items:
            bk = it["bookKey"]
            old = self.book_meta_state.get(bk)
            incoming = (int(it.get("sortOrder") or 0), int(it.get("lastReadAt") or 0))
            ver = int(it.get("updatedAt") or 0)
            if old is None:
                self.book_meta_state[bk] = (incoming[0], incoming[1], max(ver, self.CLOCK))
            elif ver > old[2]:
                self.book_meta_state[bk] = (incoming[0], incoming[1], max(ver, old[2] + 1))
        return {
            "success": True,
            "items": [{"bookKey": bk, "sortOrder": s, "lastReadAt": ms, "updatedAt": ver}
                      for bk, (s, ms, ver) in self.book_meta_state.items()],
            "total": len(self.book_meta_state),
        }


class _Base(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.config = AppConfig(data_dir=self._tmp.name)
        self.config.token = "dummy-token"
        self.db = LocalDatabase(self.config)
        self.addCleanup(self.db.close)
        self.api = _FakeApi()
        self.mgr = SyncManager(self.config, self.api, self.db)

    def _add_book(self, name="MyBook.txt", category="用户分类",
                  sort_order=1, last_read_at="2026-01-01T10:00:00",
                  store_book_id=None) -> int:
        bid = self.db.add_book(Book(
            display_name=name, file_path=f"/tmp/{name}", file_ext="txt",
            category=category, sort_order=sort_order, last_read_at=last_read_at,
            store_book_id=store_book_id))
        # ⚠ LocalDatabase.add_book 的 INSERT 列清单**不含** last_read_at，
        #   该列只能靠 update_book_last_read 或显式 UPDATE 写入。测试要构造真实场景，
        #   这里补一次，否则所有书都读成空串、影子快照永远对不上。
        c = self.db._conn()
        c.execute("UPDATE books SET last_read_at=? WHERE id=?", (last_read_at or "", bid))
        c.commit()
        return bid

    def _item(self, items, key, field="key"):
        for it in items:
            if it.get(field) == key:
                return it
        return None


class TestHelpers(_Base):
    def test_canon_speed_matches_server_java_double_tostring(self):
        # 服务端 UserSettingsService.sanitize 输出 String.valueOf(double)
        self.assertEqual("1.0", _canon_speed(1.0))
        self.assertEqual("1.0", _canon_speed("1"))
        self.assertEqual("1.0", _canon_speed("1.00"))
        self.assertEqual("1.5", _canon_speed(1.5))
        self.assertEqual("0.75", _canon_speed(0.75))
        self.assertEqual("1.25", _canon_speed(" 1.25 "))
        # 值域夹取，与 PlaybackConfig/服务端 MIN 0.5 MAX 3.0 一致
        self.assertEqual("0.5", _canon_speed(0.1))
        self.assertEqual("3.0", _canon_speed(9.0))
        self.assertEqual("1.0", _canon_speed("not-a-number"))

    def test_iso_millis_roundtrip_keeps_local_format(self):
        iso = "2026-01-01T10:00:00"
        ms = _iso_to_millis(iso)
        self.assertGreater(ms, 0)
        self.assertEqual(iso, _millis_to_iso(ms))
        self.assertEqual(0, _iso_to_millis(""))
        self.assertEqual("", _millis_to_iso(0))
        # PC 本地仍是 TEXT ISO 串（books 表 ORDER BY 依赖它），不是整数
        self.assertIsInstance(_millis_to_iso(ms), str)

    def test_json_state_corruption_falls_back(self):
        self.db.set_sync_state("bad", "{not json")
        self.assertEqual({}, _json_state(self.db, "bad", {}))
        self.assertEqual({}, _json_state(self.db, "missing", {}))
        _write_state(self.db, "good", {"a": 1})
        self.assertEqual({"a": 1}, _json_state(self.db, "good", {}))


class TestUserSettingsSync(_Base):
    def test_first_sync_default_uses_neutral_version(self):
        """⭐ 新设备首次上线不能用默认倍速冲掉另一端已设的值。"""
        self.api.settings_state["playback_speed"] = ("1.75", 9000)
        self.mgr._sync_user_settings(SyncResult())
        sent = self.api.settings_calls[0]
        self.assertEqual(0, self._item(sent, "playback_speed")["updatedAt"])
        self.assertEqual(0, self._item(sent, "default_voice_id")["updatedAt"])
        # 服务端权威值 1.75 被应用，默认值 1.0 没有覆盖它
        self.assertEqual(1.75, self.config.playback_speed)
        self.assertEqual("1.75", self.api.settings_state["playback_speed"][0])

    def test_first_sync_nondefault_pushes_local_value(self):
        """老用户首次上云且本地已有非默认设置 ⇒ 不能丢用户设置。"""
        self.config.playback_speed = 2.0
        self.mgr._sync_user_settings(SyncResult())
        sent = self.api.settings_calls[0]
        it = self._item(sent, "playback_speed")
        self.assertEqual("2.0", it["value"])
        self.assertGreater(it["updatedAt"], 0)

    def test_clean_item_returns_server_version_and_does_not_write(self):
        self.api.settings_state["playback_speed"] = ("1.5", 5000)
        self.api.settings_state["default_voice_id"] = ("Junhao", 6000)
        self.config.playback_speed = 1.5
        self.config.default_voice_id = "Junhao"
        _write_state(self.db, _SS_SHADOW, {"playback_speed": "1.5",
                                           "default_voice_id": "Junhao"})
        _write_state(self.db, _SS_VERSIONS, {"playback_speed": 5000,
                                             "default_voice_id": 6000})
        res = SyncResult()
        self.mgr._sync_user_settings(res)
        sent = self.api.settings_calls[0]
        self.assertEqual(5000, self._item(sent, "playback_speed")["updatedAt"])
        self.assertEqual(6000, self._item(sent, "default_voice_id")["updatedAt"])
        self.assertEqual(0, res.settings_pushed)
        # 服务端版本未被抬高
        self.assertEqual(5000, self.api.settings_state["playback_speed"][1])

    def test_local_change_pushes_once_and_no_loop(self):
        """改设置 ⇒ 推一次；应用响应后影子前移 ⇒ 第二轮判为干净，不再推。"""
        self.mgr._sync_user_settings(SyncResult())     # 首次建立影子/水位
        self.config.playback_speed = 1.5
        r2 = SyncResult()
        self.mgr._sync_user_settings(r2)
        self.assertEqual(1, r2.settings_pushed)
        sent = self.api.settings_calls[1]
        it = self._item(sent, "playback_speed")
        self.assertEqual("1.5", it["value"])
        self.assertGreater(it["updatedAt"], _FakeApi.CLOCK)
        self.assertEqual("1.5", self.api.settings_state["playback_speed"][0])

        version_after_push = self.api.settings_state["playback_speed"][1]
        r3 = SyncResult()
        self.mgr._sync_user_settings(r3)
        self.assertEqual(0, r3.settings_pushed, "应用远端后不得产生二次上报")
        self.assertEqual(version_after_push,
                         self.api.settings_state["playback_speed"][1],
                         "干净项回传原版本，服务端版本号不再抬升")

    def test_server_newer_value_wins_and_is_applied(self):
        """对端改过（服务端版本更大）⇒ 本地被静默覆盖，不产生回推。"""
        self.mgr._sync_user_settings(SyncResult())
        self.api.settings_state["playback_speed"] = ("2.0", 10**15)
        self.config.playback_speed = 1.25
        r = SyncResult()
        self.mgr._sync_user_settings(r)
        self.assertEqual(2.0, self.config.playback_speed)
        # 下一轮必须判为干净
        r2 = SyncResult()
        self.mgr._sync_user_settings(r2)
        self.assertEqual(0, r2.settings_pushed)

    def test_failure_does_not_write_fake_watermark(self):
        self.api.fail = True
        with self.assertRaises(RuntimeError):
            self.mgr._sync_user_settings(SyncResult())
        self.assertEqual({}, _json_state(self.db, _SS_SHADOW, {}))
        self.assertEqual({}, _json_state(self.db, _SS_VERSIONS, {}))

    def test_bad_response_raises_instead_of_silently_corrupting_shadow(self):
        self.api.settings_resp_override = {"success": True}  # 缺 items
        with self.assertRaises(ValueError):
            self.mgr._sync_user_settings(SyncResult())
        self.assertEqual({}, _json_state(self.db, _SS_SHADOW, {}))


class TestBookMetaSync(_Base):
    def test_only_two_columns_updated_category_untouched(self):
        """⭐ category 绝不能被同步覆盖（两端语义不同）。"""
        bid = self._add_book(name="MyBook.txt", category="用户分类", sort_order=1)
        bk = coverage.book_key(0, None, "MyBook.txt")
        ms = 1767000000000
        self.api.book_meta_resp_override = {
            "success": True,
            "items": [{"bookKey": bk, "sortOrder": 9, "lastReadAt": ms,
                       "updatedAt": 10 ** 15}],
            "total": 1,
        }
        r = SyncResult()
        self.mgr._sync_book_meta(r)

        book = self.db.get_book(bid)
        self.assertEqual("用户分类", book.category, "category 被跨端同步污染了")
        self.assertEqual(9, book.sort_order)
        self.assertEqual(_millis_to_iso(ms), book.last_read_at)
        self.assertEqual(1, r.book_meta_pulled)

    def test_server_only_book_does_not_create_local_row(self):
        self._add_book(name="LocalOnly.txt")
        before = len(self.db.get_books())
        self.api.book_meta_resp_override = {
            "success": True,
            "items": [{"bookKey": "n:only-on-phone", "sortOrder": 3,
                       "lastReadAt": 123, "updatedAt": 9999}],
            "total": 1,
        }
        self.mgr._sync_book_meta(SyncResult())
        self.assertEqual(before, len(self.db.get_books()), "⛔ 元数据不得 INSERT 书籍行")

    def test_clean_item_returns_server_authoritative_millis(self):
        """PC 本地 ISO 只精确到秒；干净项必须回传服务端原始毫秒，不能抹掉尾数。"""
        bid = self._add_book(name="Clean.txt", sort_order=4,
                             last_read_at="2026-01-01T10:00:00")
        bk = coverage.book_key(0, None, "Clean.txt")
        local_ms = _iso_to_millis("2026-01-01T10:00:00")
        server_ms = local_ms + 789  # 服务端带毫秒尾
        _write_state(self.db, _BM_SHADOW, {bk: [4, "2026-01-01T10:00:00"]})
        _write_state(self.db, _BM_SERVER, {bk: {"sortOrder": 4, "lastReadAt": server_ms}})
        _write_state(self.db, _BM_VERSIONS, {bk: 7000})

        self.mgr._sync_book_meta(SyncResult())
        sent = self.api.book_meta_calls[0]
        it = self._item(sent, bk, "bookKey")
        self.assertEqual(server_ms, it["lastReadAt"], "服务端毫秒尾数被本地秒级串抹掉了")
        self.assertEqual(7000, it["updatedAt"])
        self.assertEqual(4, self.db.get_book(bid).sort_order)

    def test_local_sort_change_pushes_once_then_clean(self):
        self._add_book(name="SortMe.txt", sort_order=1)
        self.mgr._sync_book_meta(SyncResult())
        self.db.update_book_sort(1, 42)
        r2 = SyncResult()
        self.mgr._sync_book_meta(r2)
        self.assertEqual(1, r2.book_meta_pushed)
        r3 = SyncResult()
        self.mgr._sync_book_meta(r3)
        self.assertEqual(0, r3.book_meta_pushed, "应用后影子未前移 ⇒ 回写循环")

    def test_first_sync_all_default_uses_neutral_version(self):
        self._add_book(name="Fresh.txt", sort_order=0, last_read_at="")
        self.mgr._sync_book_meta(SyncResult())
        sent = self.api.book_meta_calls[0]
        self.assertEqual(0, sent[0]["updatedAt"])

    def test_failure_does_not_write_book_meta_watermark(self):
        self._add_book(name="Fail.txt")
        self.api.fail = True
        with self.assertRaises(RuntimeError):
            self.mgr._sync_book_meta(SyncResult())
        self.assertEqual({}, _json_state(self.db, _BM_SHADOW, {}))
        self.assertEqual({}, _json_state(self.db, _BM_VERSIONS, {}))


class TestSyncAllVisibility(_Base):
    def test_new_categories_fail_visibly_with_chinese_labels(self):
        """自动同步失败不能静默：两项必须进 errors 且带中文功能名。"""
        class _ExplodingApi:
            def __getattr__(self, _name):
                def _boom(*_a, **_kw):
                    raise RuntimeError("模拟断网")
                return _boom

        mgr = SyncManager(self.config, _ExplodingApi(), self.db)
        res = mgr.sync_all(include_progress=False)
        joined = " | ".join(res.errors)
        self.assertIn("settings:", joined)
        self.assertIn("book_meta:", joined)
        self.assertIn("偏好设置", joined)
        self.assertIn("书架排序", joined)
        self.assertEqual("unknown", res.error_kinds.get("settings"))
        self.assertEqual("unknown", res.error_kinds.get("book_meta"))

    def test_as_text_includes_new_categories(self):
        r = SyncResult(settings_pulled=1, settings_pushed=1,
                       book_meta_pulled=2, book_meta_pushed=3)
        text = r.as_text()
        self.assertIn("偏好 ↓1/↑1", text)
        self.assertIn("书序 ↓2/↑3", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
