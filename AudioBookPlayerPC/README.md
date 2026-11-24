# AudioBookPlayerPC —— 桌面有声书阅读器

对标 Android AudioBookPlayer 全部功能的 PySide6 桌面版。

## 快速启动

```bash
cd AudioBookPlayerPC
pip install -r requirements.txt
python run.py
# 或双击 start.bat
```

## 功能清单

| 模块 | 说明 |
|---|---|
| 书架 | 本地书籍管理、导入(TXT/MD/EPUB/PDF/MOBI/AZW)、拖放排序、合成任务状态行 |
| 书城 | 浏览/搜索/分类、在线阅读(服务端正文)、按需下载、一键合成 |
| 阅读器 | 文本展示、句级高亮、点哪句播哪句、目录跳转、字号调整、书内搜索、点读查词(英/中)、划词收藏、进度记忆 |
| 在线合成 | 上传书→服务端 TTS 任务→SSE 实时进度→逐段下载→入库 |
| 本机合成 | MOSS-TTS-Nano 端侧推理(ONNX)、参考音频克隆、内置音色、断点续跑 |
| 音频播放 | 逐段连播、暂停/继续/上下段、倍速、音量、睡眠定时 |
| 发现社区 | 帖子列表/详情/评论/收藏/举报、发帖(带图)、参考音频榜 |
| 积分支付 | 余额/流水/套餐/下单(支付宝/微信/Mock) |
| 参考音频 | 上传/管理/试听/官方共享库/克隆 |
| 词典 | ECDICT 离线英文词典、点读查词、单词本 |
| 游客模式 | 不登录可浏览书城/社区/在线阅读，写操作按需登录 |
| 夜间模式 | 全局暗色主题 |
| 版本检查 | 调用 /api/app/version/latest 检查更新 |

## 技术栈

- Python 3.10+ / PySide6 (Qt 6)
- requests + sseclient-py (HTTP/SSE)
- pygame.mixer (音频播放)
- pypdf / ebooklib (PDF/EPUB 解析)
- SQLite (本地数据)
- MOSS-TTS-Nano via 子进程 JSON 行协议 (本机合成)

## 项目结构

```
AudioBookPlayerPC/
├── run.py                    入口
├── start.bat                 双击启动
├── requirements.txt
├── assets/
│   └── icons/                应用图标（各尺寸 PNG + app.ico）
├── tools/
│   ├── make_icons.py         由 Android 端图标素材渲染多尺寸 PNG / ICO
│   └── check_icons.py        图标体检：尺寸对比图 + ICO 结构校验
├── app/
│   ├── main_window.py        主窗口（导航宿主）
│   ├── core/
│   │   ├── config.py         全局配置
│   │   ├── api_client.py     HTTP API 客户端
│   │   ├── auth.py           认证/游客管理
│   │   ├── sse.py            SSE 实时进度
│   │   ├── playback.py       音频播放引擎
│   │   ├── tts_online.py     在线合成服务
│   │   ├── tts_local.py      本机合成服务
│   │   ├── book_service.py   书籍管理编排
│   │   ├── dictionary.py     离线词典
│   │   └── update_checker.py 版本检查
│   ├── db/
│   │   └── database.py       SQLite 本地库
│   ├── models/
│   │   └── models.py         数据模型
│   ├── parser/
│   │   ├── segmenter.py      切句（与服务端同口径）
│   │   ├── booktext.py       正文提取
│   │   ├── segment_map.py    原文行↔音频段映射
│   │   └── local_parser.py   统一解析入口
│   └── ui/
│       ├── theme.py          主题/夜间模式
│       ├── icons.py          应用图标加载（窗口/任务栏/品牌位）
│       ├── pages/            功能页面
│       ├── dialogs/          弹窗
│       └── widgets/          可复用控件
```

## 应用图标

桌面端图标**与 Android 端 AudioBookPlayer 完全同源**——不是另画一套，
而是用同一份素材（棕色皮质头戴耳机 + 金色声波 + 打开的书 + 金色枫叶）生成。

素材真源在 Android 模块（桌面端不重复存二进制，避免两份素材漂移）：

```
AudioBookPlayer/app/src/main/res/mipmap-xxxhdpi/
├── ic_launcher_foreground.png   前景层：画面收在中央 62%，即 268×268 白底卡片
└── ic_launcher_background.png   背景层：暖奶油竖向渐变（自适应图标用）
```

`tools/make_icons.py` 取前景层中央那张 268×268 卡片作母版——它与传统图标
`ic_launcher.png` 逐像素同源（平均色差 4.5），且分辨率更高（268 > 192），
再套圆角遮罩（现代启动器 / Windows 11 任务栏都是圆角方形）缩放到各尺寸。

| 文件 | 用途 |
|------|------|
| `app_<size>.png` | 16/24/32/48/64/128/256 各尺寸成品 |
| `app.png` | 256px 通用图（文档/网页） |
| `app.ico` | 多尺寸 ICO（Windows 任务栏/资源管理器/打包 exe） |

16/24/32px 额外做「主体放大」裁切（只裁四周留白，主体位置不变）：
满幅画面缩到 16px 时耳机只剩几个像素，裁掉留白后轮廓明显更清晰。

重新生成（素材变更后跑一次）：

```bash
python tools/make_icons.py    # 渲染 PNG + 打包 ICO
python tools/check_icons.py   # 生成尺寸对比图到 agent_outputs/ 并校验 ICO 结构
```

素材不在兄弟模块时可用 `--android-res <目录>` 或环境变量
`AUDIOBOOK_ANDROID_RES` 指定。

图标在应用里的三处落点：窗口标题栏与任务栏（`app_icon()`）、
工具栏标题左侧与左侧导航栏顶部（`brand_icon()`）。
资源缺失时全部静默降级，不会因图标问题导致启动失败。


## 对接的后端

与 Android AudioBookPlayer 共用同一 AudioBookServer（`http://api.example.com/`），
所有接口路径、认证方式、任务状态机、TTS 引擎路由完全一致。

## 本机合成前置

需要在「设置」中配置：
- MOSS 工程路径（含 `onnx_tts_runtime.py`）
- Python 解释器（装有 onnxruntime/sentencepiece/numpy 的环境）
- 模型目录（留空=工程默认 models/）

与 `AudioBookLocalTtsTool` 使用同一个引擎和通信协议（子进程 + JSON 行）。
