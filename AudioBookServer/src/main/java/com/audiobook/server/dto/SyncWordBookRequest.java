package com.audiobook.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 生词本批量同步请求体（POST /api/word-book/sync）。
 *
 * <p>与 {@link SyncFavoritesRequest} 语义一致：客户端把本地（离线 / 升级前 / 换设备）
 * 已存在但未上云的词条一次性上报，服务端按 (userId, sw) 与已有记录合并去重，
 * 返回合并后的全量列表 + imported 增量。这样"跨端收藏同步"和"首次上云迁移"
 * 共用一套骨架。
 *
 * <p>护栏同 favorites：单条 translations 上限 20000 字、条数上限 2000、
 * 总字符数上限 200 万。真实场景生词本一般几百到一两千条，够用；上限只用于
 * 防御恶意或错乱的批量提交。
 */
public class SyncWordBookRequest {

    private static final long MAX_TOTAL_TRANSLATIONS_CHARS = 2_000_000L;

    @NotNull(message = "words 不能为空")
    @Size(max = 2000, message = "单次同步最多 2000 条")
    @Valid
    private List<WordBookItemRequest> words;

    @AssertTrue(message = "单次同步的释义总量过大，请分批上报")
    public boolean isTotalTranslationsWithinLimit() {
        if (words == null) {
            return true;
        }
        long total = 0;
        for (WordBookItemRequest w : words) {
            if (w == null || w.getTranslations() == null) {
                continue;
            }
            total += w.getTranslations().length();
            if (total > MAX_TOTAL_TRANSLATIONS_CHARS) {
                return false;
            }
        }
        return true;
    }

    public List<WordBookItemRequest> getWords() {
        return words;
    }

    public void setWords(List<WordBookItemRequest> words) {
        this.words = words;
    }
}
