"""离线英汉词典（ECDICT）—— 本地 SQLite 库直查。

词典本体是服务端预构建的 **SQLite 库** ``dict.db``（55.3MB / 768739 词条），
由 ``_dict_build/build_dict.py`` 从 ECDICT 的 ecdict.csv 转出，分发时 gzip 成 24MB。
客户端下载后解压即可用 Python 标准库 ``sqlite3`` 直接查，**不需要任何解析代码**。

## 表结构（建库脚本定的，客户端只能按这个查）
```
dict(sw TEXT, word TEXT, phonetic TEXT, translation TEXT,
     exchange TEXT, tag TEXT, collins INTEGER, oxford INTEGER,
     PRIMARY KEY(sw, word)) WITHOUT ROWID
```

## ⭐ 三条不能搞错的约定（与 Android DictionaryManager 完全一致）

**① 查询必须走 `sw`，不能只做 `word = ?`**
``sw`` = strip-word = 「去掉所有非字母数字字符 + 转小写」。用户长按选中的是书里的形态，
可能是 ``take-off``（连字符）、``don't``（撇号）、``Running``（大小写）。
直接用原词精确匹配会大量落空 —— 这是本功能最常见的「查不到」原因。
``_strip_word`` 必须与建库脚本的 ``strip_word()`` **逐字符一致**。

**② `translation` 里的换行是字面量反斜杠+n 两个字符，不是真换行**
实测全库 768739 行中含真换行（U+000A）的有 **0** 行，
含字面量 ``\\n``（两字符）的有 **52999** 行。
按真换行切分会得到「一整坨释义」，弹窗直接糊屏。

**③ `sw` 有 23482 个键对应多个 word**
去符号后撞车：``hood`` / ``Hood`` / ``'hood`` 都是 ``hood``。
因此一个 ``sw`` 可能返回多行，**都要显示**（``take-off`` 的 sw=``takeoff`` 命中 3 行）。
只取第一行会漏掉用户真正想看的那个义项。

## 为什么改用 SQLite（2026-11-06）
改之前 PC 端把下载件当 **CSV** 解析，而服务端下发的是 **gzip 的 SQLite 库** ⇒
``UnicodeDecodeError``、词典恒空、查词恒无结果。
SQLite 直查同时还解决了 CSV 方案的两个固有缺陷：
① 55MB 全量读进内存建索引（PC 端内存占用大、启动慢）；
② 查词只在前 3 个词形里找，无法命中第 4 个及以后的义项。

## 并发与线程
``sqlite3`` 连接**用完即关**而不是长期持有：
词典库与业务库（``audiobook_pc.db``）分属不同文件，不会互相锁；
而长期持有一个连接会让「卸载」时的文件删除在部分系统上失败（句柄未释放）。
查词本身走 PRIMARY KEY 索引，是磁盘随机读，耗时在毫秒级。
"""
from __future__ import annotations

import gzip
import hashlib
import os
import shutil
import sqlite3
import threading
import time
from pathlib import Path
from typing import Callable, Dict, List, Optional

import requests

from app.core.api_client import ApiClient, ApiException
from app.core.config import AppConfig

# ---------- 文件名约定（与 Android 端一致） ----------

#: 解压后的词典库文件名
DICT_FILE_NAME = "dict.db"

#: 下载中的临时文件（gzip 包）
PART_FILE_NAME = "dict.db.gz.part"

#: 安装信息文件名（记录已装版本 / MD5 / 词条数）
INFO_FILE_NAME = "dict.info"

#: 旧的 CSV 方案残留文件（2026-11-06 之前的实现）。已废弃，见 ``_cleanup_legacy_csv``。
LEGACY_CSV_NAME = "ecdict.csv"

#: 下载体积上限：服务端产物是 24MB，留 3 倍余量仍远超，超过即视为异常响应
MAX_DOWNLOAD_BYTES = 80 * 1024 * 1024


def _strip_word(text: str) -> str:
    """去非字母数字 + 转小写（对齐 Android stripWord / 建库脚本 strip_word）。

    ⚠️ 必须与 ``_dict_build/build_dict.py`` 的 ``strip_word()`` **逐字符一致**，
    否则查词永远不命中。这里用 Python 的 ``str.isalnum()``（Unicode 语义），
    与 Kotlin 的 ``Char.isLetterOrDigit()`` 等价。
    """
    return "".join(ch.lower() for ch in (text or "") if ch.isalnum())


def _edge_trim(word: str) -> str:
    """剥掉首尾非字母数字，中间符号（连字符/撇号）保留。"""
    return word.strip().strip("".join(c for c in word if not c.isalnum())) if word else ""


def _normalize_definition(raw: str) -> str:
    """把字面量 ``\\n``（两字符）转成真换行供界面显示。

    ⚠️ 库里的换行是**字面量**反斜杠+n（约定 ②），按真换行切会得到一整坨释义。
    """
    if not raw:
        return ""
    return raw.replace("\\n", "\n").replace("\r\n", "\n").strip()


def _parse_translations(raw: Optional[str]) -> List[str]:
    """切分 ECDICT 的 ``translation`` 字段（约定 ②）。

    ⚠️ 必须按**字面量** ``\\n``（反斜杠 + n 两个字符）切，不是真换行：
    实测 768739 行中含真换行的有 0 行。
    """
    if not raw:
        return []
    return [line.strip() for line in raw.split("\\n") if line.strip()]


class DictionaryManager:
    """离线词典：安装 / 查词。"""

    def __init__(self, config: AppConfig, api: ApiClient):
        self.config = config
        self.api = api
        self._dict_dir = self.config.resolved_data_dir / "dict"
        self._db_path = self._dict_dir / DICT_FILE_NAME
        self._part_path = self._dict_dir / PART_FILE_NAME
        self._info_path = self._dict_dir / INFO_FILE_NAME
        self._lock = threading.Lock()
        self._row_count = 0

    # ---------- 路径 / 就绪 ----------

    @property
    def db_path(self) -> Path:
        """词典库文件路径（解压后的 SQLite 库）。"""
        return self._db_path

    @property
    def csv_path(self) -> Path:
        """⚠️ 兼容旧接口名。旧版把词典落地成 CSV，现在落地的是 SQLite 库。

        保留这个属性是为了不破坏外部调用方，但它**返回的是 SQLite 库路径**，
        任何按 CSV 解析它的代码都会炸 —— 新代码请用 :attr:`db_path`。
        """
        return self._db_path

    def is_ready(self) -> bool:
        """词典可用：本地存在词典库文件。

        只做 stat，不建目录不写盘（对齐 Android 的 ``isInstalled()``）——
        这个方法会被阅读页在主线程频繁调用。
        """
        try:
            return self._db_path.is_file() and self._db_path.stat().st_size > 0
        except OSError:
            return False

    def row_count(self) -> int:
        """已安装的词条数。

        优先读安装信息里记录的 ``rows``（安装时算过一次）；
        没有记录才回落到 ``SELECT COUNT(*)`` —— 那是 768739 行的全表扫描，
        不能每次调用都跑一遍。
        """
        with self._lock:
            if self._row_count > 0:
                return self._row_count
        rows = self._read_info().get("rows")
        if rows:
            try:
                count = int(rows)
            except ValueError:
                count = 0
            if count > 0:
                with self._lock:
                    self._row_count = count
                return count
        if not self.is_ready():
            return 0
        try:
            with self._connect() as conn:
                count = int(conn.execute("SELECT COUNT(*) FROM dict").fetchone()[0])
        except (sqlite3.Error, OSError):
            return 0
        with self._lock:
            self._row_count = count
        return count

    # ---------- 连接 ----------

    def _connect(self) -> sqlite3.Connection:
        """打开词典库的**只读**连接。

        只读是刻意的：词典库是成品数据，任何写入（包括 SQLite 自动创建
        journal/WAL 的副作用）都没有意义，却会让「卸载时删文件」在部分系统上失败。

        用 URI 的 ``mode=ro`` 而不是 ``immutable=1``：后者要求文件**绝不**变化，
        而词典可被重装覆盖，immutable 下会读到旧内容。
        """
        conn = sqlite3.connect(f"file:{self._db_path}?mode=ro", uri=True, timeout=5.0)
        conn.row_factory = None
        return conn

    # ---------- 查词 ----------

    def lookup(self, word: str) -> Optional[Dict]:
        """查一个词，返回 {word, phonetic, definition}；未命中返回 None。

        匹配优先级：**完全同形**（大小写敏感）→ 忽略大小写同形 → strip-word 归一后的首条。
        一个 sw 可能对应多个 word（约定 ③），这里返回**最贴近查询词形**的那一条；
        想看全部义项请用 :meth:`lookup_all`。
        """
        entries = self.lookup_all(word)
        if not entries:
            return None
        cleaned = _edge_trim(word)
        # ⚠️ 必须**大小写敏感**地比「完全同形」：库里 `hello` 与 `Hello` 是两条独立词条，
        #   而 SQL 的 `ORDER BY word` 是 ASCII 序（大写在小写前）⇒ `Hello` 排在 `hello` 前面。
        #   若用 `.lower()` 比较，两者都算「同形」，`lookup("hello")` 会取到 `Hello` ——
        #   用户查小写却看到大写词条。
        exact = next((e for e in entries if e["word"] == cleaned), None)
        if exact:
            return dict(exact)
        # 再退一步：忽略大小写同形
        lowered = cleaned.lower()
        ci = next((e for e in entries if e["word"].lower() == lowered), None)
        return dict(ci if ci else entries[0])

    def lookup_all(self, word: str) -> List[Dict]:
        """返回 strip-word 归一后的**全部**命中（约定 ③：同键多词形都要显示）。

        每项形如 {word, phonetic, definition, translations, exchange, tag,
        collins, oxford}，其中 ``translations`` 是按字面量 ``\\n`` 切分后的义项列表。
        """
        if not word or not word.strip():
            return []
        if not self.is_ready():
            return []
        cleaned = _edge_trim(word)
        if not cleaned:
            return []
        sw = _strip_word(cleaned)
        if not sw:
            return []

        try:
            with self._connect() as conn:
                # ⚠️ 查 sw 而非 word：见模块注释的约定 ①
                # 一个 sw 可能对应多个 word（hood/Hood/'hood），**全部返回**（约定 ③）
                rows = conn.execute(
                    "SELECT word, phonetic, translation, exchange, tag, collins, oxford "
                    "FROM dict WHERE sw = ? ORDER BY word",
                    (sw,),
                ).fetchall()
        except (sqlite3.Error, OSError):
            # 词典库损坏 / 文件被卸载：不该让阅读页崩掉
            return []

        out: List[Dict] = []
        for r in rows:
            translation = r[2] or ""
            out.append({
                "word": r[0] or cleaned,
                "phonetic": (r[1] or "").strip(),
                # definition 保留「整段释义」形态（旧的 CSV 方案是这么给的），
                # translations 才是切分后的逐条义项。
                "definition": _normalize_definition(translation),
                "translations": _parse_translations(translation),
                "exchange": (r[3] or "").strip(),
                "tag": (r[4] or "").strip(),
                "collins": r[5] if isinstance(r[5], int) else 0,
                "oxford": bool(r[6]),
            })
        return out

    # ---------- 安装信息 ----------

    def _read_info(self) -> Dict[str, str]:
        if not self._info_path.is_file():
            return {}
        try:
            out: Dict[str, str] = {}
            with open(self._info_path, "r", encoding="utf-8") as f:
                for line in f:
                    idx = line.find("=")
                    if idx > 0:
                        out[line[:idx].strip()] = line[idx + 1:].strip()
            return out
        except OSError:
            return {}

    def _write_info(self, values: Dict[str, str]) -> None:
        try:
            self._info_path.parent.mkdir(parents=True, exist_ok=True)
            with open(self._info_path, "w", encoding="utf-8") as f:
                for k, v in values.items():
                    f.write(f"{k}={v}\n")
        except OSError:
            pass

    # ---------- 下载 + 安装 ----------

    def download_dict(self, on_progress: Optional[Callable[[int, int], None]] = None) -> Path:
        """从服务端下载词典并安装成本地 SQLite 库，返回词典库路径。

        流程：查元信息 → 流式下载 gzip 包 → **MD5 校验** → 解压成 ``dict.db``
        → 校验 SQLite 文件头 → 落安装信息。

        MD5 校验针对**下载件（gzip 包）**，与服务端 ``/api/app/dict/info`` 返回的
        ``downloadMd5`` 同一口径。拿它去校验解压结果必然失败（那是另一个文件的摘要）。

        已安装且版本一致则直接复用，不重复下载。
        """
        if self.is_ready():
            return self._db_path

        self._dict_dir.mkdir(parents=True, exist_ok=True)
        self._cleanup_legacy_csv()

        # ---- 1. 元信息 ----
        total = 0
        remote_md5 = ""
        version = ""
        rows = 0
        try:
            info = self.api.get_dict_info()
            if info.get("available"):
                total = int(info.get("downloadSize") or 0)
                remote_md5 = str(info.get("downloadMd5") or "").strip()
                version = str(info.get("version") or "").strip()
                rows = int(info.get("rows") or 0)
        except Exception:
            pass

        # ---- 2. 下载（流式，支持断点续传）----
        url = self.config.api("/app/dict/download")
        headers = {}
        token = self.config.token
        if token:
            headers["Authorization"] = f"Bearer {token}"

        done = self._part_path.stat().st_size if self._part_path.is_file() else 0
        # 本地残留超过服务端声明的大小 ⇒ 换了版本或文件损坏，丢弃重来
        if total > 0 and done > total:
            self._part_path.unlink(missing_ok=True)
            done = 0
        # 只有「疑似已下完」（长度 == 远端声明）才能用 MD5 判断完整性；
        # 部分文件的摘要与完整文件的摘要必然不相等
        already_complete = False
        if done > 0 and total > 0 and done == total and remote_md5:
            if self._md5_of(self._part_path) == remote_md5:
                already_complete = True
            else:
                self._part_path.unlink(missing_ok=True)
                done = 0

        if not already_complete:
            range_header = f"bytes={done}-" if done > 0 else None
            if range_header:
                headers["Range"] = range_header
            try:
                with requests.get(url, headers=headers, stream=True,
                                  timeout=self.api.TIMEOUT_HEAVY) as r:
                    if r.status_code == 416:
                        # Range 起点越界（.part 恰好等于/超过服务端长度）⇒ 删掉重来
                        self._part_path.unlink(missing_ok=True)
                        done = 0
                        headers.pop("Range", None)
                        r = requests.get(url, headers=headers, stream=True,
                                         timeout=self.api.TIMEOUT_HEAVY)
                        r.raise_for_status()
                        mode = "wb"
                    else:
                        r.raise_for_status()
                        # ⚠️ 服务端若忽略 Range 回 200，此时必须**覆写**而不是追加，
                        # 否则拼出「两份内容」的文件，MD5 必然失败
                        mode = "ab" if r.status_code == 206 and done > 0 else "wb"
                        if mode == "wb":
                            done = 0
                    if total <= 0:
                        total = int(r.headers.get("Content-Length") or 0)
                    last_emit = 0
                    with open(self._part_path, mode) as f:
                        for chunk in r.iter_content(65536):
                            if not chunk:
                                continue
                            f.write(chunk)
                            done += len(chunk)
                            if done > MAX_DOWNLOAD_BYTES:
                                raise RuntimeError(
                                    f"词典文件异常（超过 {MAX_DOWNLOAD_BYTES // (1024 * 1024)}MB），已中止")
                            now_ms = int(time.time() * 1000)
                            if on_progress and now_ms - last_emit >= 200:
                                last_emit = now_ms
                                try:
                                    on_progress(done, total)
                                except Exception:
                                    pass
            except requests.RequestException as exc:
                self._part_path.unlink(missing_ok=True)
                raise RuntimeError(f"词典下载失败：{exc}") from exc
            except ApiException as exc:
                self._part_path.unlink(missing_ok=True)
                raise RuntimeError(f"词典下载失败：{exc.message}") from exc
            except Exception as exc:
                self._part_path.unlink(missing_ok=True)
                raise RuntimeError(f"词典下载失败：{exc}") from exc

        # ---- 3. MD5 校验 ----
        # ⚠️ 失败时必须删掉 .part：留着它，下次进来会走续传分支，
        # 而 MD5 又永远对不上 ⇒ 用户反复重试都下载不成功（死循环）
        if remote_md5:
            actual = self._md5_of(self._part_path)
            if actual != remote_md5:
                self._part_path.unlink(missing_ok=True)
                raise RuntimeError("词典校验失败（文件不完整），请重试")
        elif total > 0 and self._part_path.stat().st_size != total:
            self._part_path.unlink(missing_ok=True)
            raise RuntimeError("词典校验失败（文件不完整），请重试")

        # ---- 4. 解压 ----
        try:
            uncompressed = self._gunzip(self._part_path, self._db_path)
        except Exception as exc:
            self._part_path.unlink(missing_ok=True)
            raise RuntimeError(f"词典安装失败：{exc}") from exc
        self._part_path.unlink(missing_ok=True)

        if uncompressed <= 0:
            self._db_path.unlink(missing_ok=True)
            raise RuntimeError("词典解压失败")

        # ---- 5. 落安装信息 ----
        self._write_info({
            "version": version or "unknown",
            "rows": str(rows),
            "md5": remote_md5,
            "uncompressedSize": str(uncompressed),
        })
        with self._lock:
            self._row_count = rows
        return self._db_path

    def _gunzip(self, src: Path, target: Path) -> int:
        """解压 gzip 到目标文件，返回解压后字节数。

        逐块解压而不是 ``gzip.open(...).copyfileobj(target)`` —— 后者在目标已存在时
        行为依赖实现（部分版本直接覆写、部分追加），而这里必须**确定地覆写**
        （重装词典时目标已存在）。

        解压完校验产物确实是 SQLite：读文件头必须是 ``SQLite format 3\\0``。
        为什么必须查：解压中途磁盘满 / 进程被杀会留下**截断的 .tmp**，
        而它同样是「解压成功返回」的路径。一个截断的库会在第一次查词时抛
        ``DatabaseError``，用户看到的却是「词典已安装」—— 极难定位。
        """
        tmp = target.with_name(target.name + ".tmp")
        written = 0
        try:
            with gzip.open(src, "rb") as gz, open(tmp, "wb") as out:
                while True:
                    chunk = gz.read(1024 * 256)
                    if not chunk:
                        break
                    out.write(chunk)
                    written += len(chunk)
            if not self._looks_like_sqlite(tmp):
                raise RuntimeError("解压产物不是有效的 SQLite 库")
            if target.exists():
                target.unlink()
            os.replace(tmp, target)
            return written
        finally:
            # 成功路径上 tmp 已被 rename 走，delete 找不到文件、无副作用
            if tmp.exists():
                tmp.unlink(missing_ok=True)

    @staticmethod
    def _looks_like_sqlite(path: Path) -> bool:
        try:
            with open(path, "rb") as f:
                header = f.read(16)
            return header == b"SQLite format 3\x00"
        except OSError:
            return False

    @staticmethod
    def _md5_of(path: Path) -> str:
        if not path.is_file():
            return ""
        digest = hashlib.md5()
        try:
            with open(path, "rb") as f:
                for chunk in iter(lambda: f.read(1024 * 256), b""):
                    digest.update(chunk)
        except OSError:
            return ""
        return digest.hexdigest()

    def _cleanup_legacy_csv(self) -> None:
        """删掉旧 CSV 方案留下的 ``ecdict.csv``。

        旧实现把服务端下发的 gzip 包解压后**直接命名为 ecdict.csv** ——
        而那其实是个 SQLite 库。于是本地留下一个 58MB 的「假 CSV」，
        既占空间又会让用户以为词典已装好（实际查词恒空）。
        """
        legacy = self._dict_dir / LEGACY_CSV_NAME
        if legacy.is_file():
            try:
                legacy.unlink()
            except OSError:
                pass

    # ---------- 卸载 ----------

    def uninstall(self) -> bool:
        """卸载词典：删库 + 删安装信息 + 删残留临时文件。

        删不掉的 .part / .tmp 一并清掉 —— 留着它们下次下载会走续传分支，
        而 MD5 对不上必然重来，白占一份磁盘。
        """
        removed = True
        for p in (self._db_path, self._part_path,
                  self._db_path.with_name(self._db_path.name + ".tmp"),
                  self._info_path, self._dict_dir / LEGACY_CSV_NAME):
            try:
                if p.exists():
                    p.unlink()
            except OSError:
                removed = False
        with self._lock:
            self._row_count = 0
        return removed
