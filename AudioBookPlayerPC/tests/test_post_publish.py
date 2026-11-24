"""PC 端发帖链路回归测试。

**背景（真实线上事故）**：
PC 端 ``CreatePostDialog`` 允许给帖子附图。旧实现把图片用
``multipart/form-data`` **直接 POST 到 ``/api/posts``**，但服务端该端点只接受
JSON（``@RequestBody CreatePostRequest``）⇒ Spring 抛
``HttpMediaTypeNotSupportedException`` ⇒ 被全局兜底 handler 吞成
**500「服务器开小差了，请稍后重试」**。

这个 500 极具迷惑性：文案完全看不出是「Content-Type 不对」，
排查时容易误以为是服务端挂了或图片本身有问题。

正确形态是**两段式**（与 Android 端 ``CreatePostBottomSheet`` 完全一致）：
1. ``POST /api/posts/upload-image``（multipart，字段名 ``file``）→ 拿相对地址；
2. ``POST /api/posts``（JSON）→ 带 ``imageUrls: [相对地址]``。

本测试用假 session 钉死这几条契约：
- T1 有图时**必须**先调 upload-image，再调 JSON 的 /posts（顺序也不能反）；
- T2 upload-image 的 multipart 字段名必须是 ``file``（服务端 @RequestParam("file")）；
- T3 无图时**不得**发起 upload-image，直接 JSON 提交；
- T4 传给 /posts 的 JSON 必须是 imageUrls **数组**（服务端 DTO 是 List<String>）；
- T5 服务端返回的 imageUrls 是数组，客户端要解析成列表（旧代码读单串 imageUrl）；
- T6 列表分页参数名是 ``limit`` 而不是 ``size``。

运行：``python tests/test_post_publish.py``
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app.core.api_client import ApiClient, ApiException, parse_image_urls  # noqa: E402
from app.core.config import AppConfig  # noqa: E402


class _FakeResponse:
    """最小可用的 requests.Response 替身。"""

    def __init__(self, status_code: int = 200, payload=None):
        self.status_code = status_code
        self._payload = payload if payload is not None else {}
        self.text = json.dumps(self._payload, ensure_ascii=False)

    def json(self):
        return self._payload


class _FakeSession:
    """记录所有请求，按调用顺序回放预设响应。"""

    def __init__(self, responses=None):
        self.calls = []          # [(method, url, kwargs), ...]
        self._responses = list(responses or [])

    def _record(self, method, url, kwargs):
        self.calls.append((method, url, kwargs))
        if self._responses:
            return self._responses.pop(0)
        return _FakeResponse(200, {"success": True})

    def post(self, url, **kwargs):
        return self._record("POST", url, kwargs)

    def get(self, url, **kwargs):
        return self._record("GET", url, kwargs)

    # ---- 断言辅助 ----

    def urls(self):
        return [c[1] for c in self.calls]

    def posts_to(self, path_suffix: str):
        return [c for c in self.calls if c[0] == "POST" and c[1].endswith(path_suffix)]


class PostPublishTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.png_path = os.path.join(self._tmp.name, "shot.png")
        with open(self.png_path, "wb") as f:
            f.write(b"\x89PNG\r\n\x1a\n" + b"\x00" * 16)

        self.config = AppConfig(base_url="http://test.local", data_dir=self._tmp.name)
        self.client = ApiClient(self.config)

    def _install(self, responses=None):
        session = _FakeSession(responses)
        self.client._session = session
        return session

    # ---------- T1：两段式顺序 ----------

    def test_publish_with_image_uploads_first_then_posts_json(self):
        """有图时：先 upload-image（multipart），再 POST /posts（JSON）。"""
        session = self._install([
            _FakeResponse(200, {"success": True, "url": "/api/posts/image/abc.png"}),
            _FakeResponse(200, {"success": True, "message": "发布成功"}),
        ])

        self.client.create_post("标题", "正文", self.png_path)

        urls = session.urls()
        self.assertEqual(2, len(urls), f"应当恰好两次请求，实际：{urls}")
        self.assertTrue(urls[0].endswith("/api/posts/upload-image"),
                        f"第一次必须是 upload-image，实际：{urls[0]}")
        self.assertTrue(urls[1].endswith("/api/posts"),
                        f"第二次必须是 /posts，实际：{urls[1]}")

        # 第一次是 multipart，第二次是 JSON
        first = session.calls[0][2]
        self.assertIn("files", first, "upload-image 必须是 multipart（带 files）")
        second = session.calls[1][2]
        self.assertIn("json", second, "/posts 必须是 JSON（带 json）")
        self.assertNotIn("files", second, "/posts 不能是 multipart —— 服务端只收 JSON")

    # ---------- T2：multipart 字段名 ----------

    def test_upload_image_uses_file_field_name(self):
        """服务端是 @RequestParam("file")，字段名必须是 file。"""
        session = self._install([
            _FakeResponse(200, {"success": True, "url": "/api/posts/image/abc.png"}),
            _FakeResponse(200, {"success": True}),
        ])

        self.client.create_post("标题", "正文", self.png_path)

        files = session.calls[0][2]["files"]
        self.assertIn("file", files,
                      f"multipart 字段名必须是 'file'（服务端 @RequestParam(\"file\")），实际：{list(files)}")

    # ---------- T3：无图不走 upload ----------

    def test_publish_without_image_skips_upload(self):
        """无图时不得发起 upload-image。"""
        session = self._install([_FakeResponse(200, {"success": True})])

        self.client.create_post("标题", "正文")

        self.assertEqual(1, len(session.calls))
        self.assertTrue(session.urls()[0].endswith("/api/posts"))
        body = session.calls[0][2]["json"]
        self.assertNotIn("imageUrls", body, "无图时不该带 imageUrls 字段")

    # ---------- T4：imageUrls 是数组 ----------

    def test_post_body_carries_image_urls_as_array(self):
        """服务端 DTO 是 List<String>，必须传数组而不是字符串。"""
        session = self._install([
            _FakeResponse(200, {"success": True, "url": "/api/posts/image/abc.png"}),
            _FakeResponse(200, {"success": True}),
        ])

        self.client.create_post("标题", "正文", self.png_path)

        body = session.calls[1][2]["json"]
        self.assertIn("imageUrls", body)
        self.assertIsInstance(body["imageUrls"], list,
                              f"imageUrls 必须是数组，实际类型：{type(body['imageUrls'])}")
        self.assertEqual(["/api/posts/image/abc.png"], body["imageUrls"])

    # ---------- T5：解析服务端返回的图片数组 ----------

    def test_parse_image_urls_handles_array_string_and_none(self):
        self.assertEqual(["/api/posts/image/a.png"],
                         parse_image_urls(["/api/posts/image/a.png"]))
        # 旧服务端/旧缓存可能给的是单串，也要能吃下
        self.assertEqual(["/api/posts/image/a.png"],
                         parse_image_urls("/api/posts/image/a.png"))
        self.assertEqual([], parse_image_urls(None))
        self.assertEqual([], parse_image_urls([]))
        self.assertEqual([], parse_image_urls(123))
        # 非字符串项要滤掉，不能让 QLabel.setPixmap 收到脏数据
        self.assertEqual(["/a.png"], parse_image_urls(["/a.png", None, 7, ""]))

    # ---------- T6：分页参数名 ----------

    def test_get_posts_uses_limit_param(self):
        """服务端是 @RequestParam("limit")，写成 size 会被忽略。"""
        session = self._install([_FakeResponse(200, {"success": True, "posts": []})])

        self.client.get_posts(page=0, size=20)

        params = session.calls[0][2].get("params") or {}
        self.assertIn("limit", params,
                      f"分页参数名必须是 'limit'（服务端 PostController.listPosts），实际：{params}")
        self.assertEqual(20, params["limit"])
        self.assertNotIn("size", params, "size 会被服务端忽略，多传反而混淆")

    # ---------- 附加：上传失败要有明确报错 ----------

    def test_upload_without_url_raises_clear_error(self):
        """服务端没返回 url 时，要给出可读错误而不是静默发出坏请求。"""
        self._install([
            _FakeResponse(200, {"success": True}),   # 缺 url
            _FakeResponse(200, {"success": True}),
        ])

        with self.assertRaises(ApiException) as ctx:
            self.client.create_post("标题", "正文", self.png_path)
        self.assertIn("图片上传失败", ctx.exception.message)


if __name__ == "__main__":
    unittest.main(verbosity=2)
