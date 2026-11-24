package com.audiobook.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 参考音频主题的输入校验与提示文案规则测试。
 *
 * <p>这几条都是「直接甩到管理端弹窗上」的文案/规则，出错代价不高但很烦人：
 * 空白名若被静默接受，会建出一个没法在列表里区分的空主题；
 * 提示文案若不带名字，管理员根本不知道撞的是哪个主题。
 * 因此把规则抽成静态方法，用纯单测钉住，不依赖 Spring 容器。
 */
class ReferenceAudioTopicRulesTest {

    @Test
    @DisplayName("主题名：null、空串、纯空白一律拒绝")
    void blankNameRejected() {
        assertThatThrownBy(() -> AdminService.normalizeTopicName(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("主题名称不能为空");
        assertThatThrownBy(() -> AdminService.normalizeTopicName(""))
                .isInstanceOf(IllegalArgumentException.class);
        // 管理员从网页粘贴主题名时前后常带空格/换行，纯空白必须挡住
        assertThatThrownBy(() -> AdminService.normalizeTopicName("   \n\t "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("主题名：裁掉首尾空白，但保留名称内部的空格")
    void nameTrimmedOnly() {
        assertThat(AdminService.normalizeTopicName("  电视剧雍正王朝  ")).isEqualTo("电视剧雍正王朝");
        // 「雍正王朝 2024 精选」中间的空格是有意义的分隔，不能一并压掉
        assertThat(AdminService.normalizeTopicName(" 雍正王朝 2024 精选 ")).isEqualTo("雍正王朝 2024 精选");
        // 仅含不可见字符（如全角空格）同样视为空白
        assertThatThrownBy(() -> AdminService.normalizeTopicName("　"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("重名提示必须带上管理员输入的主题名")
    void duplicateMessageCarriesName() {
        String msg = AdminService.duplicateTopicMessage("电视剧雍正王朝");
        assertThat(msg).isEqualTo("主题名称已存在：电视剧雍正王朝");
        // 不含名字的话管理员无法判断撞的是哪个主题，提示就等于没说
        assertThat(msg).contains("电视剧雍正王朝");
    }

    @Test
    @DisplayName("非空主题拒删：文案要说明主题名与剩余数量，指明下一步动作")
    void nonEmptyDeleteMessageGuidesNextStep() {
        String msg = AdminService.nonEmptyTopicDeleteMessage("电视剧雍正王朝", 12L);
        assertThat(msg)
                .contains("电视剧雍正王朝")
                .contains("12")
                .contains("移出");
        // 数量为 1 也要如实显示，不能因为「只有一个」就省略成含糊文案
        assertThat(AdminService.nonEmptyTopicDeleteMessage("主题A", 1L)).contains("1 个参考音频");
    }
}
