"""Qt 测试清理 helper：显式销毁 QWidget，杜绝顶层 widget 泄漏。

⚠️ **为什么必须有这个**（2026-11-07 实测踩坑）：
    QWidget 若只靠 Python GC 回收，回收时机不可控 ⇒ 顶层 widget 会堆积在
    ``QApplication`` 里 ⇒ 之后任何「遍历并 polish 全部顶层 widget」的操作
    （典型是 ``Theme.apply`` 里的 ``app.setStyleSheet()``）都可能踩到野指针
    ⇒ **access violation 段错误**。表现为「跑全量测试随机崩，单独跑某个
    文件又没事」—— 崩溃点还会漂移，极难定位。

    修法有两条，**都要做**：
      1. 产品侧：``BasePage.run_async`` 的 bridge 必须 ``parent=self``，
         页面析构时线程回调不再打到野指针（见 base_page.py）。
      2. 测试侧：建了 widget 就必须显式销毁（本模块）。

⚠️ **PySide6 6.9 没有 ``PySide6.sip``**，只有独立的 ``shiboken6`` 包。
   写 ``from PySide6 import sip`` 会 ImportError —— 若被 ``except`` 吞掉，
   清理就是**假的**（页面照样泄漏，且没有任何报错）。
"""
from __future__ import annotations

try:
    import shiboken6
except ImportError:  # pragma: no cover - 极老版本 PySide6
    shiboken6 = None


def destroy_widget(w) -> None:
    """``close()`` + 立即析构 C++ 对象。

    ``close()`` 负责停掉定时器/隐藏窗口，``shiboken6.delete()`` 负责让
    C++ 对象立刻消失（``deleteLater()`` 在测试里不可靠 —— 没有事件循环
    处理 deferred delete，对象会一直挂在 ``QApplication.topLevelWidgets()``）。
    """
    if w is None:
        return
    try:
        w.close()
    except Exception:  # noqa: BLE001
        pass
    if shiboken6 is None:
        return
    try:
        shiboken6.delete(w)
    except Exception:  # noqa: BLE001
        pass


def register_destroy(testcase, *widgets) -> None:
    """把 widget 销毁注册成测试 cleanup（tearDown 时执行，LIFO）。"""
    for w in widgets:
        testcase.addCleanup(destroy_widget, w)
