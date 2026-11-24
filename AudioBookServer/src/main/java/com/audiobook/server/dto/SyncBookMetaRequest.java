package com.audiobook.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /api/book-meta/sync 请求体：客户端上报本机全部书的元数据 + 各自版本。
 *
 * <p>护栏 5000 条，与 {@code SyncAudioCoverageRequest} 同量级（书架量级通常百本以内）。
 * 服务端逐 key LWW 合并后返回权威全量；客户端按 bookKey 匹配本地书行后只 UPDATE
 * sort_order / last_read_at 两列，绝不 INSERT/DELETE 书籍行（书籍真源仍是书架同步）。
 */
public class SyncBookMetaRequest {

    @NotNull(message = "items 不能为空")
    @Size(max = 5000, message = "单次同步最多 5000 本书")
    @Valid
    private List<BookMetaItemRequest> items;

    public List<BookMetaItemRequest> getItems() {
        return items;
    }

    public void setItems(List<BookMetaItemRequest> items) {
        this.items = items;
    }
}
