# 过期/不准确注释待清理清单

> 生成日期：2026-10-30
> 范围：AudioBook 全工程（Server / Player / Web / Manager / Shared）
> 状态：**已于 2026-10-30 12:45 完成注释清理（16 处，仅改注释、未改任何代码）**。下表为清理前的定位记录，保留作追溯。

## 复核锚点（结论成立的硬事实）
- **后端 `audio_data` 列恒为 NULL**：全工程 `grep setAudioData` 仅 `AudioSegment.java:105` 一处定义，**无任何调用点** → 列永不写入，音频只落磁盘 `audio_file_path`。因此所有"兼容只存了 @Lob 的存量任务 / 回读 BLOB"注释的前提均不成立。
- **客户端 PDF 引擎已换 PDFium**：`build.gradle.kts:406` 钉死 `io.legere:pdfiumandroid:1.0.31`；同文件 `:389` 明写 "1.0.33 / 1.0.35 … metadata is 2.2.0 … 编译失败"。故任何"PDFBox-Android 2.0.27"或"PDFium 1.0.35"的陈旧描述均错误。
- `PdfPreviewParser.kt` 自身注释准确（正确写 PDFium + `getPageCount()` 函数用法），**无过期**，勿动。

---

## 高优先级（确凿过期，建议优先清理）

### A. 后端 `audio_data` BLOB 回退注释（根因：列永为 NULL）
| # | 文件:行 | 当前过期注释（要点） | 建议改法 |
|---|---------|---------------------|----------|
| A1 | `AudioBookServer/.../service/VoiceCloningTtsService.java:1970` | "整本书音频可能落在 `audio_data` 里，实体查询会把全书音频拉进堆 OOM" | 删除"BLOB/OOM"前提；改为"用元数据投影避免把无关大字段带入堆"（不提 audio_data 可能存数据） |
| A2 | `AudioBookServer/.../service/BookStoreService.java:734-735` | "兼容只存了 @Lob 的存量任务，少 BLOB 会打空 zip" | 删除 BLOB 兼容说明，改为：音频只从磁盘文件读取，缺失即视为未完成（不再有"两级来源"） |
| A3 | `AudioBookServer/.../repository/AudioSegmentRepository.java:96-98` | "之前用实体查询会把整本书音频 BLOB 一次性加载进堆，大书 OOM" | 保留"用投影取必要列"的动机，删除"音频 BLOB 进堆"描述 |
| A4 | `AudioBookServer/.../repository/AudioSegmentRepository.java:128` | "仅当磁盘文件缺失（旧数据只有 BLOB）时才真正加载音频字节" | 改为"磁盘缺失时尝试读取（当前恒为空，实际仅用于触发缺失报错）"；或直接删除该查询（见下方死代码提示） |
| A5 | `AudioBookServer/.../controller/TtsDownloadController.java:96` | "磁盘缺失时回读 BLOB（兼容只存了 @Lob 的旧数据）" | 改为"磁盘缺失时尝试回读（当前恒为空，仅作兜底报错）" |
| A6 | `AudioBookServer/.../controller/TtsDownloadController.java:110` | "兼容只存了 BLOB 的旧数据：真正下载时才单独查 audio_data" | 同上 |
| A7 | `AudioBookServer/.../controller/TtsDownloadController.java:136`(Javadoc) | "磁盘缺失时才按段单独回读 BLOB" | 删除"回读 BLOB"，改为"磁盘缺失时逐段报错" |

> 死代码提示（与注释一并评估）：`AudioSegment.audioData` 字段、`findAudioDataById` 及 `TtsDownloadController`/`BookStoreService`/`StoreReadingController`(`:249/:262`) 中的调用点均为永远取不到值的回退链路，建议清理注释时一并评估移除。

### B. 客户端 PDF 引擎注释（已换 PDFium 1.0.31）
| # | 文件:**实际行** | 当前过期注释（要点） | 建议改法 |
|---|---------------|---------------------|----------|
| B1 | `AudioBookPlayer/.../parser/MobiParser.kt:20-23` | "Android 上只有 PDFBox-Android 2.0.27，PDF 保留服务端解析、本地只做结果缓存" | 改为"PDF 由 `PdfPreviewParser` 本地 PDFium 降级预览，服务端权威结果后覆盖" |
| B2 | `AudioBookPlayer/.../parser/LocalEbookParser.kt:20` | "降级预览（PDFBox-Android 2.0.27，比服务端 3.0.2 少字）" | 改为"降级预览用 PDFium 1.0.31，与服务端字符级差异为 0" |
| B3 | `AudioBookPlayer/.../DatabaseHelper.kt:90-92` | "PDF 客户端解析不了（只有 2.0.27），只能整本上传服务端" | v6 迁移历史说明：建议改为"当时 PDF 无法本地解析，故引入 ebook_content_cache 缓存服务端结果"（加"当时"限定即可） |
| B4 | `AudioBookPlayer/.../BookViewModel.kt:1661-1662` | "PDF 依赖 PDFBox，Android 侧只有 2.0.27，只能走服务端+缓存" | 改为"PDF 走 `readPdfRowsTwoPhase`：本地 PDFium 预览先上屏，服务端权威后覆盖" |
| B5 | `AudioBookPlayer/.../parser/PdfPreviewParserTest.kt:20` | **"换引擎（PDFBox-Android 2.0.27 → PDFium 1.0.35）"** | **版本号写错**：改为 "PDFium 1.0.31"（1.0.35 在工程内会因 metadata 2.2.0 编译失败，根本不可用） |
| B6 | `AudioBookPlayer/.../BookViewModel.kt:1643` | "三种格式（TXT 本地、EPUB/MOBI 本地、PDF 服务端）失败表现一致" | 改为"PDF 本地预览+服务端权威"以反映两阶段架构 |

---

## 中/低优先级（措辞偏旧/残留，非硬错误）

| # | 文件:行 | 问题 | 建议 |
|---|---------|------|------|
| C1 | `AudioBookShared/src/http.ts:6` | 写用户端":80"，但 dev 实际 3000（生产 80 才成立） | 改为"用户端站点（生产 :80 / 开发 :3000）"或仅保留"host 相同、Cookie 不含端口" |
| C2 | `AudioBookManager/src/services/discovery.ts:28` | 注释"拉取帖子与参考音频排行"，但 Manager 列表实际走 `adminContentApi`（`Discover.vue:98/158`），`discoveryApi.listPosts/listSharedReferences` 在 Manager 未被使用（死导出） | 改为"后台审核视图：试听走 `previewSharedReference`，列表走 `adminContentApi`"；或清理死导出 |
| C3 | `AudioBookWeb/src/App.vue:13` | 写"antd v4"，实为 ant-design-vue (antdv) v4 | 改为"antdv v4"（实质结论仍成立） |

---

## 清理建议顺序
1. **B5**（版本号事实性错误，最危险，可能误导维护者尝试会破坏构建的 1.0.35）→ 立即改。
2. **A 组 7 条 + B1~B4、B6**：批量将"PDFBox-Android / BLOB 回退"旧表述替换为 PDFium / 磁盘优先现状。
3. **C 组 3 条**：顺手修正措辞。
4. 评估移除 `audio_data` 死字段与 `findAudioDataById` 回退链路（连带 A4 注释一并处理）。

---

## 复核记录（2026-10-30 二次核验）
本清单 16 条（A1–A7、B1–B6、C1–C3）均已逐行对源码复核，定位与结论**全部正确**。关键证据：
- `setAudioData` 全工程仅 `AudioSegment.java:105` 定义、无调用 → `audio_data` 恒 NULL（A 组根因成立）；`StoreReadingController.java:249/262` 同样存在该死回退。
- `build.gradle.kts:406` = `pdfiumandroid:1.0.31`；`:389` 明写 1.0.33/1.0.35 因 metadata 2.2.0 编译失败 → B5 版本号 "1.0.35" 确属写错。
- `AudioBookWeb/vite.config.ts:18` = `port: 3000` → C1 成立。
- `AudioBookManager` 内 `discoveryApi.listPosts/listSharedReferences` 仅定义未调用；列表实际走 `adminContentApi.listPosts`（`Discover.vue:158`）与 `listReferences`（`:98`）→ C2 成立。
- 本次仅修正清单内 A2/A4/B3 三条"建议改法"的措辞，未改动任何源码。
- **2026-10-30 12:40 增量复核**：并行任务提交 `ec35c50`、`b00db0a` 后（`b00db0a` 仅动 LocalTtsTool，无影响），行号发生漂移，已更新：A2 `691 → 734-735`（BookStoreService 因新增方法整体后移）、B4 `1652-1654 → 1661-1662`、B6 `1635 → 1643`（BookViewModel 新增 store_book 取正文逻辑）。**注释原文与"过期"结论均未变**；其余 13 条所在文件未被改动，行号仍有效。
- 另发现 `MossLocalSynthesizer.kt:490` 也提到 "换引擎前 PDFBox-Android 2.0.27"，但带有"换引擎前"限定、属正确历史说明，**不列为过期，勿动**。
