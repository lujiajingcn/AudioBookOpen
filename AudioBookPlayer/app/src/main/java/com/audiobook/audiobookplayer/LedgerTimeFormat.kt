package com.audiobook.audiobookplayer

/**
 * 积分明细里的时间显示：把服务端返回的 ISO 串收成「yyyy-MM-dd HH:mm」。
 *
 * ## 为什么不用 `java.time` / `SimpleDateFormat`
 * · `java.time` 要 API 26，本工程 minSdk 24，用不了（开 desugaring 是另一项工程）；
 * · `SimpleDateFormat` 对 `2026-11-02T11:46:51.239836` 这种「带 T、带微秒、无时区」
 *   的串要定制 pattern，而且微秒位数（3 位 / 6 位 / 0 位）一变就抛 ParseException。
 *
 * 服务端的格式是固定的（`LocalDateTime` 默认 toString），字符串截取反而是最稳的：
 * 不解析日期语义、不做时区换算，也就不可能因为时区把「今天」显示成「昨天」。
 *
 * ## 输入的三种形态都要能接住
 * 1. `2026-11-02T11:46:51.239836` —— 正常形态（服务端 `Post`/`Credit` 实体的
 *    `LocalDateTime` 序列化结果）；
 * 2. `2026-11-02 11:46:51` —— 老数据 / 别处已经格式化过一次的串；
 * 3. 任何不符合前两者的串 —— 原样返回，宁可显示得丑，也不能显示成空白。
 *    （明细页上一条没有时间的流水比一条格式怪异的流水更让人怀疑数据丢了。）
 */
internal fun formatLedgerTime(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return ""
    // 只认「日期 [T|空格] 时间」这一种形状；长度不足 16 的（如只有日期）原样给出
    val sep = text.indexOfFirst { it == 'T' || it == ' ' }
    if (sep <= 0 || text.length < sep + 6) return text
    // 日期段必须是 yyyy-MM-dd（10 个字符），否则不是我们认识的格式
    if (sep != 10) return text
    val day = text.substring(0, sep)
    val hhmm = text.substring(sep + 1, sep + 6) // HH:mm
    // 时间段的形状再校验一次：HH:mm —— "11:46" 的第 3 个字符必须是冒号
    if (hhmm.length != 5 || hhmm[2] != ':') return text
    return "$day $hhmm"
}
