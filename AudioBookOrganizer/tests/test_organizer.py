"""端到端与单元测试。

跑法：  python -m unittest discover -s tests -v
不依赖 pytest，也不联网。
"""

from __future__ import annotations

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from audiobook_organizer import classify, codecut, prose, tabulate  # noqa: E402
from audiobook_organizer.htmltree import Node, parse, serialize  # noqa: E402
from audiobook_organizer.pipeline import Config, Organizer  # noqa: E402


def node_of(xhtml: str) -> Node:
    doc = parse(xhtml)
    body = doc.find("body")
    assert body is not None
    return body


# ----------------------------------------------------------------------
class TestHtmlTree(unittest.TestCase):
    def test_roundtrip_escapes_code(self):
        src = "<body><pre><code>if [ $a &lt; 3 ]; then</code></pre></body>"
        body = node_of(src)
        self.assertIn("<code>if [ $a &lt; 3 ]; then</code>", serialize(body))

    def test_void_tags_self_close(self):
        body = node_of("<body><p>a<br/>b</p></body>")
        self.assertIn("<br />", serialize(body))

    def test_text_of_nested(self):
        body = node_of("<body><p>甲<strong>乙</strong>丙</p></body>")
        self.assertEqual(body.find("p").text(), "甲乙丙")


class TestClassify(unittest.TestCase):
    def test_pre_is_code_block(self):
        body = node_of('<body><pre class="代码无行号"><code>x</code></pre></body>')
        self.assertEqual(classify.classify(body.children[0]).kind, classify.PRE)

    def test_table_caption_before_para(self):
        body = node_of('<body><p class="表题">表4-1　通配符</p></body>')
        self.assertEqual(classify.classify(body.children[0]).kind, classify.TABLE_CAPTION)

    def test_toc_container_not_flattened(self):
        body = node_of('<body><div class="sgc-toc-level-1"><a href="a.html">第一章</a>'
                       '<div class="sgc-toc-level-2"><a href="b.html">第一节</a></div></div></body>')
        blocks = classify.flatten_blocks(body)
        self.assertEqual(len(blocks), 1, "目录条目不该被展开成一堆裸段落")
        self.assertEqual(classify.classify(blocks[0]).kind, classify.TOC_ENTRY)

    def test_plain_container_is_flattened(self):
        body = node_of("<body><div><p>一</p><p>二</p></div></body>")
        self.assertEqual(len(classify.flatten_blocks(body)), 2)

    def test_heading_junk(self):
        self.assertTrue(classify.heading_junk("第 12 页"))
        self.assertTrue(classify.heading_junk("————"))
        self.assertFalse(classify.heading_junk("4.1　通配符"))


class TestCodecut(unittest.TestCase):
    def _info(self, code_html: str):
        body = node_of(f"<body><pre><code>{code_html}</code></pre></body>")
        return codecut.analyze(body.children[0])

    def test_session_extracts_commands(self):
        info = self._info(
            "[me@linuxbox ~]$ <strong>type type</strong>\n\ntype is a shell builtin\n"
            "[me@linuxbox ~]$ <strong>type ls</strong>\n\nls is aliased\n"
        )
        self.assertEqual(info.kind, "session")
        self.assertEqual(info.commands, ["type"])

    def test_script_detected_by_shebang(self):
        info = self._info("#!/bin/bash\ncount=1\nwhile [ $count -le 5 ]; do\n echo $count\ndone")
        self.assertEqual(info.kind, "script")
        self.assertIn("while", info.structure)

    def test_syntax_with_placeholder(self):
        info = self._info("type <em>command</em>")
        self.assertEqual(info.kind, "syntax")

    def test_ascii_art(self):
        info = self._info("+------+------+\n| a    | b    |\n+------+------+")
        self.assertEqual(info.kind, "ascii")

    def test_summary_mentions_real_command(self):
        info = self._info("[me@linuxbox ~]$ <strong>cat -A file</strong>\n\nline$")
        text = codecut.summarize(info, lead="接下来", variant=0)
        self.assertIn("cat", text)
        self.assertIn("输出文件内容", text)

    def test_unknown_command_not_called_command(self):
        info = self._info("[me@linuxbox ~]$ <strong>while-count</strong>\n\n1\n2\n3")
        text = codecut.summarize(info, variant=0)
        self.assertIn("执行了 while-count", text)
        self.assertNotIn("命令", text)

    def test_follow_drops_lead(self):
        info = self._info("[me@linuxbox ~]$ <strong>ls</strong>\n\na b c")
        text = codecut.summarize(info, lead="接下来", follow=True, variant=0)
        self.assertFalse(text.startswith("接下来"), text)


class TestTabulate(unittest.TestCase):
    def _table(self, html: str, caption: str = ""):
        body = node_of(f"<body><table>{html}</table></body>")
        return tabulate.analyze(body.children[0], caption)

    def test_two_column_glossary_is_spoken(self):
        t = self._table(
            "<thead><tr><th>快捷方式</th><th>结果</th></tr></thead>"
            "<tbody><tr><td>cd</td><td>将工作目录改变成主目录</td></tr>"
            "<tr><td>cd-</td><td>将工作目录改变成先前的工作目录</td></tr></tbody>"
        )
        self.assertEqual(t.kind, "glossary")
        text = tabulate.summarize(t)
        self.assertIn("将工作目录改变成主目录", text)

    def test_matrix_is_skipped(self):
        t = self._table(
            "<thead><tr><th>a</th><th>b</th><th>c</th><th>d</th></tr></thead>"
            "<tbody><tr><td>1</td><td>2</td><td>3</td><td>4</td></tr></tbody>"
        )
        self.assertEqual(t.kind, "matrix")
        self.assertIn("跳过", tabulate.summarize(t))

    def test_caption_normalized(self):
        t = self._table(
            "<thead><tr><th>x</th><th>y</th></tr></thead>"
            "<tbody><tr><td>a</td><td>这是一句像样的说明文字</td></tr></tbody>",
            caption="表4-2　常用的字符类**",
        )
        text = tabulate.summarize(t)
        self.assertIn("表4-2《常用的字符类》", text)
        self.assertNotIn("**", text)


class TestProse(unittest.TestCase):
    def test_drop_page_number(self):
        self.assertEqual(prose.clean_prose("第 12 页"), "")
        self.assertEqual(prose.clean_prose("| 第 4 章　操作文件与目录"), "")

    def test_strip_url_and_footnote(self):
        s = prose.clean_prose("详见 http://example.com/a 说明[12]。")
        self.assertNotIn("http", s)
        self.assertNotIn("[12]", s)

    def test_step_number_detection(self):
        self.assertEqual(prose.step_number("1．取案板。"), (1, "取案板。"))
        self.assertEqual(prose.step_number("②切菜"), (2, "切菜"))

    def test_cn_number(self):
        self.assertEqual(prose.cn_number(1), "一")
        self.assertEqual(prose.cn_number(10), "十")
        self.assertEqual(prose.cn_number(12), "十二")

    def test_chapter_outro_avoids_duplicate_numbering(self):
        self.assertIn("接下来是第30章", prose.chapter_outro("第30章 故障诊断"))
        self.assertIn("下一章我们来看", prose.chapter_outro("附录"))


# ----------------------------------------------------------------------
def _build_mini_epub(path: Path) -> None:
    """造一本 3 页的迷你书：版权页 + 目录页 + 一个带代码和表格的正文章。"""
    opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>测试书</dc:title>
    <dc:creator>某人</dc:creator>
    <dc:language>zh</dc:language>
    <dc:identifier id="bookid">urn:uuid:test</dc:identifier>
  </metadata>
  <manifest>
    <item id="p1" href="p1.html" media-type="application/xhtml+xml"/>
    <item id="p2" href="p2.html" media-type="application/xhtml+xml"/>
    <item id="p3" href="p3.html" media-type="application/xhtml+xml"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="p1"/><itemref idref="p2"/><itemref idref="p3"/>
  </spine>
</package>"""
    ncx = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="urn:uuid:test"/></head>
  <docTitle><text>测试书</text></docTitle>
  <navMap>
    <navPoint id="n1" playOrder="1"><navLabel><text>版权信息</text></navLabel><content src="p1.html"/></navPoint>
    <navPoint id="n2" playOrder="2"><navLabel><text>目录</text></navLabel><content src="p2.html"/></navPoint>
    <navPoint id="n3" playOrder="3"><navLabel><text>第1章</text></navLabel><content src="p3.html"/></navPoint>
  </navMap>
</ncx>"""
    pages = {
        "p1.html": '<html><body><h1>版权信息</h1><p>ISBN：978-7-115-30745-3</p></body></html>',
        "p2.html": '<html><body><div class="sgc-toc-title">目录</div>'
                   '<div class="sgc-toc-level-1"><a href="p1.html">版权信息</a></div>'
                   '<div class="sgc-toc-level-1"><a href="p3.html">第1章</a></div></body></html>',
        "p3.html": (
            '<html><body>'
            '<h1>第1章　开始</h1>'
            '<p>本章讲两个东西。表1-1列出了它们。</p>'
            '<p class="表题">表1-1　两个东西</p>'
            '<table><thead><tr><th>名字</th><th>用途</th></tr></thead>'
            '<tbody><tr><td>甲</td><td>用来做第一件事</td></tr>'
            '<tr><td>乙</td><td>用来做第二件事</td></tr></tbody></table>'
            '<p>实例如下所示。</p>'
            '<pre><code>[me@host ~]$ <strong>ls -l</strong>\n\ntotal 0\n-rw-r--r-- 1 a b 0 x</code></pre>'
            '<p>1．第一步动作。</p><p>2．第二步动作。</p><p>3．第三步动作。</p>'
            '</body></html>'
        ),
    }

    with zipfile.ZipFile(path, "w") as z:
        info = zipfile.ZipInfo("mimetype")
        info.compress_type = zipfile.ZIP_STORED
        z.writestr(info, "application/epub+zip")
        z.writestr("META-INF/container.xml",
                   '<?xml version="1.0"?><container version="1.0" '
                   'xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>'
                   '<rootfile full-path="OEBPS/content.opf" '
                   'media-type="application/oebps-package+xml"/></rootfiles></container>')
        z.writestr("OEBPS/content.opf", opf)
        z.writestr("OEBPS/toc.ncx", ncx)
        for name, html in pages.items():
            z.writestr(f"OEBPS/{name}", html)


class TestPipeline(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        root = Path(cls.tmp.name)
        cls.src = root / "测试书.epub"
        _build_mini_epub(cls.src)
        cls.result = Organizer(Config()).run(cls.src, root / "out")
        cls.txt = cls.result.out_txt.read_text(encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_output_named_as_requested(self):
        self.assertEqual(self.result.out_epub.name, "测试书_精简有声化.epub")
        self.assertEqual(self.result.out_txt.name, "测试书_精简有声化.txt")

    def test_toc_page_dropped_and_spine_pruned(self):
        with zipfile.ZipFile(self.result.out_epub) as z:
            opf = z.read("OEBPS/content.opf").decode()
            ncx = z.read("OEBPS/toc.ncx").decode()
            names = set(z.namelist())
        self.assertNotIn("p2.html", names, "纯目录页应从包里消失")
        self.assertNotIn('idref="p2"', opf)
        self.assertNotIn("p2.html", ncx, "NCX 里的悬空 navPoint 要被剪掉")

    def test_copy_right_section_dropped(self):
        self.assertNotIn("ISBN", self.txt)

    def test_code_replaced_by_sentence(self):
        self.assertNotIn("total 0", self.txt, "命令输出不该进有声稿")
        self.assertIn("ls", self.txt)

    def test_table_spoken(self):
        self.assertIn("表1-1《两个东西》", self.txt)
        self.assertIn("用来做第一件事", self.txt)

    def test_steps_rewritten(self):
        self.assertIn("第一步，第一步动作", self.txt)
        self.assertIn("第三步", self.txt)

    def test_front_matter_note_present(self):
        self.assertIn("有声化精简版", self.txt)

    def test_epub_is_wellformed_xml(self):
        import xml.etree.ElementTree as ET
        with zipfile.ZipFile(self.result.out_epub) as z:
            for n in z.namelist():
                if n.endswith((".html", ".xhtml")):
                    xml = z.read(n).decode("utf-8")
                    xml = xml.split("?>", 1)[-1] if xml.lstrip().startswith("<?xml") else xml
                    xml = xml.replace('<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" '
                                      '"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">', "")
                    ET.fromstring(xml)  # 不抛异常即合法

    def test_mimetype_first_and_stored(self):
        with zipfile.ZipFile(self.result.out_epub) as z:
            first = z.infolist()[0]
            self.assertEqual(first.filename, "mimetype")
            self.assertEqual(first.compress_type, zipfile.ZIP_STORED)
            self.assertEqual(z.read("mimetype"), b"application/epub+zip")


class TestStripMode(unittest.TestCase):
    def test_strip_removes_without_adding(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            src = root / "t.epub"
            _build_mini_epub(src)
            res = Organizer(Config(mode="strip", formats=("txt",))).run(src, root / "o")
            txt = res.out_txt.read_text(encoding="utf-8")
            self.assertNotIn("表1-1《两个东西》", txt)
            self.assertNotIn("total 0", txt)


if __name__ == "__main__":
    unittest.main(verbosity=2)
