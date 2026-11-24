# A-02 配套：release 开启 R8 后的保留规则。
# Retrofit / OkHttp / Okio / Coil / security-crypto 各自携带 consumer rules，
# 会自动随 AAR 合入，此处只需处理本工程自身的反射点。

# Gson：保留注解本身（ReflectiveTypeAdapterFactory 会读取）
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# ============================================================================
# 【必须保留】R8 full mode + Retrofit 2.9.0：泛型签名要显式 keep，否则 release 包
# 全部接口在「发出请求之前」就崩，服务端零日志。
#
# 机理：
#   1) AGP 8.x 默认开启 R8 full mode（本工程 gradle.properties 未显式关闭）；
#   2) full mode 会「剥掉未被 keep 的类的泛型签名」；
#   3) Retrofit 2.9.0 自带的 consumer rules 生成于该行为之前 —— 只写了
#      `-keepattributes Signature` 与 Retrofit 接口的 keep，**没有** keep
#      retrofit2.Response / kotlin.coroutines.Continuation 这两个「被签名引用」的类；
#      Retrofit 2.10.0 才补上下面这三条；
#   4) 于是 suspend 方法末位参数的签名
#        Continuation<? super Response<AuthResponse>>
#      退化成被擦除的裸 `Continuation`；
#   5) Retrofit 解析方法时执行
#        Utils.getParameterLowerBound(0, (ParameterizedType) continuationType)
#      直接抛 ClassCastException。
#
# 线上实证（2026-09-18，Redmi K30 Pro / release APK versionCode=1）：
#   E/AuthManager: 登录异常: java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType
#     java.lang.ClassCastException: java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType
#       at (Retrofit 内部方法解析) → at java.lang.reflect.Proxy.invoke → at $Proxy2.n(...)
#   特征：点「登录」瞬间报错、无转圈等待、服务端 access.log 一条请求都没有。
#   debug 包（isMinifyEnabled = false）不受影响 —— 典型的「只在 release 包复现」缺陷。
#
# 长期方案：升级 retrofit 到 2.10.0+（其 consumer rules 已内置这三条），
# 届时可删掉本段。升级前请勿删除。
# ============================================================================
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ============================================================================
# 【必须保留】Gson 2.8.5 + R8 full mode：TypeToken 匿名子类的泛型超类签名。
#
# 本工程 Gson 由 retrofit converter-gson:2.9.0 传递引入，版本是 **2.8.5**，
# 其 consumer rules 只写了 -keepattributes Signature 与 TypeAdapterFactory 等，
# **没有** `-keep class * extends com.google.gson.reflect.TypeToken`
# （那条是 Gson 2.10+ 才补上的）。
#
# 后果：`object : TypeToken<List<X>>() {}.type` 这种写法在 release 包里，
# 匿名子类的 Signature 属性被 full mode 剥离，运行时 TypeToken 取不到 type
# parameter，直接抛 IllegalArgumentException: Missing type parameter.
#
# 线上实证（2026-09-19，release APK versionCode=2，模拟器复现）：
#   E/BookStoreVM: 解析书籍列表失败: Missing type parameter.
#   E/BookStoreVM: 解析分类列表失败: Missing type parameter.
#   表现：书城页「暂无书籍」、分类 chip 只剩「推荐/全部」，
#         而服务端 /api/store/home 返回 200 + 8 本书，网络层完全正常。
#   debug 包（isMinifyEnabled=false）不受影响。
#
# 代码层已改用 TypeToken.getParameterized（不依赖签名反射）作为根治手段，
# 本段是兜底：防止后续再有人写出匿名 TypeToken 子类。
# 长期方案：升级 Gson 到 2.10+，届时可删掉本段。
# ============================================================================
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# 缺陷 A-02 关键点：本项目大部分 model 类（AuthResponse / Task / CreditModels 等）
# 未使用 @SerializedName，Gson 完全依赖字段名反射反序列化。
# R8 一旦重命名这些字段，所有 API 响应将解析为默认值且无任何报错，极难排查。
# 整体保留 model 包（字段 + 无参/全参构造）。
-keep class com.audiobook.audiobookplayer.model.** { *; }

# Kotlin 反射元数据（Gson 处理 data class 默认参数时需要）
-keep class kotlin.Metadata { *; }

# ZXing 二维码：代码中通过反射/枚举遍历的部分
-keep class com.google.zxing.** { *; }

# 枚举的 values()/valueOf() 反射调用保护
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================================
# 端侧 MOSS-TTS-Nano（ONNX Runtime）相关保留。
#
# ai.onnxruntime 的 AAR 自带完整的 consumer rules（含 JNI 回调类），
# 通常无需干预；这里只显式保留 **JNI 边界上的类**：ONNX Runtime 的 native 层
# 会按类名/方法名回调这些类型，一旦被 R8 重命名就会在运行时抛
# UnsatisfiedLinkError 或直接崩溃，且只在 release 包复现（与上面 A-02 同类陷阱）。
# ============================================================================
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# 端侧推理用到的 JSON 元数据解析（org.json 为 Android 平台自带，无需保留规则），
# 但本工程端侧模块的数据类会被 Gson/反射之外的代码直接实例化，保留其成员以便排障。
-keep class com.audiobook.audiobookplayer.moss.** { *; }