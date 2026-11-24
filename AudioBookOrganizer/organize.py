#!/usr/bin/env python3
"""AudioBookOrganizer 入口。

用法：
    python organize.py "G:\\学习\\03-Linux与系统编程\\Linux命令行大全.epub"
    python organize.py 某目录/ -o out --mode audit
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from audiobook_organizer.cli import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
