# -*- coding: utf-8 -*-
"""应用图标资源回归测试。

覆盖：
  - assets/icons 下的多尺寸 PNG 与 app.ico 齐全；
  - app.ico 是合法的多尺寸 ICO（能被 Qt 识别出全部尺寸）；
  - 图标与 Android 端（AudioBookPlayer）母版同源——抽样像素比对；
  - icons.app_icon() 返回非空 QIcon 且包含全部尺寸；
  - 资源缺失时优雅降级（返回空图标而不是抛异常）。
"""
from __future__ import annotations

import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.ui.icons import (  # noqa: E402
    BRAND_PX, ICON_SIZES, app_icon, brand_icon, icons_dir,
)


def _android_res_dir():
    """定位 Android 端图标素材目录（与 tools/make_icons.py 同一套规则）。"""
    pc_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    env = os.environ.get("AUDIOBOOK_ANDROID_RES")
    cands = []
    if env:
        cands.append(env)
    cands.append(os.path.join(os.path.dirname(pc_root), "AudioBookPlayer", "app",
                              "src", "main", "res", "mipmap-xxxhdpi"))
    cands.append(os.path.join(pc_root, "assets", "icons", "source"))
    for c in cands:
        if c and os.path.isdir(c):
            return c
    return None


class IconAssetsTest(unittest.TestCase):
    def setUp(self):
        self.dir = icons_dir()
        self.assertIsNotNone(self.dir, "找不到 assets/icons 目录")

    def test_all_size_pngs_exist(self):
        for s in ICON_SIZES:
            p = os.path.join(self.dir, f"app_{s}.png")
            self.assertTrue(os.path.isfile(p), f"缺少 {p}")
            self.assertGreater(os.path.getsize(p), 0)

    def test_master_svg_exists(self):
        """图标母版是 Android 端的位图素材（桌面端不重复存二进制）。

        桌面端图标与移动端保持一致，母版真源在 AudioBookPlayer 模块里：
        mipmap-xxxhdpi/ic_launcher_foreground.png 的中央画面卡片。
        若 Android 模块不在（单独检出桌面端），允许缺失，但不能有旧的 SVG 母版残留。
        """
        for name in ("app.svg", "app_small.svg"):
            p = os.path.join(self.dir, name)
            self.assertFalse(os.path.isfile(p),
                             f"矢量母版 {name} 已废弃（改用 Android 位图素材）")

    def test_icon_visually_matches_android_master(self):
        """生成的图标应与 Android 端素材同源。

        做法：把 Android 前景层中央画面卡片按生成器同样的方式缩放到 48px，
        与已入库的 app_48.png 比对（只比中央区域，避开圆角遮罩裁掉的边角）。
        """
        from PySide6.QtCore import QSize, Qt
        from PySide6.QtGui import QImage

        res = _android_res_dir()
        if res is None:
            self.skipTest("找不到 Android 图标素材目录，跳过同源校验")

        fg = QImage(os.path.join(res, "ic_launcher_foreground.png")).convertToFormat(
            QImage.Format.Format_ARGB32)
        # 前景层中央画面卡片（Alpha>8 包围盒）
        w, h = fg.width(), fg.height()
        min_x, min_y, max_x, max_y = w, h, -1, -1
        for y in range(h):
            for x in range(w):
                if fg.pixelColor(x, y).alpha() > 8:
                    min_x, min_y = min(min_x, x), min(min_y, y)
                    max_x, max_y = max(max_x, x), max(max_y, y)
        self.assertGreater(max_x, 0, "Android 前景层没有不透明画面")
        card = fg.copy(min_x, min_y, max_x - min_x + 1, max_y - min_y + 1)

        expected = card.scaled(QSize(48, 48), Qt.IgnoreAspectRatio,
                               Qt.SmoothTransformation)
        actual = QImage(os.path.join(self.dir, "app_48.png")).convertToFormat(
            QImage.Format.Format_ARGB32)
        self.assertEqual(actual.width(), 48)

        # 只比中央 80%（避开圆角遮罩裁掉的四角），逐像素允许 ±12 的抗锯齿误差
        worst = 0
        for y in range(5, 43):
            for x in range(5, 43):
                a = expected.pixelColor(x, y)
                b = actual.pixelColor(x, y)
                d = (abs(a.red() - b.red()) + abs(a.green() - b.green())
                     + abs(a.blue() - b.blue()))
                worst = max(worst, d)
        self.assertLessEqual(worst, 36,
                             f"桌面端图标与 Android 母版不一致（最大色差 {worst}）")

    def test_app_png_exists(self):
        p = os.path.join(self.dir, "app.png")
        self.assertTrue(os.path.isfile(p))
        self.assertGreater(os.path.getsize(p), 0)

    def test_ico_header_valid(self):
        """ICO 结构：ICONDIR(6B) + 每项 16B + 图像数据。条目数应等于尺寸数。"""
        ico = os.path.join(self.dir, "app.ico")
        self.assertTrue(os.path.isfile(ico), "缺少 app.ico")
        with open(ico, "rb") as f:
            data = f.read()

        reserved, ico_type, count = struct.unpack("<HHH", data[:6])
        self.assertEqual(reserved, 0)
        self.assertEqual(ico_type, 1, "ICO type 必须是 1(图标)")
        self.assertEqual(count, len(ICON_SIZES))

        # 逐项检查偏移与长度都在文件范围内
        offset = 6 + 16 * count
        for i in range(count):
            entry = data[6 + 16 * i: 6 + 16 * (i + 1)]
            w, h, _, _, planes, bits, size, img_off = struct.unpack(
                "<BBBBHHII", entry)
            self.assertGreaterEqual(planes, 1)
            self.assertGreaterEqual(bits, 1)
            self.assertGreater(size, 0)
            self.assertLessEqual(offset + size, len(data),
                                 f"第 {i} 项图像数据越界")
            offset += size


class IconApiTest(unittest.TestCase):
    def setUp(self):
        # 需要 QGuiApplication 才能安全构造 QIcon
        from PySide6.QtGui import QGuiApplication
        self._app = QGuiApplication.instance() or QGuiApplication(sys.argv)

    def test_app_icon_not_null(self):
        icon = app_icon()
        self.assertFalse(icon.isNull(), "app_icon() 返回了空图标")
        sizes = {(s.width(), s.height()) for s in icon.availableSizes()}
        for s in ICON_SIZES:
            self.assertIn((s, s), sizes, f"图标缺少 {s}x{s} 尺寸")

    def test_brand_icon_not_null(self):
        icon = brand_icon()
        self.assertFalse(icon.isNull(), "brand_icon() 返回了空图标")
        pm = icon.pixmap(BRAND_PX, BRAND_PX)
        self.assertFalse(pm.isNull())
        self.assertEqual(pm.width(), BRAND_PX)

    def test_missing_resource_degrades_gracefully(self):
        """资源目录不存在时不应抛异常，只返回空图标。"""
        import app.ui.icons as icons_mod
        original = icons_mod.icons_dir
        try:
            icons_mod.icons_dir = lambda: None
            icons_mod.app_icon.cache_clear()
            icons_mod.brand_icon.cache_clear()
            self.assertTrue(icons_mod.app_icon().isNull())
            self.assertTrue(icons_mod.brand_icon().isNull())
        finally:
            icons_mod.icons_dir = original
            icons_mod.app_icon.cache_clear()
            icons_mod.brand_icon.cache_clear()


if __name__ == "__main__":
    unittest.main()
