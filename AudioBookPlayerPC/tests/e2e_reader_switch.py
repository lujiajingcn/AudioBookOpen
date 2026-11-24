"""端到端复现脚本（诊断用，非单元测试）。

**复现用户报告的场景**：
1. 打开《Linux命令行大全-一句话解释》（书城 id=49，有快照）→ 正常显示；
2. 返回；
3. 打开《无穷的开始英文原版the-beginning-of-infinity》（书城 id=45，PDF，无快照）→
   修复前：先显示第 1 本书的内容，约 10s 后才跳到第 2 本；
   修复后：立刻清空并显示「正在加载」，内容随后正确到达。

跑法::

    "C:/Program Files/Python312/python.exe" tests/e2e_reader_switch.py

需要一个可访问的服务端（默认 http://api.example.com/）与有效 token。
token 取自本机 PC 端配置 ``%LOCALAPPDATA%/AudioBookPlayerPC/data/auth.json``。
"""
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from PySide6.QtWidgets import QApplication  # noqa: E402

from app.core.api_client import ApiClient  # noqa: E402
from app.core.config import AppConfig  # noqa: E402
from app.db.database import LocalDatabase  # noqa: E402
from app.ui.pages.reader_page import ReaderPage  # noqa: E402

BOOK_A = 49   # Linux命令行大全_一句话解释（TXT，有快照）
BOOK_B = 45   # 无穷的开始（PDF，无快照）

_APP = QApplication.instance() or QApplication([])


def _load_token() -> str:
    auth_file = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) \
        / "AudioBookPlayerPC" / "data" / "auth.json"
    return json.loads(auth_file.read_text(encoding="utf-8"))["token"]


def main() -> int:
    cfg = AppConfig.load()
    cfg.token = _load_token()
    api = ApiClient(cfg)
    db = LocalDatabase(cfg)
    reader = ReaderPage(cfg, api, None, db)

    # ---- 1. 打开 A，等它加载完 ----
    reader.open_book(store_book_id=BOOK_A)
    t0 = time.time()
    while reader._load_seq and not reader.lines and time.time() - t0 < 60:
        _APP.processEvents()
        time.sleep(0.05)
    print(f"[1] 打开 A(id={BOOK_A})：{len(reader.lines)} 行，"
          f"标题={reader._title.text()!r}，耗时 {time.time() - t0:.2f}s")
    assert reader.lines, "A 应有内容"
    a_first_line = reader.lines[0].text[:30]

    # ---- 2. 离开阅读器（模拟点左侧导航栏换页 → hideEvent 收尾；
    #         注意：阅读器页是单实例复用，内容此时仍留在视图里） ----
    reader._stop_session()
    print("[2] 离开阅读器（页面复用，内容仍在视图里）")

    # ---- 3. 打开 B —— 关键步骤 ----
    t0 = time.time()
    reader.open_book(store_book_id=BOOK_B)
    _APP.processEvents()
    view_lines = reader.line_view._lines
    leftover = [ln.text for ln in view_lines if a_first_line[:12] in ln.text]
    print(f"[3] 打开 B(id={BOOK_B}) 后立刻检查：")
    print(f"    视图中 A 的残留行数 = {len(leftover)}（应为 0）")
    print(f"    视图当前内容 = {[ln.text[:24] for ln in view_lines]}")
    print(f"    状态栏 = {reader.status.text()!r}")

    # ---- 4. 等 B 加载完 ----
    t0 = time.time()
    while not reader.lines and time.time() - t0 < 90:
        _APP.processEvents()
        time.sleep(0.05)
    print(f"[4] B 加载完成：{len(reader.lines)} 行，"
          f"标题={reader._title.text()!r}，耗时 {time.time() - t0:.2f}s")
    print(f"    B 首行 = {reader.lines[0].text[:40]!r}")

    ok = (not leftover) and reader.lines and "无穷" in reader._title.text()
    print("\n结论：" + ("✅ 通过（无残留内容，且正确切到 B）" if ok else "❌ 失败"))
    db.close()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
