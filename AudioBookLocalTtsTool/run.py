"""AudioBookLocalTtsTool 启动入口。

用法：

    python run.py

（若要打包成 exe：pyinstaller --noconsole --onedir --name AudioBookLocalTtsTool run.py）
"""

from __future__ import annotations

import sys
from pathlib import Path

# 允许直接 `python run.py` 运行（把工程根目录加进 sys.path）
sys.path.insert(0, str(Path(__file__).resolve().parent))

from PySide6.QtWidgets import QApplication  # noqa: E402

from app.ui.main_window import MainWindow  # noqa: E402


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("AudioBookLocalTtsTool")
    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
