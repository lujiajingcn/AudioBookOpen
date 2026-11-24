"""本机合成的「暂停 / 恢复」闸门。

## 为什么暂停只能发生在段边界

一次推理就是 ONNX Runtime 的一次 ``session.run()``，跑在 native 里且不可中断
（这也是中止功能同样只能在段边界生效的原因）。所以：

- 点「暂停」后，**当前正在跑的那一段会先跑完**，之后的段才不再开始；
- 按钮文案要如实写「等当前段结束」，不能让人以为点了就立刻停。

## 为什么用 Condition 而不是一个布尔标志 + 忙等

最直觉的写法是 ``while paused: time.sleep(0.1)`` 空转等 —— **这是错的**：
本机合成的 worker 数等于引擎数，空转会把 CPU 占满，而暂停的意义正是
让机器停下来（省电、让用户先干别的）。这里用 Condition 让 worker 真正挂起，
恢复时被唤醒，期间不消耗 CPU。

## 与「中止」的关系

暂停与中止必须能同时表达：用户在暂停状态下点中止，worker 必须能退出，
而不是永远卡在 ``await_resume`` 里。因此 :meth:`await_resume` 的等待条件是
「已恢复 **或** 已中止」，两者任一成立就放行，由调用方再判断该怎么走。

⚠️ **但「只置中止标志」不足以放行 worker**：闸门用的是 ``Condition.wait()``，
它**只会在 ``notify_all()`` 时返回**，不会因为「某个别的变量（中止标志）变了」
就醒。所以「置中止标志」与「放行闸门」必须**成对出现**，缺一不可：

    stop_flag.set()            # 让 worker 在下一个检查点退出
    release_synthesis_pause()  # 放行正卡在 await_resume 上的 worker

漏掉后者的表现是「暂停中点停止 ⇒ 合成既不结束也不释放内存」——
进程还活着、界面一直挂着，比崩溃更难排查。

## 与安卓端的关系

本文件是 ``AudioBookPlayer`` 的 ``moss/PauseController.kt`` 的等价实现，
语义刻意保持一致（同一份设计文档、同一组边界条件），避免两端行为漂移。
"""
from __future__ import annotations

import threading
from typing import Callable


class PauseController:
    """本机合成的暂停/恢复闸门。"""

    def __init__(self) -> None:
        self._cond = threading.Condition(threading.Lock())
        self._paused = False

    # ---------- 状态查询 ----------

    @property
    def is_paused(self) -> bool:
        """是否处于暂停状态（供 UI 判断显示「暂停」还是「继续」）。"""
        with self._cond:
            return self._paused

    # ---------- 操作 ----------

    def pause(self) -> None:
        """暂停。已经暂停时重复调用无副作用。"""
        with self._cond:
            self._paused = True

    def resume(self) -> None:
        """恢复。会唤醒所有正在等待的 worker。"""
        with self._cond:
            if not self._paused:
                return
            self._paused = False
            self._cond.notify_all()

    def release_all(self) -> None:
        """中止时调用：确保卡在暂停里的 worker 能被放出来，否则收尾会永远等下去。"""
        with self._cond:
            self._paused = False
            self._cond.notify_all()

    # ---------- 段边界闸门 ----------

    def await_resume(self, is_cancelled: Callable[[], bool]) -> bool:
        """在段边界调用。若处于暂停状态则挂起当前线程，直到恢复或中止。

        :param is_cancelled: 中止判定，用来避免「暂停中点中止」时永久挂起。
        :return: True 表示被放行且**未**中止（可以继续合成下一段）。
        """
        with self._cond:
            # 双重检查：可能在取锁期间已经被恢复/中止
            while self._paused and not is_cancelled():
                # wait 会在 notify 或**虚假唤醒**时返回，因此必须放在循环里重新判定。
                # 只判一次的实现会在虚假唤醒时提前放行。
                self._cond.wait()
            return not is_cancelled()
