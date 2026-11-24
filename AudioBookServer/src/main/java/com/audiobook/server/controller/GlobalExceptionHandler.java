package com.audiobook.server.controller;

import com.audiobook.server.exception.InsufficientCreditsException;
import com.audiobook.server.exception.TtsTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理。没有 @ControllerAdvice 时，
 * 大量用户可理解的错误会以裸 500 漏到前端：
 * - 上传超过 50MB 时容器在进入 Controller 之前抛 MaxUploadSizeExceededException，
 *   Controller 里的友好校验永远执行不到；
 * - /clone、/synthesize 等 TTS 失败直接 500 且无原因；
 * - @Valid 校验失败的 MethodArgumentNotValidException 无处理方。
 *
 * 响应体统一为 {"success": false, "message": "..."}，与各 Controller 手写的格式一致，
 * 前端无需区分错误来自哪一层。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 「技术性」异常消息的前缀白名单：这些是 JDK/基础库自己抛的，文案是给开发者看的，
     * 不该原样回给客户端（v5 / N1）。
     *
     * <p><b>为什么需要它</b>：{@code handleIllegalArgument} 会把 {@code e.getMessage()} 原样回显，
     * 这是**有意设计** —— 绝大多数 {@code IllegalArgumentException} 是业务校验
     * （「书籍不存在」「积分为负」），客户端要靠这句话展示。但同一个类型也被 JDK 拿去抛
     * 「URLDecoder: Illegal hex characters…」「For input string: "abc"」这类实现细节；
     * 一旦回显，等于把内部解码/解析过程透给调用方，且对用户毫无意义。
     *
     * <p><b>为什么用前缀白名单而不是"统一通用文案"</b>：统一文案会把业务提示一起吞掉
     * （前端大量依赖它做 toast），代价远大于收益。白名单只挡已知的技术消息，
     * 漏网的技术消息由 S6 那类"在源头包装"的修法兜住 —— 两层配合。
     */
    private static final String[] TECHNICAL_IAE_PREFIXES = {
            "URLDecoder:", "URLEncoder:",          // java.net（非法 % 转义）
            "Illegal base64",                       // Base64 解码器
            "For input string:",                    // Integer/Long.valueOf
            "Character out of range",               // Character.toChars
            "Input length =",                       // CharsetDecoder
            "Illegal char",                         // PatternSyntaxException 家族
            "Unparseable number",                   // NumberFormatException 文案变体
            "Start of string",                      // 时间/正则解析
            "No enum constant",                     // Enum.valueOf
            "Illegal repetition", "Unclosed group", // 正则语法
            "Index out of bounds", "Index -1 out of bounds",
    };

    /**
     * 该消息是否属于「技术性」而不该回显。
     *
     * <p>包内可见：单测直接打表验证，不必构造完整 MVC 上下文。
     */
    static boolean isTechnicalIaeMessage(String message) {
        if (message == null || message.isBlank()) {
            return true;   // 空消息对客户端毫无意义，按技术性处理
        }
        for (String prefix : TECHNICAL_IAE_PREFIXES) {
            if (message.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 上传文件超过 multipart 限制：在进 Controller 之前被容器抛出，必须在这里接住 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小超过限制（最大 50MB），请压缩后重试");
    }

    /** @Valid 校验失败：返回第一条字段错误，让用户知道具体哪里不合规 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getDefaultMessage())
                .orElse("请求参数不合法");
        log.warn("参数校验失败: {}", msg);
        return body(HttpStatus.BAD_REQUEST, msg);
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        String msg = "缺少必填参数: " + e.getParameterName();
        log.warn(msg);
        return body(HttpStatus.BAD_REQUEST, msg);
    }

    /** 参数类型不匹配（如 taskId 传了非数字） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: {}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "参数格式不正确: " + e.getName());
    }

    /**
     * 积分不足：返回 402 Payment Required + 结构化余额信息。
     *
     * 用独立状态码而非 400，是为了让三端可以只靠状态码判定「该弹充值引导」，
     * 不必解析文案（文案会随后台规则说明变化）。响应额外带 required/balance/shortage，
     * 前端可直接显示「还需 N 积分」并预选合适的充值套餐。
     */
    @ExceptionHandler(InsufficientCreditsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientCredits(InsufficientCreditsException e) {
        log.warn("积分不足: required={}, balance={}", e.getRequired(), e.getBalance());
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", e.getMessage());
        response.put("code", "INSUFFICIENT_CREDITS");
        response.put("required", e.getRequired());
        response.put("balance", e.getBalance());
        response.put("shortage", e.getShortage());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(response);
    }

    /** 业务校验异常：消息本身就是给用户看的，原样返回 400 */
    /**
     * 参数/业务校验失败 → 400。
     *
     * <p><b>回显有边界</b>（v5 / N1）：业务文案原样回显（前端靠它 toast），
     * 但 JDK/基础库抛出的技术性消息统一换成"请求参数格式不正确"，原文只进日志 ——
     * 否则 {@code URLDecoder}、{@code Integer.parseInt} 这类实现细节会被透给调用方。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        if (isTechnicalIaeMessage(e.getMessage())) {
            log.warn("参数解析失败（技术性异常，文案不外显）: {}", e.getMessage());
            return body(HttpStatus.BAD_REQUEST, "请求参数格式不正确");
        }
        log.warn("业务校验失败: {}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 权限不足：Spring Security 方法级注解拒绝时走这里（filter 层的在 SecurityConfig 单独处理） */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        return body(HttpStatus.FORBIDDEN, "没有权限执行此操作");
    }

    /**
     * 乐观锁冲突：@Version 版本不一致时由 JPA 抛出，经 Spring 翻译为
     * ObjectOptimisticLockingFailureException。语义是「你操作的数据刚被别人改过」，
     * 返回 409 并引导重试；落进兜底 500 用户会以为系统故障反复重试，反而加剧冲突。
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("乐观锁冲突（数据已被并发修改）: {}", e.getMessage());
        return body(HttpStatus.CONFLICT, "数据已被其他操作更新，请刷新后重试");
    }

    /** 请求路径不存在：Spring 6 的 NoResourceFoundException 替代了旧版 404 处理 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getResourcePath());
        return body(HttpStatus.NOT_FOUND, "请求的资源不存在");
    }

    /**
     * Content-Type 不受支持 → 415，而不是兜底 500。
     *
     * <p><b>为什么必须单独接</b>：客户端把 multipart/form-data 发到只收 JSON 的
     * 端点（{@code POST /api/posts}）时，Spring 在进入 Controller 之前就抛
     * {@code HttpMediaTypeNotSupportedException}。落进兜底 500 会显示
     * 「服务器开小差了，请稍后重试」—— 这条文案完全看不出是「请求格式不对」，
     * 排查时极易误判为服务端故障或图片本身有问题（PC 端就踩过这个坑）。
     *
     * <p>415 + 明确文案，客户端一眼就能看出是自己发错了格式。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("Content-Type 不受支持: {}", e.getMessage());
        // ⚠ getContentType() 返回**单个** MediaType（可空），
        //   返回 List 的是 getSupportedMediaTypes() —— 别搞混，否则编译不过。
        MediaType contentType = e.getContentType();
        String detail = (contentType == null) ? "未指定 Content-Type" : contentType.toString();
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求格式不支持：" + detail);
    }

    /**
     * 请求体解析失败（非法 JSON / 字段类型不匹配 / 缺少 @RequestBody）→ 400，不是兜底 500。
     *
     * <p><b>为什么必须单独接</b>：Spring 在进 Controller 之前用 HttpMessageConverter
     * 把 body 反序列化成 DTO，失败时抛 {@code HttpMessageNotReadableException}。
     * 它和 {@link HttpMediaTypeNotSupportedException} 是同一类「客户端把请求写错了」，
     * 但此前没有专用分支，直接落进 {@link #handleGeneric}，对外只剩
     * 「服务器开小差了，请稍后重试」—— 生产部署验收时实测 POST 一个坏 JSON 返回 500，
     * 排查方向会被带成服务端故障，与「请求格式错不能伪装成服务器故障」的既有约定相悖。
     *
     * <p><b>为什么不回显 e.getMessage()</b>：那是 Jackson 的完整解析诊断，含 DTO 全限定类名、
     * 字段类型与源码片段，属于内部实现细节；与 {@code TECHNICAL_IAE_PREFIXES} 同理，
     * 原文只进日志，对外给可操作的固定文案。
     *
     * <p>⚠ 边界：{@code HttpMessageNotWritableException}（**响应**序列化失败）是服务端问题，
     * 仍应落 500，不要顺手接住。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        String raw = e.getMessage();
        log.warn("请求体解析失败: {}", raw);
        // 空 body 常见且客户端能自行修复，单独给更精准的提示；其余统一归到「JSON/字段类型」。
        String hint = (raw != null && raw.contains("Required request body is missing"))
                ? "请求体不能为空"
                : "请求体不是合法的 JSON，或字段类型不匹配，请检查后重试";
        return body(HttpStatus.BAD_REQUEST, hint);
    }

    /**
     * Spring MVC 标准「客户端错误」不得落进兜底 500。
     *
     * <p><b>实证（standaloneSetup 挂真实 advice，2026-11-09）</b>：GET 打到只收 POST 的端点、
     * multipart 缺必填 part，两者都进了 {@link #handleGeneric}，返回 500「服务器开小差了」
     * 并记 ERROR 全堆栈；Accept 与 produces 不匹配时状态码虽侥幸是 406，但响应体被写空，
     * 同样被记成未处理异常。根因是 {@code ExceptionHandlerExceptionResolver} 优先级高于
     * {@code DefaultHandlerExceptionResolver} —— 只要 advice 里有
     * {@code @ExceptionHandler(Exception.class)}，框架对这类异常的标准映射就轮不到。
     *
     * <p>显式接住后：状态码回归 HTTP 语义、响应体保持
     * {@code {"success": false, "message": "..."}} 统一格式、日志降为 warn。
     * 与 415（Content-Type）/ 400（畸形 JSON）是同一条约定。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMessage());
        String[] supported = e.getSupportedMethods();
        String hint = (supported != null && supported.length > 0)
                ? "请求方法不支持，请使用 " + String.join(" / ", supported)
                : "请求方法不支持";
        return body(HttpStatus.METHOD_NOT_ALLOWED, hint);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        log.warn("Accept 与响应类型不匹配: {}", e.getMessage());
        // ⚠ 必须强制 Content-Type=application/json：此时 Accept 与端点 produces 已冲突，
        //   不固定的话 Spring 选不出能写 Map 的 converter，错误体又会变成空串。
        return body(HttpStatus.NOT_ACCEPTABLE,
                "无法提供客户端可接受的响应格式，请检查 Accept 请求头",
                MediaType.APPLICATION_JSON);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException e) {
        String part = e.getRequestPartName();
        log.warn("缺少 multipart part: {}", part);
        String hint = (part == null || part.isBlank())
                ? "缺少必填的上传文件"
                : "缺少必填的上传文件: " + part;
        return body(HttpStatus.BAD_REQUEST, hint);
    }

    /**
     * 该族其它绑定错误（如 {@code UnsatisfiedServletRequestParameterException}）的兜底。
     * 已有的 {@code MissingServletRequestParameterException} / {@code MissingServletRequestPartException}
     * 分支更具体，Spring 会优先选它们，这里只接漏网的。
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<Map<String, Object>> handleRequestBinding(ServletRequestBindingException e) {
        log.warn("请求参数绑定失败: {}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, "请求参数不完整或格式不正确");
    }

    /**
     * 交互式 TTS 合成超时：504 + 具体文案。
     * 30s 超时上限下这是常见路径而非意外故障——若落进兜底 500「服务器开小差了」，
     * 用户会反复重试，每次既空等 30s 又消耗限流配额，形成双重打击。
     */
    @ExceptionHandler(TtsTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleTtsTimeout(TtsTimeoutException e) {
        log.warn("TTS 合成超时: {}", e.getMessage());
        return body(HttpStatus.GATEWAY_TIMEOUT, e.getMessage());
    }

    /** 兜底：未预期异常记完整堆栈，对外只给简短提示（不泄露内部细节） */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("未处理异常: {}", e.getMessage(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "服务器开小差了，请稍后重试");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return body(status, message, null);
    }

    /**
     * 可强制指定 Content-Type 的重载，只给 406 用。
     *
     * <p>客户端 Accept 与端点 produces 不匹配时，Spring 选不出能写响应体的
     * HttpMessageConverter，错误体会变成空串（前端拿不到 message）。
     * 错误响应统一固定为 application/json，绕过这次协商。
     */
    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, MediaType contentType) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (contentType != null) {
            builder.contentType(contentType);
        }
        return builder.body(response);
    }
}
