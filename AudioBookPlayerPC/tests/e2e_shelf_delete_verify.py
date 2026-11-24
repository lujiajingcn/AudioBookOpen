"""端到端验收：真实服务端 + 真实同步 + 真实用户数据。

场景完全按用户操作序列走：
  1. 复制真实数据目录（不污染真库）
  2. 起真实 MainWindow（进入书架会触发自动同步）
  3. 点「删除」→ 确认
  4. 再触发一次同步
  5. 断言：书**没有**回到书架上

同时验证：服务端确实收到 DELETE（其它设备也不再显示）。
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

REAL = os.path.expanduser('~/AppData/Local/AudioBookPlayerPC/data')
SID = 45


def main() -> int:
    from PySide6.QtWidgets import QApplication, QMessageBox
    from app.core.config import AppConfig
    from app.db.database import LocalDatabase
    from app.core.api_client import ApiClient

    app = QApplication(sys.argv)

    tmp = tempfile.mkdtemp()
    shutil.copytree(REAL, os.path.join(tmp, "data"), dirs_exist_ok=True)
    for ext in ("-wal", "-shm"):
        p = os.path.join(tmp, "data", "audiobook_pc.db" + ext)
        if os.path.exists(p):
            os.remove(p)

    cfg = AppConfig(data_dir=os.path.join(tmp, "data"))
    auth = json.load(open(os.path.join(tmp, "data", "auth.json"), encoding="utf-8"))
    cfg.token = auth.get("token", "")
    db = LocalDatabase(cfg)
    api = ApiClient(cfg)

    # 服务端确保有这本书
    if not any(int(i.get('storeBookId') or 0) == SID for i in api.get_shelf()):
        api.add_to_shelf(SID)
        print("已确保服务端有", SID, flush=True)

    from app.main_window import MainWindow
    win = MainWindow(cfg)
    win.show()
    for _ in range(20):
        app.processEvents()

    page = win.shelf_page
    print("初始 DB:", [(b.id, b.display_name[:24], b.store_book_id) for b in db.get_books()], flush=True)

    target = next((r for r in page._rows.values()
                   if getattr(r.book, "store_book_id", 0) == SID), None)
    if target is None:
        print("❌ 本机没有这本书", flush=True)
        return 1

    QMessageBox.question = staticmethod(
        lambda p, t, x, *a, **kw: (print("   弹框:", t, flush=True), QMessageBox.Yes)[1])

    print("--- 点删除 ---", flush=True)
    target.btn_del.click()
    for _ in range(30):
        app.processEvents()
    time.sleep(1.2)
    for _ in range(20):
        app.processEvents()

    print("删除后 DB:", [(b.id, b.display_name[:24], b.store_book_id) for b in db.get_books()], flush=True)
    srv = [(i.get('storeBookId'), i.get('title', '')[:20]) for i in api.get_shelf()]
    print("服务端书架:", srv, flush=True)

    print("--- 再同步一次 ---", flush=True)
    win.sync.sync_all()
    for _ in range(20):
        app.processEvents()
    page.refresh()
    for _ in range(10):
        app.processEvents()

    final = [(b.id, b.display_name[:24], b.store_book_id) for b in db.get_books()]
    print("同步后 DB:", final, flush=True)
    print("同步后页面行数:", len(page._rows), flush=True)

    local_gone = not [b for b in db.get_books() if b.store_book_id == SID]
    server_gone = not any(int(i.get('storeBookId') or 0) == SID for i in api.get_shelf())

    print()
    print("本机已删除:", "✅" if local_gone else "❌")
    print("服务端已移出:", "✅" if server_gone else "❌")

    ok = local_gone and server_gone
    print("\n=== 结论:", "✅ 删除生效且不会被同步拉回" if ok else "❌ 仍有问题", "===")

    # 还原服务端
    if not server_gone:
        try:
            api.add_to_shelf(SID)
            print("已还原服务端书架", flush=True)
        except Exception as e:
            print("还原失败:", e, flush=True)

    db.close()
    shutil.rmtree(tmp, ignore_errors=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
