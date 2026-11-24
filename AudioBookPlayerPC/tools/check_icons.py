#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""图标体检：生成各尺寸对比图（亮/暗底）+ 校验 app.ico 可被 Qt 读取。"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ICON_DIR = ROOT / "assets" / "icons"
OUT = Path(r"<agent-outputs>")
OUT.mkdir(parents=True, exist_ok=True)

from PySide6.QtCore import Qt, QSize
from PySide6.QtGui import QIcon, QImage, QPainter, QColor
from PySide6.QtWidgets import QApplication


def main() -> int:
    app = QApplication(sys.argv)

    sizes = [16, 24, 32, 48, 64, 128]
    scale = 4          # 小图放大 4 倍（最近邻），便于肉眼判断
    pad = 16
    label_h = 22

    # 两行：亮底 / 暗底
    row_h = max(sizes) * scale + label_h + pad
    W = pad + sum(s * scale + pad for s in sizes)
    H = pad + row_h * 2

    sheet = QImage(W, H, QImage.Format.Format_ARGB32)
    sheet.fill(QColor("#ffffff"))
    p = QPainter(sheet)
    p.setRenderHint(QPainter.Antialiasing, False)

    for row, bg in enumerate(("#ffffff", "#1a1a2e")):
        y0 = pad + row * row_h
        p.fillRect(0, y0 - pad, W, row_h + pad, QColor(bg))
        x = pad
        for s in sizes:
            img = QImage(str(ICON_DIR / f"app_{s}.png")).convertToFormat(
                QImage.Format.Format_ARGB32)
            # 最近邻放大，暴露真实像素
            big = img.scaled(QSize(s * scale, s * scale),
                             Qt.IgnoreAspectRatio, Qt.FastTransformation)
            p.drawImage(x, y0, big)
            p.setPen(QColor("#888888" if row == 0 else "#aaaaaa"))
            p.drawText(x, y0 + s * scale + 15, f"{s}px")
            x += s * scale + pad
    p.end()
    out = OUT / "icon_contact_sheet.png"
    sheet.save(str(out), "PNG")
    print("contact sheet:", out)

    # ---- ICO 校验 ----
    ico = QIcon(str(ICON_DIR / "app.ico"))
    print("ico isNull:", ico.isNull())
    print("ico availableSizes:", sorted((s.width(), s.height())
                                        for s in ico.availableSizes()))
    for s in (16, 32, 48, 256):
        pm = ico.pixmap(QSize(s, s))
        print(f"  pixmap {s}x{s} -> {pm.width()}x{pm.height()} null={pm.isNull()}")

    # ico 能否正确解码（不看 null 而看内容）
    img = QImage(str(ICON_DIR / "app.ico"))
    print("QImage(ico):", img.width(), "x", img.height(), "null:", img.isNull())

    # ---- 多尺寸 QIcon 组合（应用里真正用的）----
    combo = QIcon()
    for s in (16, 24, 32, 48, 64, 128, 256):
        combo.addFile(str(ICON_DIR / f"app_{s}.png"), QSize(s, s))
    print("combo availableSizes:", sorted((s.width(), s.height())
                                         for s in combo.availableSizes()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
