# AudioBookLocalTtsTool

Windows 桌面工具：在**本机**用 [MOSS-TTS-Nano](https://github.com/OpenMOSS/MOSS-TTS-Nano)
把电子书合成语音，按 AudioBookServer 的同一套口径写入**本地 MySQL**，
并支持一键把「电子书数据 + 对应音频」**发布到书城**（属于有声书后台管理，不进普通用户书架）。

```
┌──────────────┐   JSON 行协议   ┌──────────────────┐
│  工具主进程   │ ◄─────────────► │  MOSS 桥接进程    │
│ (PySide6)    │                 │ (MOSS 工程解释器) │
└──────┬───────┘                 └──────────────────┘
       │
       ├── 本地 MySQL：tasks + audio_segments（合成结果落库）
       └── 服务器：一键发布到书城（tasks + audio_segments + store_books + 文件）
```

## 1. 它解决什么问题

服务端合成要登录、要积分、书要上传到云上。想在**自己电脑上**把书批量做成有声书，
并让结果最终出现在服务器上、客户端能正常播放，就是这个工具。

关键设计：**产物结构与服务端完全同构**。
`tasks` / `audio_segments` 的表名、字段名、段号规则、音频命名规则
（`{taskId}/tts_{taskId}_{n}.mp3`）全部照抄 `AudioBookServer/init_database.sql`，
所以本地产物可以原样推到服务器，不需要任何转换。

## 2. 运行前提

| 依赖 | 说明 |
|---|---|
| Python 3.10+ | 工具本体 |
| MOSS-TTS-Nano 工程 | 官方仓库，**需要它自己的 Python 环境**（onnxruntime 等） |
| MySQL | 本地一个、服务器一个（服务器的是 audiobook_db） |
| ffmpeg（可选） | 有则转 mp3（与服务端一致），没有则保留 wav |

### 本机已验证的环境（2026-10-26 实测）

| 项 | 值 |
|---|---|
| MOSS 工程 | `E:\study_audiobook\WorkSpace_AudioBook\MOSS-TTS-Nano` |
| 引擎解释器 | `C:\Users\audiobook\miniconda3\envs\moss-tts-nano\python.exe` |
| 模型 | 已在 `models/`（`MOSS-TTS-Nano-100M-ONNX` + `MOSS-Audio-Tokenizer-Nano-ONNX`），**无需下载** |
| 引擎加载 | 约 28 秒（CPU，16 线程） |
| 引擎音色 | 18 个内置音色（`Junhao`/`Zhiming`/`Weiguo`/`Xiaoyu`…，含英/日） |
| 单段合成 | 15 字 ≈ 23 秒、35 字 ≈ 40 秒（CPU 推理，比云端慢很多） |

> 工具默认已把上面两个路径填好，直接打开就能用；换机器时在界面里改一次即可（会记住）。

### 安装工具本体

```bash
pip install -r requirements.txt
python run.py
```

也可以直接双击 `start.bat`（会先检查 PATH 里有没有 python）。

### 准备 MOSS 引擎环境

```bash
git clone https://github.com/OpenMOSS/MOSS-TTS-Nano.git
cd MOSS-TTS-Nano
conda create -n moss-tts-nano python=3.12 -y && conda activate moss-tts-nano
pip install -r requirements.txt
pip install -e .
```

引擎在**首次运行**时会自动下载 ONNX 权重到工程的 `models/` 目录
（`MOSS-TTS-Nano-100M-ONNX` 与 `MOSS-Audio-Tokenizer-Nano-ONNX`）。

## 3. 界面怎么用

### 左栏（配置）

* **MOSS 引擎**
  * *工程路径*：MOSS-TTS-Nano 目录（含 `onnx_tts_runtime.py`）
  * *Python 解释器*：填上面 conda 环境的 `python.exe`；留空则用运行工具的解释器
  * *模型目录*：留空 = 工程默认的 `models/`
  * *CPU 线程* / *执行提供方*（cpu/cuda）/ *采样模式*（fixed/full/greedy）
* **本地库**：合成结果写入的 MySQL（IP、端口、库名、账号、密码）
  * 「测试连接」验证连通性，「初始化表结构」自动建 `users` / `tasks` / `audio_segments`
  * 「**对齐服务器结构**」把本地库改成与服务器**完全一致**（见下节）
  * *建表脚本*：默认已填好 `AudioBookServer/init_database.sql` 的路径
* **服务器（SSH 发布）**：发布走 SSH —— 文件用 scp/tar 传，数据库用 `ssh -L` 隧道连
  * *发布到*：**书城**（默认，管理员视角）/ 个人书架。书城 = 建一条归管理员的任务 +
    一条 `store_books` + 原书落书城目录；书架 = 只建任务（旧行为）
  * *SSH 主机 / 端口 / 用户 / 私钥*：私钥留空则用 ssh-agent（后台线程不会弹口令框，
    `BatchMode=yes` 下需要口令的密钥会直接报错）
  * *uploads 目录*：留空 = 读服务器 env 里的 `UPLOAD_DIR`（默认 `/data/audiobook/uploads`）
  * *书城目录*：留空 = 读服务器 env 里的 `STORE_DIR`（默认 `/data/audiobook/store_books`）
  * *服务器 env*：`/etc/audiobook/audiobook-server.env`，里面有 `DB_URL` /
    `DB_USERNAME` / `DB_PASSWORD` / `UPLOAD_DIR` / `STORE_DIR`，工具会自动读，**不用手填库密码**
  * *隧道端口*：0 = 每次自动挑空闲端口（避免上次残留的隧道占着 13306）
  * *库口令覆盖*：一般留空；只有 env 读不到、或要连另一套库时才填
  * *书架用户名*（shelf 模式）：任务挂在哪个用户名下；填错会直接把可选用户名列出来
  * *书城归属账号*（store 模式）：发布出来的任务归哪个管理员（默认 `audiobook_admin`），
    **正因为归管理员，普通用户的书架里才不会多出一本**
  * *书城分类*：发布时的分类（服务端按它建 `store_books/{分类}/` 目录），之后可在管理端改
  * *管理端地址*：发布收尾补「正文快照」用的 HTTP 地址。留空 = `http://<SSH 主机>`
    （生产是 nginx :80 反代 `/api`）；直连后端调试可填 `http://<ip>:8080`
  * 「测试上传通道」一次性验证 SSH → 读 env → 建隧道 → 连库这四步
* **其他**：ffmpeg 路径、中间产物目录、是否保留每段中间音频

### 右栏（干活）

1. 选**本机电子书**（txt / md / epub / pdf / mobi / azw / azw3）—— **可以多选**
   * 「选择…」按钮打开的是多选对话框，**一次能选多本**；
   * 多本之间用 `;` 分隔写进输入框（输入框可以直接编辑 —— 想只留其中几本，删掉别的即可）；
   * 每本书 = **一个独立任务**（各自从 1 开始编号段号），互不干扰。
2. 可选：选**本机参考音频**（wav/mp3/…）→ 克隆自己的声音；
   或点「刷新音色」选**内置音色**
3. 「开始合成」→ 日志与进度实时刷新；「停止」在**当前小片**结束后就生效
   （一段被拆成 48 片时，不必等那一整段跑完）
4. 合成完成后，「本地任务」表格出现该任务 → 「一键发布（全部未发布）」推到**书城**
5. 批量合成完，这批任务会**自动在表格里选中** → 直接点「发布选中任务」即可
   （书城里书名默认取本地任务名；想改书名/作者/分类，发布后到管理端改，或用 `tools/upload_cli.py`）

### 长跑 / 中断了怎么办（断点续跑）

一本书 3000+ 段、本地 CPU 约 30 秒/段 ⇒ **十几小时**。所以默认开了「断点续跑」：

| 情况 | 行为 |
|---|---|
| 点「停止」/ 关窗口 / 断电 / 进程被杀 | 任务标 `paused`（来不及收尾就是留在 `processing`），已完成段一直在库里 |
| 再点「开始合成」（同一本书） | **复用原 task_id**，跳过已 `completed` 的段，接着往下合成 —— 不会从第 1 段重来，也不会多出一个半本书的新任务 |
| 想强制从头跑 | 取消勾选「断点续跑」，则一律新建任务、从第 1 段合成 |
| 任务表的段数 | 每 **10 段**写一次 `completed_segments`，所以中途也能看出跑到哪（不必等收尾） |

续跑的匹配条件刻意苛刻：**同文件名 + 同总段数 + 同参考音频 + 同音色**。
任何一项变了都算另一件事（段号对不齐 / 前后不是同一个声音），会新建任务而不是硬接。

> ⚠️ 同一个任务**不能被两个进程同时写**：重启工具续跑前，先把旧窗口关掉。

### 为什么第 1 段能跑十几分钟（长段预警）

PDF 的封面+目录是天然的长段：几千字、全是点线和页码、**中间没有句号**，切句器切不开，
整段被当成"一句话" → 拆成几十小片逐片合成。这时界面会：

* 开跑前先预警：`⚠ 有 N 个超长段…第 1 段 5217 字 → 48 片，约 16 分钟`；
* 段内每 5 秒一行心跳：`段 1/3329 · 片 12/48 · 本段已 3 分钟`（顶栏同步刷新）。

没有这两样的话，这十几分钟里界面完全静止 —— 和卡死一模一样（2026-10-30 实际踩过）。

### 多选（批量合成）是怎么跑的

| 点 | 做法 |
|---|---|
| 任务划分 | 一本书 = 一个任务；段号各自从 1 开始，`(task_id, segment_number)` 唯一 |
| 引擎复用 | 整个批次**只加载一次模型**（`EngineSession`）。MOSS 加载一次要 ~28 秒，每本各加载一次就白等好几分钟 |
| 单本失败 | 某本书坏了（格式不支持、正文为空、文件被删）**不中断整批**：记下原因，继续下一本，最后统一汇报「成功 X 本 / 失败 Y 本」 |
| 中途停止 | 「停止」在**当前小片**结束后生效（不是整段）：已合成的那本保留结果，剩下的书标记为「已跳过」 |
| 进度显示 | 状态栏显示「第 i/N 本 + 本书内已合成段数」，日志里逐本打印开始/结束，长段期间还有段内心跳 |
| 发布 | 合成完自动选中这批任务，「发布选中任务」一次全推；也可以用「一键发布（全部未发布）」 |

自检（不碰真引擎、不碰业务库，跑在独立临时库上）：

```bash
python tools/selftest_batch.py     # 批量合成：任务划分/引擎复用/单本失败/中途停止
python tools/selftest_resume.py    # 断点续跑/长段预警/段内心跳/进度落库
```

## 4. 与服务端保持一致的关键点

| 点 | 做法 |
|---|---|
| 片段定义 | 「一条完整句子 = 一个片段」，复刻 `TextSegmenter.sentences()`（已与 Java 实现做 300+ 样本交叉验证，逐段一致） |
| **句内换行** | 切句时把**句子内部**的换行清掉：两侧都是 ASCII 字母/数字 ⇒ 换成**一个空格**（`hello\nworld` → `hello world`）；其余（中文、书名号、标点）⇒ 连同两侧空白**一起删掉**（`《汉\n书》` → `《汉书》`）。PDF/EPUB 抽出来的换行只是**版面换行**，留着送进 TTS 引擎就会在那里停顿（线上反馈：「《汉书》中间有个换行符，导致合成音频时有停顿」）。与服务端 `TextSegmenter.cleanInnerNewlines` 逐字一致；不变式仍是「只动空白、不动实字」 |
| 句末空白集 | 必须与 Java 的 `\s` 逐字一致（`[ \t\n\x0B\f\r]`，**不含** U+3000）。Python 的 `\s` 会多匹配全角空格 ⇒ 两端段数漂移 ⇒ 段号错位 |
| 段号 | **段号 = 句子序号**，从 1 开始，`(task_id, segment_number)` 唯一。**一个句子都不能丢** —— 客户端按段号对齐「正文段落 ↔ 音频段」，丢一句就会让后面整体前移、声音与文字错位 |
| 超长句 | 拆成 ≤45 字小片、逐片合成再拼回一条（与服务端 `TextSegmenter.subPieces` + `WavConcat` 同口径），切分点与停顿才和服务器一致 |
| 生成帧数 | 与服务端同一公式：`base(30) + 字符数 × 4.0`，夹到 `[30, 375]`，**按小片字数**下发 |
| 不可朗读的段 | 整段没有可朗读内容（如一整行装饰线）时，生成 **300ms 静音占位**（与服务端 `WavConcat.silentWav()` 同规格：22050Hz/单声道/16bit），保住「每段都有音频」的契约 |
| 合成失败的段 | **照样落库**（`status=failed` + 错误原因），段号照占。上传时也照传，否则服务器侧会缺号 |
| 表结构 | `tasks` / `audio_segments` 字段与 `init_database.sql` 一致 |
| 音频命名 | `{taskId}/tts_{taskId}_{n}.mp3` |
| 参考音频 | 入库前裁到 8 秒（对齐服务端 `moss.tts.reference-max-seconds`），否则两端音色会有差别 |
| 音频读取 | 服务端「先找文件 → 找不到回退 BLOB」：工具把音频字节写进 `audio_data`，所以不碰服务器文件系统也能播 |
| 任务状态 | `processing` / `paused` / `completed` / `partial` / `failed`，与服务端 `TaskStatus` 同一套取值 |
| 电子书格式 | txt / md / epub / pdf / **mobi / azw / azw3 / prc**。MOBI 用纯标准库解（PDB 容器 + PalmDOC LZ77，见 `mobitext.py`），**解压出的 HTML 与服务端 `BookTextExtractor` 逐字节相同**（本机 9 本中文书比对过 SHA-256），切出的段数也一致 |
| EPUB 章节 | 按 OPF spine 顺序读章节，**不按后缀过滤**：有些书把章节存成 `.xml`（实测《改革历程.epub》是 `OEBPS/chapter1.xml …`），只认 `.xhtml/.html/.htm` 会一章都取不到、整本提取为空。spine 缺失时退回「自然排序取非资源文件」（`chapter2` 在 `chapter10` 前面） |

### MOBI / AZW：两个只有真实文件才会暴露的长度约束

MOBI 正文是 PalmDOC LZ77 压缩的 HTML，藏在 Palm Database（PDB）容器里。
本机 9 本中文 MOBI 实测踩到的坑（服务端原本也有，已一并修掉）：

| 坑 | 现象 | 现在的做法 |
|---|---|---|
| **单条文本记录解压后不超过 recordSize（4096）** | 压缩流末尾带填充字节（如 `00 81`），不限长就多解出几字节，**后面的正文整体错位** | 每条记录解压上限 = record0 的 recordSize |
| **正文总长 = record0 的 textLength** | 最后一条文本记录后面还挂着别的数据（目录、封面标记），不截断就把杂字节当正文 | 解压完按 textLength 截断 |

两条都缺时：解压结果比 textLength 多几百字节，且每 4096 字节就有一段非法 UTF-8 ——
`new String(bytes, UTF_8)` 会把它们变成 `�`，正文里每隔几页就出现一个乱码。
现在实测：9 本书解压长度与 textLength **逐字节相等**、整段 UTF-8 零错误。

其它实现细节（PDB 头 78 字节 / 记录数在偏移 76 / MOBI 头 big-endian /
LZ77 回引要按活动缓冲逐字节读）都写在 `app/core/mobitext.py` 的注释里。

> **限制**：DRM 加密的 AZW3 解不了（会明确报「压缩类型无效」）；
> 也因此不引第三方库（`pip install mobi` 会拖进 kindleunpack + pycryptodome 一串依赖）。

### 存量修复：把**已经合成过**的旧段里的换行清掉

切句规则改成「去掉句内换行」只对**新合成**的书生效；
已经落库的旧段落，`text_content` 里还留着 PDF 的版面换行，**音频里也停了一下**。

```bash
# 先看会动哪些段（不改数据）
python tools/repair_legacy_newlines.py --task 3 --dry-run

# 正式修：清文本 + 用同样的音色/seed/拆片重新合成，覆盖原行的音频
python tools/repair_legacy_newlines.py --task 3

# 只清文本、不重合成（快，但音频里的停顿还在）
python tools/repair_legacy_newlines.py --task 3 --no-audio
```

| 安全边界 | 做法 |
|---|---|
| 先备份 | 动过的行（含旧音频字节）写到 `%APPDATA%/AudioBookLocalTtsTool/backup/`，可回滚 |
| 只动带换行的段 | 其它段一行不碰 |
| 段号不变 | 不重排、不删除、不新增段 ⇒ 客户端「段号 ↔ 行号」映射不受影响 |
| 音色一致 | 复用任务记录的参考音频（同名文件在 MOSS 工程 `assets/audio/` 里自动找）、同样的 seed 与帧数预算 |

### 自检

```bash
python tools/selftest_segmenter.py                   # 切句口径：句内换行清理、句末空白集、不丢字不变式
python tools/selftest_segmenter.py "D:\电子书"       # 再用真实书库回归一遍（每本书都必须 0 段含换行）
python tools/selftest_mobi.py                        # 构造样本 + 全链路断言
python tools/selftest_mobi.py "D:\电子书"            # 再用真实书库回归一遍
python tools/selftest_resume.py                      # 断点续跑 / 心跳 / 进度落库（临时库 + 假引擎）
python tools/selftest_upload.py                      # 发布：env 解析、书城绑定、同名复用、回滚、快照收尾
python tools/selftest_snapshot.py                    # 正文快照：JWT 签发格式 / env 密钥清洗 / 用户鉴权读取
python tools/selftest_snapshot.py --live             # 再连真服务器跑一遍（会真的重建一本书的快照）
```

上传自检用假 SSH（只记录传了哪些文件）+ 两个临时库，**不碰真服务器**。
`selftest_snapshot.py` 离线部分同样不碰服务器；加 `--live` 才连，
并且会额外验证「换一个字符的密钥必须被 401 拒掉」——证明调用真的走了鉴权。

### 已知差异（都在服务端「送引擎」那一步，不影响段号与数据结构）

* **音色集合不同**：服务端走 `app_onnx.py` 的 HTTP 接口、用引擎的 **demo 音色**
  （`demo-1`…`demo-6`，见 `moss.tts.voices`）；工具走 `onnx_tts_runtime` 进程内推理、
  用的是 manifest 里的 **builtin_voices**（`Junhao`/`Zhiming`/`Weiguo`…，18 个）。
  两套 id 不通用，但各自在自己的通道里都有效。落库与上传不受影响。
* **音色连贯性**：服务端有「滚动韵律 prompt」（连续几段沿用上一段的尾巴），
  工具目前每片独立合成 —— 长段落之间的语气衔接会略逊于服务端。
* **帧数预算**：工具与服务端用**同一套公式**（`base(30) + 字符数 × 4.0`，夹到 `[30, 375]`），
  按**小片**字数下发，所以短句尾部不会被拖出一大段无用静音。
* **HTML → 纯文本的细节**：服务端 `stripHtmlTags` 把「被删掉的标签」替换成**空格**，
  工具自己的 `_html_to_text`（epub / mobi 共用）不留空格 ——
  所以同一本书在两端的**段数完全一致**（9 本 MOBI 实测逐本核对），
  但段内文字可能差几个空格（不影响朗读与段号对齐）。

## 5. 「一键发布」到底做了什么（SSH 文件模式）

这个工具是**配合有声书后台管理**用的：成品进「书城」，不进某个用户的书架。
服务端这两者是两套数据，混淆过一次（书传上去了，书城里看不到、用户书架里多一本），
所以这里把口径写死：

| | 书架 | 书城 |
|---|---|---|
| 数据 | `tasks.user_id` 指向某用户 | `store_books` 一行 + 原书在 store 目录 |
| 音频 | `uploads/{taskId}/…` | 同左，但由 `store_books.task_id` 指过去 |
| 可见性 | 只有该用户 | 所有登录用户（`status=onshelf`） |

**发布到书城**（默认）= 三件事一起做：

1. 建一条**归管理员账号**的任务（默认 `audiobook_admin`）—— 所以普通用户书架里不会有它；
2. 原书落书城目录 `<store>/{分类}/<uuid>_<书名>.<ext>`，写 `store_books`
   （`title/author/category/file_size/cover_color/status=onshelf/task_id/synthesized_by`）；
   `cover_image` 留空，服务端 `CoverRenderer` 会现画「书名+作者」封面；
3. 音频推到 `<uploads>/{serverTaskId}/tts_{serverTaskId}_{段号}.mp3`，元数据写
   `tasks` + `audio_segments`（`audio_data` 恒 NULL）。

对每个本地任务的具体动作：

1. **书城原书** → `<store>/{分类}/{uuid_书名}.<ext>`，`store_books.file_name` 记它
   （书城「下载」的文件名、阅读页抽正文都用这个）。
2. **任务原书** → `<uploads>/file_<uuid>.<ext>`，`tasks.book_file_path` 记它 ——
   任务侧仍能独立打开 / 重新合成。原书从工作目录的 `book.<ext>` 拿（新版合成会自动留副本）；
   老任务没副本，发布前会让你选一次。
3. **音频**用 tar 流推过去，段号保持原样；工作目录缺文件时自动从本地库 BLOB 导出兜底。
4. **元数据**经 SSH 隧道写 `127.0.0.1:3306`。
5. 传完**逐个核对远端文件名**：少一个就回滚（删库里的行 + 删刚传的目录、两份原书），
   绝不留「有书没声」的半成品。
6. **补正文快照**：调一次 `POST /api/admin/books/{id}/content-snapshot`，让服务端把整本
   正文的解析结果（`book_content_snapshots`）存下来 —— 这一步只有服务端能算，本地做不了。
7. 本地库记一条 `tool_upload_log`（本地 id ↔ 服务器 task id），避免重复发布。

> ⚠️ 第 6 步为什么必须有：快照本来只在**服务端自己合成**收尾时写入，而本地工具是
> 「库里直写」，合成那一步从未发生 ⇒ 本地发布的书天生没有快照，
> 客户端第一次打开阅读时服务端只能现解析整本：同一本书**有快照 95ms、没快照 9225ms**，
> 还要多等一次全文回传。补上就归零（实测《潜规则》3512 段 / 4092 行一次到位）。
>
> 调用方式不猜管理员口令：用服务器 env 里的 `JWT_SECRET` 在**本地**按 `JwtUtil` 的口径
> （密钥 = `SHA-512(secret)`、HS512、claims `sub/isAdmin/tokenVersion`）签一个 10 分钟的
> 管理员 token，只放进请求头。服务端照样按库里的 `token_version` 与封禁状态复核，
> 权限不会超过那个账号。地址默认 `http://<SSH 主机>`，可用「管理端地址」覆盖。
>
> 这一步**失败只告警，不拦发布**（书已经是完整可听的，只是首次打开慢一点）。

幂等：书城已有**同名**书且它已经有音频 ⇒ 默认跳过；勾「强制重发」才覆盖，
且**复用同一条 `store_books` 记录与原文件名**（书城里不会堆出两本同名的书）。

> ⚠️ 为什么不是「把音频塞进数据库」：生产 MySQL 只监听回环（公网 3306 连不通），
> 且 `audio_data` 是 `tinyblob`（255 字节）——服务端本来就是「文件优先、BLOB 兜底」。
> 旧版这条路在第一行容量检查就会抛错，从来没有跑通过。

> ⚠️ 合成失败的段**照样写库**（`status=failed`，不占文件但占段号）：
> 少一个段号，后面所有段的声音都会整体错位。

> ⚠️ 书城书只是**引用**任务：删书城书不会动音频；但在 App「我的书籍」里删掉那条任务，
> 会把 `audio_segments` 和 uploads 里的 mp3 一起删掉，书城那本立刻变哑。
> 发布出来的任务归管理员账号，普通用户本来也看不到、删不到。

### 不开界面也能发：`tools/upload_cli.py`

界面里点「一键发布」是在后台线程跑的，关窗口就断；上千段的长任务更适合走命令行
（同一条 `upload_tasks` 链路，只是把「选原书文件」从文件对话框换成参数）：

```bash
# 发布到书城（默认），书名/作者/分类可覆盖 —— 本地 PDF 文件名常常是 `[书名].作者`，直接上架很难看
python tools/upload_cli.py --task 2 --book "E:/本地合成的电子书/论持久战全文.pdf" \
    --title "论持久战全文" --author "毛泽东" --category "军事"

# 先干跑：只看会发哪些、缺不缺原书
python tools/upload_cli.py --task 2 --book "..." --dry-run

# 全部未发布过的任务（原书自动找工作目录的 book.*）
python tools/upload_cli.py

# 传到个人书架（旧行为，书只出现在那个账号的「我的书籍」里）
python tools/upload_cli.py --task 2 --book "..." --target shelf --user audiobook
```

日志同时写屏和 `tools/upload_cli.log`（长命令的输出容易被截断，事后只能靠日志复盘）。
退出码：0 成功、1 有任务失败、2 缺原书或参数不对。

> 踩过的坑：汇总行（「结束：成功 N 个…」）的日志句柄曾被 `finally: fh.close()`
> 提前关掉，发布明明成功却在收尾抛 `ValueError: I/O operation on closed file`，
> 退出码变非 0。**日志句柄必须留到汇总打印之后再关。**

## 6. 「对齐服务器结构」：把本地库改成和服务器一模一样

### 为什么需要

服务端的表结构由 `AudioBookServer/init_database.sql` 定义（生产环境
`ddl-auto=validate`，启动时逐列校验，对不上就起不来）。
而**老版本建出来的库**已经和它不一致了，本机实测踩过两个坑：

| 老库的样子 | 后果 |
|---|---|
| `audio_segments.audio_data` 是 `tinyblob`（255 字节） | 插一段 mp3 就报 `Data too long for column 'audio_data'`（MySQL 1400） |
| `tasks.reference_audio_path` 是 `NOT NULL` | 「没选参考音频」时插任务报 `Column ... cannot be null`（MySQL 1048） |

`CREATE TABLE IF NOT EXISTS` **不会改已有表**，所以光跑一遍建表脚本没用。

### 怎么做的

不解析 SQL，而是**建一个参考库再比对**：

1. 建临时库 `audiobook_db_schema_ref`
2. 用 `init_database.sql` 在参考库里建出标准结构（服务器必然是这个结构）
3. 逐表比对本地库与参考库（列 / 索引 / 外键），生成 ALTER
4. 在本地库执行 → 再跑一次校验 → 删掉参考库

目标结构 100% 来自服务端脚本本身，不会「解析 SQL 解析错了」。

### 安全边界

* 只做**不丢数据**的变更：加列、改可空性、放宽类型、补索引/外键
* 类型**收窄**且本地确有超长数据 → 跳过并报告（不擅自截断）
* NULL → NOT NULL 且本地存在 NULL → 先回填再改，并报告
* 不删列、不删表、不动数据行

### 命令行等价用法

```bash
python tools/align_local_schema.py --host 127.0.0.1 --port 3306 \
    --user root --password xxx --database audiobook_db \
    --ddl "E:\path\to\AudioBookServer\init_database.sql"

python tools/align_local_schema.py ... --dry-run   # 只看差异
python tools/align_local_schema.py ... --verify    # 只校验
```

## 7. 目录结构

```
AudioBookLocalTtsTool/
  run.py                    启动入口
  requirements.txt
  app/
    config.py               配置数据与持久化
    core/
      segmenter.py          切句（与服务端同口径）
      booktext.py           正文提取（txt/md/epub/pdf/mobi）
      mobitext.py           MOBI/AZW 解析（PDB 容器 + PalmDOC LZ77）
      moss_bridge.py        引擎桥接脚本（跑在 MOSS 解释器里）
      moss_client.py        桥接客户端（子进程 + JSON 行协议）
      db.py                 MySQL 存取（本地库 / 服务器库）
      schema_align.py       表结构对齐到服务器结构
      audio.py              WAV→MP3、时长、拼接
      synthesizer.py        合成编排
      uploader.py           一键发布（书城 / 个人书架两条路径）
      ssh_upload.py         SSH 通道（scp / tar 流 / -L 隧道 / 读服务器 env）
    ui/
      main_window.py        PySide6 主窗口
  tools/
    align_local_schema.py   结构对齐的命令行入口
    upload_cli.py           发布的命令行入口（不开界面也能发，可覆盖书名/作者/分类）
    selftest_upload.py      发布链路自检（假 SSH + 两个临时库，7 组）
    selftest_resume.py      断点续跑自检
    selftest_mobi.py        MOBI 解析自检（构造样本 + 真实书库回归）
    selftest_batch.py       批量合成自检（多选 → 多任务、引擎复用、失败隔离）
    selftest_segmenter.py   切句自检（句内换行清理 / 句末空白集 / 不丢字不变式）
    repair_legacy_newlines.py  存量修复：清掉旧段里的换行并重合成音频
```

## 8. 打包成 exe（可选）

```bash
pip install pyinstaller
pyinstaller --noconsole --onedir --name AudioBookLocalTtsTool run.py
```

注意：`moss_bridge.py` 会被 PyInstaller 收进 `_MEIPASS`，工具已按
`Path(__file__).with_name(...)` 定位它；但**MOSS 工程本身不会被打包** ——
它太大，而且需要自己的 Python 环境，运行时在界面里填路径即可。
