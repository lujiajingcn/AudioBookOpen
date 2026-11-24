package com.audiobook.server.controller;

import com.audiobook.server.service.BookTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 电子书内容提取（非 TTS 场景：直接阅读 PDF/EPUB/MOBI）。
 * 由原 Controller.java（God Object）拆分而来，路径保持不变。
 */
@RestController
@RequestMapping("/api/tts")
public class BookContentController {

    private static final Logger log = LoggerFactory.getLogger(BookContentController.class);

    private final BookTextExtractor bookTextExtractor;
    private final TtsControllerSupport support;

    public BookContentController(BookTextExtractor bookTextExtractor, TtsControllerSupport support) {
        this.bookTextExtractor = bookTextExtractor;
        this.support = support;
    }

    /**
     * 上传电子书文件，提取全文（含段落索引）和目录。
     * 用于非 TTS 场景（直接阅读 PDF/EPUB/MOBI），返回文本段落和目录。
     */
    @PostMapping(value = "/extract-content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> extractContent(
            @RequestParam("book_file") MultipartFile bookFile) {

        Map<String, Object> response = new HashMap<>();

        try {
            if (bookFile.isEmpty()) {
                response.put("success", false);
                response.put("message", "文件不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            String filename = bookFile.getOriginalFilename();
            String extension = support.getFileExtension(filename);
            if (extension == null) {
                response.put("success", false);
                response.put("message", "无法识别文件扩展名");
                return ResponseEntity.badRequest().body(response);
            }

            if (!support.isSupportedEbookFormat(extension)) {
                response.put("success", false);
                response.put("message", "不支持的格式，支持：TXT / PDF / EPUB / MOBI / MD");
                return ResponseEntity.badRequest().body(response);
            }

            // 显式大小前置校验。multipart 容器层虽会兜底拦截，但这里
            // 主动校验能返回带明确文案的 413，并与 /upload-book、/from-store 的
            // 校验口径（同一个 maxFileSizeBytes 来源）保持完全一致
            long sizeLimit = support.maxFileSizeBytes();
            if (bookFile.getSize() > sizeLimit) {
                response.put("success", false);
                response.put("message", "文件过大（上限 " + TtsControllerSupport.formatMB(sizeLimit) + "MB），请压缩后重试");
                return ResponseEntity.status(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE).body(response);
            }

            // 保存临时文件
            java.io.File tempFile = null;
            try {
                tempFile = java.io.File.createTempFile("toc_extract_", "." + extension);
                bookFile.transferTo(tempFile);

                try {
                    // 提取正文段落 + 目录。具体编排（切段、字符偏移、目录偏移→段落下标映射）
                    // 已上收到 BookTextExtractor.extractBookContent，与
                    // GET /api/store/book/{id}/content 共用同一实现，避免两处口径漂移
                    BookTextExtractor.BookContent content =
                            bookTextExtractor.extractBookContent(tempFile, extension);

                    response.put("success", true);
                    response.put("paragraphs", toParagraphMaps(content.paragraphs()));
                    response.put("toc", toTocMaps(content.toc()));
                    response.put("extension", content.extension());
                    putRows(response, content);
                    return ResponseEntity.ok(response);

                } finally {
                    if (tempFile != null && tempFile.exists()) tempFile.delete();
                }

            } catch (Exception e) {
                if (tempFile != null && tempFile.exists()) tempFile.delete();
                throw e;
            }

        } catch (Exception e) {
            log.error("提取内容失败: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "提取内容失败，请稍后重试");
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** 段落 record → JSON 结构（字段名与历史响应保持一致：text / charOffset / paragraphIndex） */
    static java.util.List<java.util.Map<String, Object>> toParagraphMaps(
            List<BookTextExtractor.Paragraph> paragraphs) {
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>(paragraphs.size());
        for (BookTextExtractor.Paragraph p : paragraphs) {
            java.util.Map<String, Object> m = new HashMap<>(4);
            m.put("text", p.text());
            m.put("charOffset", p.charOffset());
            m.put("paragraphIndex", p.paragraphIndex());
            list.add(m);
        }
        return list;
    }

    /** 目录 record → JSON 结构（字段名与历史响应保持一致：title / paragraphIndex / pageNumber） */
    static java.util.List<java.util.Map<String, Object>> toTocMaps(
            List<BookTextExtractor.TocEntry> toc) {
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>(toc.size());
        for (BookTextExtractor.TocEntry t : toc) {
            java.util.Map<String, Object> m = new HashMap<>(4);
            m.put("title", t.title);
            m.put("paragraphIndex", t.charOffset);
            m.put("pageNumber", t.pageNumber);
            list.add(m);
        }
        return list;
    }

    /**
     * 原文行（{@code rows}）与其「行 → 段」下标表写入响应：客户端阅读页据此显示带缩进的原文，
     * 并知道自己点的那一行对应哪一段音频。
     *
     * <p>为 {@code null}/空时**不写入响应**（TXT 与空正文），老客户端与 TXT 路径的响应结构
     * 因此保持原样 —— 新字段只增不改，不必做版本协商；不认识新字段的客户端（如 Android）
     * 继续只读 {@code rows}，行为不变。
     *
     * <p>{@code rowParagraphStart} / {@code rowParagraphEnd} 与 {@code rows} 等长平行，
     * 分别是第 i 行**首/末实字**所在的段落下标。**它们是行与段唯一的对齐依据**：
     * 客户端只有行文本，既不知道行首在原文里的下标、也不知道段落起点，自己推必然要把
     * 定位算法重写一遍，而且错了没有任何东西能发现（静默错位）。缺了它，阅读页就只能显示
     * 片段（= 切给引擎的句子），这正是本次要修的问题。
     *
     * <p>三个数组必须在同一次响应里给出，长度不等时宁可不给下标表 ——
     * 错位的下标比没有下标更糟（会把高亮/跳转引到无关位置）。
     */
    static void putRows(Map<String, Object> response, BookTextExtractor.BookContent content) {
        List<String> rows = content.rows();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        response.put("rows", rows);
        List<Integer> start = content.rowParagraphStart();
        List<Integer> end = content.rowParagraphEnd();
        if (start != null && end != null && start.size() == rows.size() && end.size() == rows.size()) {
            response.put("rowParagraphStart", start);
            response.put("rowParagraphEnd", end);
            // 每行内各段字符区间：与 rows 等长平行，客户端子高亮从音频真实起点起（不盖上一句残字）
            List<List<int[]>> segs = content.rowParagraphSegments();
            if (segs != null && segs.size() == rows.size()) {
                response.put("rowParagraphSegments", segs);
            }
        }
    }
}