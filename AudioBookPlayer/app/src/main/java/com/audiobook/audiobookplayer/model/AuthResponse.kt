package com.audiobook.audiobookplayer.model

/**
 * 登录/注册响应。
 *
 * 所有字段都给了默认值，且 [message] 声明为可空——这是 Gson 的反序列化特性决定的：
 * Gson 通过 `UnsafeAllocator` 直接分配对象、**绕开主构造函数**，因此
 *  - 字段默认值不会生效（对象字段一律为 null / 基本类型为 0 值）；
 *  - **非可空字段照样可以被写入 null**。
 *
 * 也就是说 `message: String`（非可空）并不能保证非空：服务端不返回 message 时它仍是 null，
 * 调用方直接拼接或 `.isNotBlank()` 就会 NPE。声明成 `String?` 是让编译器强制调用方处理。
 *
 * 同理，凡是 `Response<T>` 反序列化出来的 model，非可空 ≠ 安全。
 */
data class AuthResponse(
    val token: String? = null,
    val username: String? = null,
    val success: Boolean = false,
    val message: String? = null,
    /** 服务端 dto/AuthResponse.java 含 isAdmin 字段；当前 Android 端无管理 UI，
     *  该字段暂无调用方，保留以对齐契约。 */
    val isAdmin: Boolean = false
)
