package com.audiobook.server.controller;

import com.audiobook.server.model.StoreBook;
import com.audiobook.server.service.BookStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 书城接口：书籍浏览、分类、搜索、详情、封面、下载。
 * 与 /api/tts 一样受 JWT 保护（SecurityConfig 默认要求认证）。
 */
@RestController
@RequestMapping("/api/store")
public class BookStoreController {

    private static final Logger log = LoggerFactory.getLogger(BookStoreController.class);

    private final BookStoreService bookStoreService;

    public BookStoreController(BookStoreService bookStoreService) {
        this.bookStoreService = bookStoreService;
    }

    /**
     * 书城首页数据：热门榜 + 新书榜 + 分类列表 + 标签栏。
     * 一个接口拿全，减少客户端首页请求数。
     */
    @GetMapping("/home")
    public ResponseEntity<Map<String, Object>> home() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("hotBooks", bookStoreService.getHotBooks());
            response.put("newBooks", bookStoreService.getNewBooks());
            response.put("editorPicks", bookStoreService.getEditorPicks());
            response.put("categories", buildCategoryList());
            // 标签栏与分类栏并列：首页一次拿全，客户端不必再多打一个请求
            response.put("tags", buildTagList());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取书城首页数据失败", e);
            response.put("success", false);
            response.put("message", "服务器错误，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 书籍列表：支持分类筛选 + 标签筛选 + 关键词搜索 + 可选分页。
     *
     * @param category 可选，分类名
     * @param tag      可选，标签名（精确匹配；一本书可属多个标签）
     * @param keyword  可选，书名/作者模糊搜索
     * @param page     可选，从 1 开始；不传返回全量（兼容旧客户端）
     * @param size     可选，每页条数
     */
    @GetMapping("/books")
    public ResponseEntity<Map<String, Object>> listBooks(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        Map<String, Object> response = new HashMap<>();
        try {
            BookStoreService.BookPage result =
                    bookStoreService.listBooksPaged(category, keyword, tag, page, size);
            response.put("success", true);
            response.put("books", result.books());
            // total 恒为过滤后的总条数（若取当页条数，分页后前端无法算总页数）
            response.put("total", result.total());
            if (page != null && size != null) {
                response.put("page", page);
                response.put("size", size);
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取书城书籍列表失败", e);
            response.put("success", false);
            response.put("message", "服务器错误，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** 标签列表（标签名 + 在售书籍数），书城标签栏的数据源 */
    @GetMapping("/tags")
    public ResponseEntity<Map<String, Object>> tags() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("tags", buildTagList());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取书城标签失败", e);
            response.put("success", false);
            response.put("message", "服务器错误，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** 分类列表（含书籍数量） */
    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> categories() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("categories", buildCategoryList());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取书城分类失败", e);
            response.put("success", false);
            response.put("message", "服务器错误，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** 编辑推荐书单（editor_pick=true 且在售，按创建时间倒序） */
    @GetMapping("/editor-picks")
    public ResponseEntity<Map<String, Object>> editorPicks() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("books", bookStoreService.getEditorPicks());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取编辑推荐书单失败", e);
            response.put("success", false);
            response.put("message", "服务器错误，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** 书籍详情 */
    @GetMapping("/book/{id}")
    public ResponseEntity<Map<String, Object>> bookDetail(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        // 详情页要展示标签，走带 tags 的查询（其余封面/下载链路仍用轻量的 getBook）
        Optional<StoreBook> book = bookStoreService.getBookWithTags(id);
        // 列表/分类/热榜/新书都过滤了 offshelf，详情也必须一致：历史链接/猜 ID
        // 不应看到已下架书籍，保持「下架即不可见」的语义
        if (book.isEmpty() || "offshelf".equals(book.get().getStatus())) {
            response.put("success", false);
            response.put("message", "书籍不存在");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        response.put("success", true);
        response.put("book", book.get());
        return ResponseEntity.ok(response);
    }

    /**
     * 读取书城书籍的正文（段落 + 目录），供用户端「直接打开阅读」。
     *
     * <p>与 {@code POST /api/tts/extract-content} 的区别：那个端点要求客户端先上传文件，
     * 服务端不知道书是哪一本；本端点直接按 {@code store_books.id} 在服务端定位文件，
     * 书城里的书无须先下载到本地再上传一遍。两条路径的段落切分与目录映射**共用**
     * {@code BookTextExtractor.extractBookContent}，保证阅读页段落与合成音频段落严格对齐。
     *
     * <p>响应结构与 {@code extract-content} 完全一致
     * （{@code {success, paragraphs:[{text,charOffset,paragraphIndex}], toc:[{title,paragraphIndex,pageNumber}], extension, rows?}}），
     * 前端可复用同一套阅读渲染逻辑。{@code rows} 是原文行（保留行首缩进），
     * 仅 PDF/EPUB/MOBI 有、TXT 不返回（见 {@code BookTextExtractor.splitRows}）。
     */
    @GetMapping("/book/{id}/content")
    public ResponseEntity<Map<String, Object>> bookContent(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        Optional<StoreBook> bookOpt = bookStoreService.getBook(id);
        // 与详情/封面/下载一致：下架书籍不可见，避免「列表隐藏了、直连 id 还能读」
        if (bookOpt.isEmpty() || "offshelf".equals(bookOpt.get().getStatus())) {
            response.put("success", false);
            response.put("message", "书籍不存在");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        BookStoreService.ReadingContent reading;
        try {
            reading = bookStoreService.readableContentForReading(bookOpt.get());
        } catch (Exception e) {
            log.error("书城书籍正文提取异常: bookId={}", id, e);
            response.put("success", false);
            response.put("message", "提取内容失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
        if (reading == null) {
            response.put("success", false);
            response.put("message", "书籍文件缺失或无法解析，暂不支持在线阅读");
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
        }

        BookStoreService.StoreBookContent result = reading.content();
        response.put("success", true);
        response.put("title", result.title());
        response.put("paragraphs", BookContentController.toParagraphMaps(result.content().paragraphs()));
        response.put("toc", BookContentController.toTocMaps(result.content().toc()));
        response.put("extension", result.content().extension());
        // W1：正文来源与「文件是否已变更」。前端据此提示，不再让用户以为高亮错位是 bug
        response.put("source", reading.source());
        response.put("staleFile", reading.staleFile());
        // 原文行：与 extract-content 同构（PDF/EPUB/MOBI 有、TXT 无），保持两个端点
        // 可复用同一套阅读渲染逻辑。书城书籍目前由客户端「下载文件后本地阅读」，
        // 本端点的 rows 暂无消费方（下载链路只用 paragraphs），但结构一致更不易漂移
        BookContentController.putRows(response, result.content());
        return ResponseEntity.ok(response);
    }

    /** 书籍封面（优先自定义封面，否则动态生成 PNG） */
    @GetMapping("/book/{id}/cover")
    public ResponseEntity<byte[]> bookCover(@PathVariable Long id) {
        Optional<StoreBook> book = bookStoreService.getBook(id);
        // 与详情一致：下架书籍不返回封面（否则列表隐藏了书、封面接口还能被探测）
        if (book.isEmpty() || "offshelf".equals(book.get().getStatus())) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] data = bookStoreService.getCover(book.get());
            // 自定义封面可能是 jpg/png，动态生成的一定是 PNG；统一按图片返回
            MediaType contentType = MediaType.IMAGE_PNG;
            File custom = bookStoreService.resolveCoverFile(book.get());
            if (custom != null) {
                String name = custom.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                    contentType = MediaType.IMAGE_JPEG;
                }
            }
            return ResponseEntity.ok()
                    .contentType(contentType)
                    .cacheControl(org.springframework.http.CacheControl.maxAge(24, java.util.concurrent.TimeUnit.HOURS))
                    .body(data);
        } catch (IOException e) {
            log.error("获取封面失败: bookId={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** 下载书籍文件（支持 HTTP Range 断点续传：客户端可带 Range: bytes=start- 续传） */
    @GetMapping("/book/{id}/download")
    public ResponseEntity<?> downloadBook(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        Optional<StoreBook> bookOpt = bookStoreService.getBook(id);
        if (bookOpt.isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "书籍不存在");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        StoreBook book = bookOpt.get();
        if ("offshelf".equals(book.getStatus())) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "书籍已下架");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        File file = bookStoreService.resolveBookFile(book);
        if (file == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "书籍文件已下架或缺失");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        log.info("书城书籍下载: [{}] {} (第 {} 次)", book.getCategory(), book.getTitle(), book.getDownloadCount());

        String encodedFileName = URLEncoder.encode(book.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        long fileLength = file.length();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + encodedFileName);
        // 声明支持按字节续传，客户端据此可在断点后续传
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");

        String range = request.getHeader(HttpHeaders.RANGE);
        if (range != null && range.startsWith("bytes=")) {
            try {
                String[] parts = range.substring("bytes=".length()).split("-");
                long start = Long.parseLong(parts[0]);
                long end = (parts.length > 1 && !parts[1].isEmpty())
                        ? Long.parseLong(parts[1]) : fileLength - 1;
                if (start < 0 || end >= fileLength || start > end) {
                    // 非法 Range：返回 416，让客户端从头重下
                    headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + fileLength);
                    return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                            .headers(headers).build();
                }
                long contentLength = end - start + 1;
                final long rafEnd = end;
                final java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
                try {
                    raf.seek(start);
                } catch (java.io.IOException seekError) {
                    // seek 失败必须显式关闭再上抛：外层 catch 会降级为整文件下载，
                    // 若不关就漏掉一个文件句柄，异常 Range 请求反复触发即可持续累积。
                    raf.close();
                    throw seekError;
                }
                java.io.InputStream body = new java.io.InputStream() {
                    private long pos = start;
                    @Override
                    public int read() throws java.io.IOException {
                        if (pos > rafEnd) return -1;
                        int b = raf.read();
                        if (b != -1) pos++;
                        return b;
                    }
                    @Override
                    public int read(byte[] b, int off, int len) throws java.io.IOException {
                        if (pos > rafEnd) return -1;
                        int toRead = (int) Math.min(len, rafEnd - pos + 1);
                        int n = raf.read(b, off, toRead);
                        if (n != -1) pos += n;
                        return n;
                    }
                    @Override
                    public void close() throws java.io.IOException { raf.close(); }
                };
                headers.setContentLength(contentLength);
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileLength);
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers)
                        .body(new org.springframework.core.io.InputStreamResource(body));
            } catch (NumberFormatException | java.io.IOException e) {
                log.warn("Range 解析失败，降级为整文件下载: bookId={}, {}", id, e.getMessage());
                // 落入下方整文件下载
            }
        }

        headers.setContentLength(fileLength);
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(file));
    }

    /**
     * 下载书城书籍的合成音频（zip 包）。
     *
     * <p>书级公共资源，登录用户即可下载。书未合成音频（task_id IS NULL）或合成未完成时返回 4xx 提示。
     * 写入响应头后开始流式 zip 出，避免把整本书音频驻留堆。流式响应一旦开始即无法回退，
     * 故错误前置校验 / 成功路径都集中在 BookStoreService.streamStoreAudioZip。
     */
    @GetMapping("/book/{id}/audio/download")
    public void downloadAudio(@PathVariable Long id, HttpServletResponse response) {
        try {
            bookStoreService.streamStoreAudioZip(id, response);
        } catch (IOException e) {
            // 本分支的典型来源是「客户端中途断开」的 broken pipe（大 zip 下很常见）。
            // 此时 response 已提交、且其 OutputStream 已被 ZipOutputStream 取走，
            // getWriter() 会抛 IllegalStateException（非 IOException，不会被下面兜住），
            // 穿透出控制器白白多打一条 ERROR。所以必须与 Exception 分支一样先判 isCommitted。
            log.warn("书级音频下载流出中断: bookId={}, error={}", id, e.getMessage());
            try {
                if (!response.isCommitted()) {
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    response.getWriter().write("下载失败，请稍后重试");
                }
            } catch (IOException ignored) {
                // 客户端已断开，无法再回写——忽略
            }
        } catch (Exception e) {
            log.error("书级音频下载失败: bookId={}, error={}", id, e.getMessage(), e);
            try {
                if (!response.isCommitted()) {
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    response.getWriter().write("下载失败，请稍后重试");
                }
            } catch (IOException ignored) {
            }
        }
    }

    /** 把 JPA 查询结果 [ [category, count], ... ] 转为友好结构 */
    private List<Map<String, Object>> buildCategoryList() {
        List<Map<String, Object>> categories = new ArrayList<>();
        for (Object[] row : bookStoreService.listCategories()) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", row[0]);
            item.put("count", row[1]);
            categories.add(item);
        }
        return categories;
    }

    /**
     * 标签列表转友好结构：[{name, count}]。
     * 结构刻意与 categories 对齐（同样是 name + count），前端两栏可以用同一套渲染逻辑。
     */
    private List<Map<String, Object>> buildTagList() {
        List<Map<String, Object>> tags = new ArrayList<>();
        for (com.audiobook.server.service.StoreBookTagService.TagCount tc : bookStoreService.listTagCounts()) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", tc.name());
            item.put("count", tc.count());
            tags.add(item);
        }
        return tags;
    }
}
