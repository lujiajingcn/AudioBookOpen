"""数据模型（对标 Android .model 包）。"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any
from enum import Enum


# ---------- 认证 ----------

@dataclass
class AuthResponse:
    token: str = ""
    is_admin: bool = False
    success: bool = False
    message: str = ""


# ---------- 书籍 ----------

class BookCategory(str, Enum):
    LOCAL_TXT = "txt"
    LOCAL_EPUB = "epub"
    LOCAL_PDF = "pdf"
    LOCAL_MOBI = "mobi"
    LOCAL_MD = "md"
    STORE = "store"


@dataclass
class Book:
    id: int = 0
    display_name: str = ""
    file_path: str = ""
    file_ext: str = ""
    has_audio: bool = False
    last_read_at: str = ""
    category: str = ""
    sort_order: int = 0
    # 书城书
    store_book_id: Optional[int] = None
    author: str = ""
    description: str = ""
    cover_url: str = ""
    download_count: int = 0


@dataclass
class StoreBook:
    id: int = 0
    title: str = ""
    author: str = ""
    category: str = ""
    description: str = ""
    file_extension: str = ""
    file_size: int = 0
    cover_color: str = ""
    download_count: int = 0
    task_id: Optional[int] = None
    status: str = "onshelf"
    # 标签（一本书可有多个）。分类是单选归属、标签是多选描述，两者并存。
    # 旧版服务端不返回该字段，默认空列表，展示侧无需判空。
    tags: List[str] = field(default_factory=list)


@dataclass
class TocEntry:
    title: str = ""
    level: int = 0
    paragraph_index: int = 0


@dataclass
class LineItem:
    """阅读页的一行文本。"""
    index: int = 0
    text: str = ""
    segment_number: Optional[int] = None  # 对应的音频段号
    char_start: int = 0
    char_end: int = 0


# ---------- 任务 ----------

class TaskStatus(str, Enum):
    PENDING = "pending"
    QUEUED = "queued"
    PROCESSING = "processing"
    COMPLETED = "completed"
    PARTIAL = "partial"
    FAILED = "failed"
    PAUSED = "paused"


@dataclass
class Task:
    id: int = 0
    book_name: str = ""
    audio_name: str = ""
    status: str = TaskStatus.PENDING.value
    progress: int = 0
    total_segments: int = 0
    completed_segments: int = 0
    progress_text: str = ""
    server_task_id: Optional[int] = None
    download_status: str = ""
    created_at: str = ""
    error_message: str = ""

    @property
    def is_local(self) -> bool:
        return self.audio_name.startswith("本机合成 · ")

    @property
    def is_terminal(self) -> bool:
        """终态：合成已结束，不会再自行推进。

        ⚠️ 必须与服务端 ``TaskStatus.isTerminal`` **同口径**（含 partial）：
        服务端把 partial 也当终态（它是一次性跑完但有段失败的结论），
        PC 端原先漏了它 ⇒ 同一个任务在两端被判定成不同性质，
        「能不能续跑 / 要不要给按钮」就会在跨端场景下打架。
        对齐服务端：completed / partial / failed。
        """
        return self.status in (TaskStatus.COMPLETED.value,
                               TaskStatus.PARTIAL.value,
                               TaskStatus.FAILED.value)


# ---------- 音频段 ----------

@dataclass
class Segment:
    id: int = 0
    task_id: int = 0
    segment_number: int = 0
    text_content: str = ""
    audio_file_path: str = ""
    duration_seconds: float = 0.0
    status: str = "pending"
    voice: str = ""
    # 段音频来源：'local' = 本机合成落盘（服务端无字节，跨端不可下），
    # 'server' = 从服务端下载（服务端有字节，其它端可下载）。
    # 与 Android audio_segments v12 迁移、PC database.py._migrate 的 source 列对齐。
    # 覆盖图 (local_audio_coverage) 判源优先读这一列，回退到文件名前缀启发（tts_/local_）。
    source: str = "local"


# ---------- 音色 ----------

@dataclass
class Voice:
    id: str = ""
    display_name: str = ""


# ---------- 参考音频 ----------

@dataclass
class ReferenceAudio:
    id: int = 0
    name: str = ""
    file_path: str = ""
    duration_seconds: float = 0.0
    created_at: str = ""


# ---------- 社区 ----------

@dataclass
class Post:
    id: int = 0
    title: str = ""
    content: str = ""
    author: str = ""
    created_at: str = ""
    comment_count: int = 0
    favorite_count: int = 0
    # 图片相对地址列表（如 ["/api/posts/image/xxx.png"]）——服务端 imageUrls 是**数组**，
    # 早期按单串 image_url 读，永远取不到值。
    image_urls: List[str] = field(default_factory=list)


@dataclass
class Comment:
    id: int = 0
    post_id: int = 0
    content: str = ""
    author: str = ""
    created_at: str = ""


# ---------- 积分 / 支付 ----------

@dataclass
class CreditInfo:
    balance: int = 0
    free_trials_remaining: int = 0


@dataclass
class CreditPackage:
    id: int = 0
    name: str = ""
    credits: int = 0
    amount: float = 0.0
    description: str = ""


@dataclass
class CreditTransaction:
    id: int = 0
    type: str = ""
    credits: int = 0
    balance_after: int = 0
    description: str = ""
    created_at: str = ""


# ---------- 阅读进度 ----------

@dataclass
class ReadingProgress:
    segment_number: int = 0
    offset_ms: int = 0
    updated_at: str = ""
    server_updated_at: str = ""


# ---------- 收藏 ----------

@dataclass
class Favorite:
    id: int = 0
    book_id: int = 0
    text: str = ""
    note: str = ""
    created_at: str = ""
    # 云端身份（与服务端 /api/favorites 对齐）：唯一身份 = (book_name, segment_number)
    book_name: str = ""
    segment_number: int = 0
    server_id: Optional[int] = None   # None = 本机新增、尚未上云
    task_id: Optional[int] = None     # 「去听」深链辅助，不参与去重
    store_book_id: Optional[int] = None  # 书城书身份（本机深链跳转用，服务端不存）


# ---------- 单词本 ----------

@dataclass
class WordEntry:
    word: str = ""
    phonetic: str = ""
    definition: str = ""
    created_at: str = ""
    # 云端身份（跨端同步，对齐 Android/服务端 /api/word-book）：
    # sw = 归一化键（去非字母数字+小写），唯一身份；server_id 记录服务端主键（None=本机新增待上云）。
    sw: str = ""
    server_id: Optional[int] = None
    # 收藏出处（三端单词本「跳转到原书」用，与服务端 WordBookItemRequest 的 6 键逐字段对齐）。
    # 全部可空：老数据 / 旧客户端 / 服务端返回 None 时「跳转到原书」必须禁用或提示，绝不崩。
    # ⚠️ line_number 只是 hint —— 行号会随切分粒度漂移，定位第一判据是 sentence_text。
    book_name: Optional[str] = None
    book_key: Optional[str] = None
    store_book_id: Optional[int] = None
    segment_number: Optional[int] = None   # 段号，1-based
    line_number: Optional[int] = None      # 行号 hint，仅兜底
    sentence_text: Optional[str] = None    # 收藏时所在句子/段落原文，定位第一判据


# ---------- 更新 ----------

@dataclass
class AppUpdateInfo:
    version_code: int = 0
    version_name: str = ""
    changelog: str = ""
    download_url: str = ""
    file_size: int = 0
    md5: str = ""
    force_update: bool = False
    has_update: bool = False


# ---------- 通用响应 ----------

@dataclass
class ApiResult:
    success: bool = True
    message: str = ""
    data: Any = None
