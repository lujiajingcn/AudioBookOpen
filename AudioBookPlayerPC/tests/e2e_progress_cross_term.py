"""跨端阅读位置同步的端到端验证（连**真实服务端**）。

**验证目标**：模拟「A 端读到某处 → B 端打开同一本书 → 跳到同一位置」。

三端的身份口径必须一致：同一本书在 PC / Android / Web 算出的 bookKey 相同，
服务端按 (user_id, book_key) 存 ⇒ 换端能读到。

本脚本覆盖：
1. PC 端算出的 bookKey 与服务端归一化口径一致（``s:{storeBookId}``）。
2. PC 上报 → 服务端落库 → 另一端（用 bookKey 读）能取到同一位置。
3. **无音频的书也能记位置**（修复前服务端直接 400）——
   这正是「纯阅读的书换端打开只能从头开始」的根因。
4. 归一化：``n:`` 分支下大小写/扩展名/空白差异归到同一行。

运行：``python tests/e2e_progress_cross_term.py``
需要：服务端可达（本机默认 http://api.example.com/）+ 本机 PC 端 auth.json 里的有效 token。
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

BASE_URL = os.environ.get("AUDIOBOOK_BASE_URL", "http://api.example.com")
# 测试用的书城书 id（45 = 《无穷的开始》，未合成 ⇒ 无音频，是「纯阅读」场景）
BOOK_ID = 45
BOOK_KEY = f"s:{BOOK_ID}"


def _load_token() -> str:
    local = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local"))
    auth = local / "AudioBookPlayerPC" / "data" / "auth.json"
    return json.loads(auth.read_text(encoding="utf-8"))["token"]


TOKEN = _load_token()


def _api(method: str, path: str, body: dict | None = None) -> dict:
    """发一个 JSON 请求。

    用 ``requests``（与 PC 端 ApiClient 同一套）而不是 urllib：
    urllib 在带 body 时会**追加**一个 ``Content-Type: application/x-www-form-urlencoded``，
    与我们再设的 json 头重复 ⇒ 服务端按错误的媒体类型解析，返回 415/405。
    requests 的 ``json=`` 会正确设置 ``Content-Type: application/json``。
    """
    import requests
    url = f"{BASE_URL}{path}"
    headers = {"Authorization": f"Bearer {TOKEN}"}
    try:
        if method == "GET":
            resp = requests.get(url, headers=headers, timeout=30)
        elif method == "PUT":
            resp = requests.put(url, headers=headers, json=body or {}, timeout=30)
        else:
            resp = requests.post(url, headers=headers, json=body or {}, timeout=30)
        try:
            return resp.json()
        except Exception:  # noqa: BLE001
            return {"__http_status": resp.status_code, "body": resp.text[:300]}
    except Exception as e:  # noqa: BLE001
        return {"__error": f"{type(e).__name__}: {e}"}


def main() -> int:
    print(f"服务端: {BASE_URL}")
    print(f"测试书: 书城 id={BOOK_ID}（无音频 ⇒ 纯阅读场景），bookKey={BOOK_KEY}\n")

    # ---- 1. PC 端算出的 bookKey 与服务端口径一致 ----
    from app.core import coverage  # noqa: E402
    pc_key = coverage.book_key(BOOK_ID, 0, "无穷的开始英文原版the-beginning-of-infinity")
    ok1 = pc_key == BOOK_KEY
    print(f"[1] PC 端 book_key: {pc_key!r}  期望 {BOOK_KEY!r}  → {'✅' if ok1 else '❌'}")
    if not ok1:
        return 1

    # ---- 2. 无音频的书：能存进度（修复前是 400）----
    r = _api("PUT", "/api/book-progress", {"bookKey": BOOK_KEY, "segment": 4242, "offsetMs": 0})
    ok2 = r.get("success") is True
    print(f"[2] 无音频的书保存进度: {r}  → {'✅' if ok2 else '❌'}")
    if not ok2:
        return 1

    # ---- 3. 另一端按同一 bookKey 读回，应拿到同一位置 ----
    r = _api("GET", "/api/book-progress?" + urllib.parse.urlencode({"bookKey": BOOK_KEY}))
    ok3 = r.get("hasProgress") is True and r.get("segment") == 4242
    print(f"[3] 另一端读回（同一 bookKey）: {r}  → {'✅' if ok3 else '❌'}")

    # ---- 4. 老端点（书级）也能读到同一行 —— 兼容老客户端 ----
    r = _api("GET", f"/api/store/book/{BOOK_ID}/progress")
    ok4 = r.get("hasProgress") is True and r.get("segment") == 4242
    print(f"[4] 老书级端点读回: {r}  → {'✅' if ok4 else '❌'}")

    # ---- 5. 归一化：n: 分支下扩展名差异归到同一行 ----
    #    「我的书.PDF」与「我的书」应是同一本书 ⇒ 同一行进度
    r1 = _api("PUT", "/api/book-progress", {"bookKey": "n:我的书", "segment": 7, "offsetMs": 0})
    r2 = _api("PUT", "/api/book-progress", {"bookKey": "n:我的书.PDF", "segment": 9, "offsetMs": 0})
    r3 = _api("GET", "/api/book-progress?" + urllib.parse.urlencode({"bookKey": "n:我的书"}))
    ok5 = (r1.get("success") and r2.get("success")
            and r3.get("hasProgress") is True and r3.get("segment") == 9)
    print(f"[5] 归一化（我的书 / 我的书.PDF 同行）: 写入 {r1.get('success')}/{r2.get('success')}，"
          f"读回 {r3.get('segment')}  → {'✅' if ok5 else '❌'}")

    # ---- 清理测试数据 ----
    print("\n清理测试数据…")
    # 服务端没有删进度端点，这里提示手动清理（脚本不碰生产数据以外的东西）
    print(f"  提示: 可手动清理 user_book_progress 里 book_key='{BOOK_KEY}' 与 'n:我的书' 的行")

    all_ok = ok1 and ok2 and ok3 and ok4 and ok5
    print("\n结论: " + ("✅ 跨端进度同步全部通过" if all_ok else "❌ 有用例未通过"))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
