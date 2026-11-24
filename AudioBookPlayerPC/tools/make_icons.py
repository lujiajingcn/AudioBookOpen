#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""由 Android 端应用图标素材生成桌面端多尺寸 PNG 与 Windows ICO。

用法：
    python tools/make_icons.py [--android-res <目录>]

素材来源（唯一真源 = Android 模块，不在桌面端重复存二进制）：
    ../AudioBookPlayer/app/src/main/res/mipmap-xxxhdpi/
        ic_launcher_background.png   自适应图标背景层（108dp 画布，暖奶油竖向渐变）
        ic_launcher_foreground.png   自适应图标前景层（画面收在中央 62%）

合成规则与 Android 一致：
  1) 取自适应图标前景层中央的「画面卡片」——Android 的 ic_launcher_foreground.png
     把插图收在 108dp 画布的中央 62%（432px 画布里是 268x268 的白底卡片），
     这块卡片就是传统图标 ic_launcher.png 的完整画面（与 mipmap-xxxhdpi/ic_launcher.png
     逐像素比对平均色差 4.5 ⇒ 同源），且分辨率 268 > 传统图标的 192；
  2) 套圆角遮罩（现代 Android 启动器与 Windows 11 任务栏均为圆角方形）。

  为什么不取「中央 72dp 可见区」：那样会在画面外留一圈暖奶油渐变边框，
  大尺寸下看起来像贴纸割裂；卡片本身已是满幅画面，更干净。

产出：
    assets/icons/app_<size>.png   （16/24/32/48/64/128/256）
    assets/icons/app.png           （= 256，供文档/网页使用）
    assets/icons/app.ico           （多尺寸，Windows 任务栏/资源管理器/打包 exe 用）

不依赖 Pillow：ICO 手工封装（≤64 用 DIB，256 用 PNG，Vista+ 规范）。
"""
from __future__ import annotations

import os
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "assets" / "icons"

# Windows 任务栏/资源管理器常用尺寸
SIZES = (16, 24, 32, 48, 64, 128, 256)

# 圆角遮罩半径（相对边长）。现代 Android 启动器与 Windows 11 任务栏都是圆角方形。
MASK_RATIO = 0.18

# 小尺寸（≤32px）用「主体放大」裁切：满幅画面在 16px 下耳机只剩几个像素，
# 裁掉四周留白后轮廓明显更清晰（主体位置不变，仍是同一幅画）。
# 值为 (x, y, w, h)，相对 268px 母版的比例。
SMALL_ZOOM = (0.119, 0.097, 0.761, 0.761)
SMALL_SIZES = (16, 24, 32)


# ---------------------------------------------------------------- 素材定位

def android_res_dir() -> Path:
    """定位 Android 端图标素材目录（优先级：环境变量 → 兄弟模块 → 本地缓存）。"""
    env = os.environ.get("AUDIOBOOK_ANDROID_RES")
    if env and os.path.isdir(env):
        return Path(env)

    # 兄弟模块：AudioBook/AudioBook/AudioBookPlayer/app/src/main/res/mipmap-xxxhdpi
    sibling = (ROOT.parent / "AudioBookPlayer" / "app" / "src" / "main"
               / "res" / "mipmap-xxxhdpi")
    if sibling.is_dir():
        return sibling

    # 本地缓存（可选，便于脱离 Android 模块单独生成）
    cache = ROOT / "assets" / "icons" / "source"
    if cache.is_dir():
        return cache

    raise SystemExit(
        "找不到 Android 图标素材。请用 --android-res 指定 "
        "AudioBookPlayer/app/src/main/res/mipmap-xxxhdpi 目录，"
        "或设置环境变量 AUDIOBOOK_ANDROID_RES。")


# ---------------------------------------------------------------- 合成母版

def build_master(res: Path):
    """按 Android 自适应图标的规则取出画面母版，返回 (图像, 说明)。"""
    from PySide6.QtCore import Qt
    from PySide6.QtGui import QImage, QPainter

    bg_path = res / "ic_launcher_background.png"
    fg_path = res / "ic_launcher_foreground.png"
    for p in (bg_path, fg_path):
        if not p.is_file():
            raise SystemExit(f"缺少素材：{p}")

    fg = QImage(str(fg_path)).convertToFormat(QImage.Format_ARGB32)

    # 找出前景层里不透明的画面卡片区域（Alpha>8 的包围盒）。
    # 前景层是 108dp 画布，插图收在中央 62% ⇒ 卡片约占画布 62%。
    w, h = fg.width(), fg.height()
    min_x, min_y, max_x, max_y = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            if fg.pixelColor(x, y).alpha() > 8:
                if x < min_x:
                    min_x = x
                if x > max_x:
                    max_x = x
                if y < min_y:
                    min_y = y
                if y > max_y:
                    max_y = y
    if max_x < 0:
        raise SystemExit(f"前景层没有不透明画面：{fg_path}")

    card = fg.copy(min_x, min_y, max_x - min_x + 1, max_y - min_y + 1)
    return card


def apply_round_mask(img, radius_ratio: float = MASK_RATIO):
    """给方形图套圆角遮罩（Android 启动器 / Windows 11 任务栏的圆角方形观感）。"""
    from PySide6.QtCore import Qt
    from PySide6.QtGui import QImage, QPainter, QPainterPath

    n = img.width()
    out = QImage(n, n, QImage.Format_ARGB32)
    out.fill(Qt.transparent)
    path = QPainterPath()
    path.addRoundedRect(0, 0, n, n, n * radius_ratio, n * radius_ratio)
    p = QPainter(out)
    p.setRenderHint(QPainter.Antialiasing, True)
    p.setClipPath(path)
    p.drawImage(0, 0, img)
    p.end()
    return out


# ---------------------------------------------------------------- ICO 封装

def _png_bytes(img) -> bytes:
    from PySide6.QtCore import QBuffer, QByteArray, QIODevice

    # ⚠ QByteArray 必须由 Python 变量持有：写成 QBuffer(QByteArray()) 时临时对象
    #   会被 GC 回收，data() 返回悬垂指针 → 退出期段错误（exit 139）。
    data = QByteArray()
    buf = QBuffer(data)
    buf.open(QIODevice.WriteOnly)
    img.save(buf, "PNG")
    buf.close()
    return bytes(data)


def _bmp_entry(img) -> bytes:
    """把 QImage 编成 ICO 里的 DIB 条目（BGRA 底部朝上 + AND 掩码）。

    32bpp 的透明度由 alpha 通道表达，AND 掩码全 0 即可。
    """
    from PySide6.QtGui import QImage

    argb = img.convertToFormat(QImage.Format.Format_ARGB32)
    w, h = argb.width(), argb.height()
    bpl = argb.bytesPerLine()
    raw = bytes(argb.constBits())

    rows = [raw[y * bpl:(y + 1) * bpl] for y in range(h)]
    xor = b"".join(reversed(rows))  # BMP 自下而上

    mask_stride = ((w + 31) // 32) * 4
    mask = b"\x00" * (mask_stride * h)

    header = struct.pack(
        "<IiiHHIIiiII",
        40,            # biSize
        w,             # biWidth
        h * 2,         # biHeight（XOR + AND 两倍）
        1,             # biPlanes
        32,            # biBitCount
        0,             # biCompression = BI_RGB
        len(xor) + len(mask),  # biSizeImage
        0, 0,          # 像素/米
        0, 0,          # biClrUsed / biClrImportant
    )
    return header + xor + mask


def _write_ico(images: dict[int, object], path: Path) -> None:
    """封装多尺寸 ICO：≤64 用 DIB 条目，256 用 PNG 条目（Vista+ 规范）。"""
    entries = []   # (w, h, payload)
    for size in sorted(images):
        img = images[size]
        if size >= 256:
            payload = _png_bytes(img)
        else:
            payload = _bmp_entry(img)
        entries.append((size if size < 256 else 0, size if size < 256 else 0, payload))

    count = len(entries)
    dir_size = 6 + 16 * count
    offset = dir_size
    header = struct.pack("<HHH", 0, 1, count)
    dir_bytes = b""
    for w, h, payload in entries:
        dir_bytes += struct.pack(
            "<BBBBHHII", w, h, 0, 0, 1, 32, len(payload), offset
        )
        offset += len(payload)

    with open(path, "wb") as f:
        f.write(header + dir_bytes + b"".join(p for _, _, p in entries))


# ---------------------------------------------------------------- 主流程

def main() -> int:
    # ⚠ QPainter 在没有应用实例时会崩溃（exit 127），必须先建 QGuiApplication。
    from PySide6.QtCore import Qt, QSize
    from PySide6.QtGui import QImage
    from PySide6.QtWidgets import QApplication

    app = QApplication(sys.argv)

    res = android_res_dir()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    master = build_master(res)
    print(f"素材目录：{res}")
    print(f"母版：{master.width()}x{master.height()}（前景层中央画面卡片）")

    # 小尺寸用主体放大裁切
    mx, my, mw, mh = SMALL_ZOOM
    small_src = master.copy(int(master.width() * mx), int(master.height() * my),
                            int(master.width() * mw), int(master.height() * mh))

    images = {}
    for size in SIZES:
        src = small_src if size in SMALL_SIZES else master
        masked = apply_round_mask(src)
        img = masked.scaled(QSize(size, size), Qt.IgnoreAspectRatio,
                            Qt.SmoothTransformation)
        out = OUT_DIR / f"app_{size}.png"
        img.save(str(out), "PNG")
        images[size] = img
        tag = " (主体放大)" if size in SMALL_SIZES else ""
        print(f"  {out.relative_to(ROOT)}  ({size}x{size}){tag}")

    # 256 版另存一份无后缀尺寸名的 app.png
    images[256].save(str(OUT_DIR / "app.png"), "PNG")
    print(f"  {(OUT_DIR / 'app.png').relative_to(ROOT)}")

    ico_path = OUT_DIR / "app.ico"
    _write_ico(images, ico_path)
    print(f"  {ico_path.relative_to(ROOT)}  ({len(SIZES)} sizes, "
          f"{ico_path.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
