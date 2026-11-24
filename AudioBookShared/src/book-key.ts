/**
 * 跨端书名身份键（bookKey）与阅读进度的公共口径。
 *
 * ## 为什么需要它
 *
 * 「同一本书」在不同终端算出来的标识必须**逐字符相同**，否则跨端进度无法互通：
 * 服务端曾以 `task_id` 作进度身份，但它有两个硬伤 ——
 * ① 无音频的书（`store_books.task_id IS NULL`）**根本没有 task_id**，进度存不下来；
 * ② 书城书的音频常由管理员合成，普通用户拿它调私有任务端点会因归属校验 403。
 *
 * 本工程已有验证过的跨端身份口径 —— **有声覆盖图**（`user_audio_coverage`）用的就是它：
 * 服务端 `BookKeys`、Android `CoverageCodec.bookKey`、PC `coverage.book_key` 三端逐字符一致。
 * 阅读进度改用同一套口径，三端才能认出「这是同一本书」。
 *
 * ## 三种前缀
 *
 * - `s:{storeBookId}` —— 书城条目（**与是否合成无关**，纯阅读的书也用它）
 * - `t:{serverTaskId}` —— 在线合成任务
 * - `n:{normalizeName(书名)}` —— 本地导入书（文件名/书名归一化）
 *
 * 优先级：`s:` > `t:` > `n:`。
 */

/** 书城条目前缀 */
export const PREFIX_STORE = 's:'
/** 在线合成任务前缀 */
export const PREFIX_TASK = 't:'
/** 本地书名前缀 */
export const PREFIX_NAME = 'n:'

/**
 * 已知电子书扩展名（用于 `n:` 分支剥一次）。
 *
 * 顺序必须与 Java `BookKeys.EXTENSIONS`、Python `coverage._EXTENSIONS` **完全一致**
 * （含顺序）：按长度倒序，避免 `.doc` 遮蔽 `.docx`。
 */
export const BOOK_EXTENSIONS: readonly string[] = [
  '.azw3',
  '.docx',
  '.djvu',
  '.epub',
  '.mobi',
  '.pdf',
  '.azw',
  '.doc',
  '.fb2',
  '.txt',
]

/**
 * 「书键空白」的 code point 集合。
 *
 * 与 Java `BookKeys.isBookWhitespace`、Python `coverage._BOOK_WS_CODEPOINTS`
 * **逐点一致**（不多不少）：ASCII 空白 + NBSP + OGHAM 空格 + U+2000..U+200A +
 * 行/段分隔符 + 窄 NBSP + 中数学空格 + 全角空格。
 *
 * ⚠️ 刻意**不含** U+FEFF（BOM）与 U+200B（零宽空格）—— 参考实现没有它们，
 * 这里多加就会让三端算出不同的键。
 */
const BOOK_WS_CODEPOINTS: readonly number[] = [
  0x20,
  0x09, 0x0a, 0x0b, 0x0c, 0x0d,
  0xa0,
  0x1680,
  0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a,
  0x2028,
  0x2029,
  0x202f,
  0x205f,
  0x3000,
]

const BOOK_WS_SET = new Set(BOOK_WS_CODEPOINTS)

/** 该 code point 是否是「书键空白」。 */
function isBookWs(cp: number): boolean {
  return BOOK_WS_SET.has(cp)
}

/**
 * 归一化书名（不含 `n:` 前缀）。
 *
 * 规则（顺序固定，禁止增删步骤）—— 必须与服务端 `BookKeys.normalizeName`、
 * Android `CoverageCodec.normalizeName`、PC `coverage.normalize_name` 逐字符一致：
 * 1. 去两端「书键空白」；
 * 2. Unicode 小写化（与 Python `str.lower()` / Java `toLowerCase(Locale.ROOT)` 语义一致）；
 * 3. 尾部匹配已知扩展名则剥掉一次；
 * 4. 压缩内部连续空白为单个 ASCII 空格，并再次去两端空白。
 */
export function normalizeName(name: string | null | undefined): string {
  if (name == null) return ''
  let s = String(name)
  // ① 去两端空白
  s = trimBookWhitespace(s)
  if (!s) return ''
  // ② Unicode 小写化
  s = s.toLowerCase()
  // ③ 剥一次已知扩展名（长度严格大于扩展名，避免把 "a.pdf" 剥成空）
  for (const ext of BOOK_EXTENSIONS) {
    if (s.length > ext.length && s.endsWith(ext)) {
      s = s.slice(0, -ext.length)
      break
    }
  }
  // ④ 压缩内部连续空白 + 再次去两端
  s = compressWs(s)
  return s
}

/** 去两端「书键空白」。 */
function trimBookWhitespace(s: string): string {
  let a = 0
  let b = s.length
  while (a < b && isBookWs(s.codePointAt(a)!)) a++
  while (b > a && isBookWs(s.codePointAt(b - 1)!)) b--
  return a === 0 && b === s.length ? s : s.slice(a, b)
}

/** 把连续「书键空白」压成单个 ASCII 空格（0x20），并去两端空白。 */
function compressWs(s: string): string {
  let out = ''
  let inWsRun = false
  for (const ch of s) {
    if (isBookWs(ch.codePointAt(0)!)) {
      inWsRun = true
      continue
    }
    if (inWsRun) {
      out += ' '
      inWsRun = false
    }
    out += ch
  }
  if (inWsRun) out += ' '
  return trimBookWhitespace(out)
}

/**
 * 生成跨端书名身份键。优先级：书城 `s:` > 任务 `t:` > 本地名 `n:`。
 *
 * @param storeBookId   书城条目 id（0/空 表示没有）
 * @param serverTaskId  在线合成任务 id（0/空 表示没有）
 * @param displayName   书名（`n:` 分支用）
 * @returns 身份键；三个来源都无效时返回空串（调用方应跳过）
 */
export function bookKey(
  storeBookId?: number | null,
  serverTaskId?: number | null,
  displayName?: string | null,
): string {
  const sbid = posInt(storeBookId)
  if (sbid) return `${PREFIX_STORE}${sbid}`
  const stid = posInt(serverTaskId)
  if (stid) return `${PREFIX_TASK}${stid}`
  const name = normalizeName(displayName)
  if (name) return `${PREFIX_NAME}${name}`
  return ''
}

function posInt(v: unknown): number {
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0
}
