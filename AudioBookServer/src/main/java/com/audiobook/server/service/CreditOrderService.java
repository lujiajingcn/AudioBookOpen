package com.audiobook.server.service;

import com.audiobook.server.config.PaymentProperties;
import com.audiobook.server.model.CreditOrder;
import com.audiobook.server.model.CreditPackage;
import com.audiobook.server.model.User;
import com.audiobook.server.repository.CreditOrderRepository;
import com.audiobook.server.repository.CreditPackageRepository;
import com.audiobook.server.repository.UserRepository;
import com.audiobook.server.service.payment.PaymentProvider;
import com.audiobook.server.util.PageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 充值订单服务：下单、支付回调入账、超时关单。
 *
 * 职责划分：
 * - {@link PaymentProvider} 只负责与支付平台交互（下单、验签解析），不碰数据库；
 * - 本类持有订单状态机与幂等控制；
 * - {@link CreditService#applyRecharge} 只负责加积分和写流水。
 *
 * 幂等是这里的核心。支付平台的通知有三个特性必须假设成立：
 * 1. 会重复投递（哪怕第一次已返回成功）；
 * 2. 可能并发投递（两个线程同时进来）；
 * 3. 可能乱序（先收到查询结果后收到通知）。
 * 因此入账路径统一走 {@link CreditOrderRepository#findByOrderNoForUpdate} 行锁 +
 * 订单状态判定 + 流水查重三道闸门，重复通知只会命中「已支付，跳过」分支。
 */
@Service
public class CreditOrderService {

    private static final Logger log = LoggerFactory.getLogger(CreditOrderService.class);
    private static final DateTimeFormatter ORDER_NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CreditOrderRepository creditOrderRepository;
    private final CreditPackageRepository creditPackageRepository;
    private final UserRepository userRepository;
    private final CreditService creditService;
    private final PaymentProperties paymentProperties;

    /**
     * 自身的代理引用。
     *
     * 非事务方法 {@link #handleNotify} 需要调用事务方法 {@link #markOrderPaid}，
     * 而 Spring 的事务是基于代理实现的 —— 直接 this 调用会绕过代理，
     * 导致 markOrderPaid 根本没有事务，进而让 applyRecharge(MANDATORY) 抛
     * IllegalTransactionStateException，支付回调必然 500。
     * 所以这里注入自己的代理（@Lazy 打破构造期循环依赖）并通过它调用。
     */
    private final CreditOrderService self;

    /** 渠道标识 → Provider。Spring 把所有实现注入进来，新增渠道无需改本类 */
    private final Map<String, PaymentProvider> providers = new LinkedHashMap<>();

    public CreditOrderService(CreditOrderRepository creditOrderRepository,
                              CreditPackageRepository creditPackageRepository,
                              UserRepository userRepository,
                              CreditService creditService,
                              PaymentProperties paymentProperties,
                              List<PaymentProvider> providerList,
                              @Lazy CreditOrderService self) {
        this.creditOrderRepository = creditOrderRepository;
        this.creditPackageRepository = creditPackageRepository;
        this.userRepository = userRepository;
        this.creditService = creditService;
        this.paymentProperties = paymentProperties;
        this.self = self;
        for (PaymentProvider p : providerList) {
            this.providers.put(p.channel().toUpperCase(), p);
        }
        log.info("支付渠道已注册: {}", this.providers.keySet());
    }

    // ==================== 支付渠道 ====================

    /** 当前可用的支付渠道列表，供前端渲染支付方式按钮 */
    public List<Map<String, Object>> listAvailableChannels() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PaymentProvider p : providers.values()) {
            if (!p.isConfigured()) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("channel", p.channel());
            item.put("name", channelDisplayName(p.channel()));
            list.add(item);
        }
        return list;
    }

    // ==================== 下单 ====================

    /**
     * 创建充值订单并向支付平台下单。
     *
     * 套餐信息在下单瞬间快照进订单：之后管理员改价、下架、删除套餐都不影响
     * 这笔订单的应付金额与到账积分，避免用户付款后收到的积分与下单时看到的不一致。
     *
     * 事务边界：订单落库走一个极短的独立事务（{@link #persistOrder}），
     * 支付渠道外呼放在事务之外 —— 微信 Native 是一次最长 15s 的 HTTPS 请求，
     * 夹在事务里会全程占住数据库连接，慢网络下并发下单足以耗尽连接池。
     * 外呼失败时立即关闭订单（带状态守卫），不留无法支付的 PENDING 残单；
     * 即使关闭动作本身也失败，超时关单调度器仍会兜底。
     *
     * @param username  下单用户
     * @param packageId 套餐 ID
     * @param channel   期望渠道（ALIPAY / WECHAT / MOCK），未配置时自动降级
     * @return 前端渲染支付所需信息
     */
    public Map<String, Object> createOrder(String username, Long packageId, String channel) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + username));

        CreditPackage pkg = creditPackageRepository.findById(packageId)
                .orElseThrow(() -> new IllegalArgumentException("套餐不存在: " + packageId));
        if (!Boolean.TRUE.equals(pkg.getEnabled())) {
            throw new IllegalArgumentException("该套餐已下架");
        }
        if (pkg.getPrice() == null || pkg.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("套餐价格配置异常，请联系管理员");
        }

        PaymentProvider provider = resolveProvider(channel);

        CreditOrder order = new CreditOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(user.getId());
        order.setUsername(user.getUsername());
        order.setPackageId(pkg.getId());
        order.setPackageName(pkg.getName());
        order.setCredits(pkg.getCredits());
        order.setBonusCredits(pkg.getBonusCredits() == null ? 0 : pkg.getBonusCredits());
        order.setAmount(pkg.getPrice().setScale(2, RoundingMode.HALF_UP));
        order.setStatus(CreditOrder.STATUS_PENDING);
        order.setPayChannel(provider.channel());
        order.setExpireAt(LocalDateTime.now().plusMinutes(Math.max(1, paymentProperties.getOrderExpireMinutes())));

        // 短事务只做 insert，立即提交释放连接
        CreditOrder saved = self.persistOrder(order);

        // 支付渠道下单必须在订单落库之后：先有本地订单，支付平台的通知才有落脚点。
        // 外呼不在事务内；失败则关闭订单并向上抛错（上层转 503）。
        PaymentProvider.PrepayResult prepay;
        try {
            prepay = provider.prepay(saved);
        } catch (RuntimeException e) {
            log.error("支付渠道下单失败，关闭充值订单: orderNo={}, channel={}, 原因: {}",
                    saved.getOrderNo(), provider.channel(), e.getMessage());
            try {
                self.closeOrderOnPrepayFailure(saved.getOrderNo());
            } catch (Exception closeEx) {
                // 关闭失败不影响主流程：超时关单调度器会兜底
                log.warn("关闭下单失败订单时异常（将由超时关单兜底）: orderNo={}, 原因: {}",
                        saved.getOrderNo(), closeEx.getMessage());
            }
            throw e instanceof IllegalStateException ? (IllegalStateException) e
                    : new IllegalStateException("支付渠道下单失败: " + e.getMessage(), e);
        }

        log.info("充值订单已创建: orderNo={}, user={}, package={}, amount={}, credits={}(+{}), channel={}",
                saved.getOrderNo(), username, pkg.getName(), saved.getAmount(),
                saved.getCredits(), saved.getBonusCredits(), provider.channel());

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", saved.getOrderNo());
        result.put("amount", saved.getAmount());
        result.put("credits", saved.getCredits());
        result.put("bonusCredits", saved.getBonusCredits());
        result.put("totalCredits", saved.getTotalCredits());
        result.put("packageName", saved.getPackageName());
        result.put("channel", prepay.channel());
        result.put("payType", prepay.payType());
        result.put("payUrl", prepay.payUrl());
        result.put("expireAt", saved.getExpireAt());
        return result;
    }

    /** 短事务：仅落库订单并立即提交，不夹带任何外部 IO */
    @Transactional
    public CreditOrder persistOrder(CreditOrder order) {
        return creditOrderRepository.save(order);
    }

    /** 支付渠道下单失败后关闭订单（带状态守卫，不覆盖其他终态） */
    @Transactional
    public void closeOrderOnPrepayFailure(String orderNo) {
        creditOrderRepository.findByOrderNoForUpdate(orderNo).ifPresent(o -> {
            if (CreditOrder.STATUS_PENDING.equals(o.getStatus())) {
                o.setStatus(CreditOrder.STATUS_CLOSED);
                o.setFailReason("支付渠道下单失败，订单已关闭");
                creditOrderRepository.save(o);
                log.warn("支付渠道下单失败，已关闭充值订单: orderNo={}", orderNo);
            }
        });
    }

    /**
     * 渠道解析与降级。
     *
     * 传入渠道不存在或未配置密钥时，若允许 MOCK 则降级为模拟支付
     * （本地开发常态），否则直接报错 —— 生产环境宁可下单失败，
     * 也不能悄悄用模拟支付放行，那等于免费送积分。
     */
    private PaymentProvider resolveProvider(String channel) {
        String key = channel == null || channel.isBlank() ? "ALIPAY" : channel.trim().toUpperCase();
        PaymentProvider provider = providers.get(key);

        if (provider != null && provider.isConfigured()) {
            return provider;
        }

        PaymentProvider mock = providers.get("MOCK");
        if (mock != null && mock.isConfigured()) {
            log.warn("渠道 {} 不可用（未注册或未配置密钥），降级为 MOCK 模拟支付", key);
            return mock;
        }
        throw new IllegalStateException("支付渠道 " + key + " 未配置，且模拟支付已禁用，无法下单");
    }

    /** 订单号：时间戳 + 6 位随机数，兼顾可读性与唯一性（唯一索引兜底） */
    private String generateOrderNo() {
        return "AB" + LocalDateTime.now().format(ORDER_NO_TS) + String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    // ==================== 支付回调 ====================

    /**
     * 处理支付平台异步通知。
     *
     * @return 需要回给支付平台的响应体
     */
    public String handleNotify(String channel, Map<String, String> params, String rawBody, Map<String, String> headers) {
        PaymentProvider provider = providers.get(channel == null ? "" : channel.trim().toUpperCase());
        if (provider == null) {
            log.error("收到未知渠道的支付通知: channel={}", channel);
            return "failure";
        }

        PaymentProvider.NotifyResult result;
        try {
            result = provider.parseNotify(params, rawBody, headers);
        } catch (Exception e) {
            log.error("解析支付通知异常: channel={}, 原因: {}", channel, e.getMessage(), e);
            return provider.failureResponse();
        }

        if (result == null) {
            // 验签失败/报文非法：返回失败让平台重投，同时留下 ERROR 日志便于排查
            return provider.failureResponse();
        }
        if (!result.paid()) {
            // 非成功终态（如已关闭、退款中）无需入账，但要回成功避免平台无意义重投
            log.info("忽略非支付成功通知: channel={}, orderNo={}", channel, result.orderNo());
            return provider.successResponse();
        }

        try {
            // 必须走代理调用，否则事务不生效（见 self 字段注释）
            self.markOrderPaid(result, provider.channel());
            return provider.successResponse();
        } catch (Exception e) {
            // 入账失败必须回失败：让平台重投，靠幂等保证最终只入账一次。
            // 若这里骗平台说成功，用户的钱就永久丢了。
            log.error("支付入账失败，将由平台重投: orderNo={}, 原因: {}", result.orderNo(), e.getMessage(), e);
            return provider.failureResponse();
        }
    }

    /**
     * 订单置为已支付并发放积分（幂等）。
     *
     * 全程在一个事务里：先拿订单行锁，再改状态、再加积分。
     * 并发的第二条通知会阻塞在行锁上，等第一条提交后读到 PAID 直接返回，
     * 因此不会重复到账。
     */
    @Transactional
    public void markOrderPaid(PaymentProvider.NotifyResult result, String channel) {
        CreditOrder order = creditOrderRepository.findByOrderNoForUpdate(result.orderNo())
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + result.orderNo()));

        if (CreditOrder.STATUS_PAID.equals(order.getStatus())) {
            log.info("订单已支付，跳过重复入账: orderNo={}", order.getOrderNo());
            return;
        }

        // 已关闭（超时/取消）的订单收到迟到的成功通知：钱确实收了，必须入账，
        // 直接丢弃会造成用户付了钱没积分。这里重新激活订单并记录告警供人工复核。
        if (CreditOrder.STATUS_CLOSED.equals(order.getStatus())) {
            log.warn("已关闭订单收到支付成功通知，重新入账（请人工复核）: orderNo={}", order.getOrderNo());
        }

        // 金额一致性校验：防止有人篡改通知里的金额，或套餐改价导致对不上账。
        // MOCK 渠道不带金额（内部调用，金额以订单快照为准），跳过校验。
        if (result.amount() != null) {
            BigDecimal expected = order.getAmount().setScale(2, RoundingMode.HALF_UP);
            BigDecimal actual = result.amount().setScale(2, RoundingMode.HALF_UP);
            if (expected.compareTo(actual) != 0) {
                log.error("支付金额与订单不一致，拒绝入账: orderNo={}, 应付={}, 实付={}",
                        order.getOrderNo(), expected, actual);
                throw new IllegalStateException("支付金额与订单金额不一致");
            }
        }

        order.setStatus(CreditOrder.STATUS_PAID);
        order.setPayChannel(channel);
        order.setPayTradeNo(result.tradeNo());
        order.setPaidAt(LocalDateTime.now());
        creditOrderRepository.save(order);

        int credited = creditService.applyRecharge(order);
        log.info("充值订单支付完成: orderNo={}, user={}, channel={}, 到账积分={}",
                order.getOrderNo(), order.getUsername(), channel, credited);
    }

    /**
     * 模拟支付确认（仅开发环境）。
     *
     * 校验订单归属，防止用户 A 拿到订单号后替 B 的订单「付款」——
     * 虽然那样只是给 B 白送积分，但仍是可被滥用的越权入口。
     */
    @Transactional
    public void confirmMockPay(String orderNo, String username) {
        if (!paymentProperties.isAllowMock()) {
            throw new IllegalStateException("当前环境已禁用模拟支付");
        }
        CreditOrder order = creditOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderNo));
        if (!order.getUsername().equals(username)) {
            throw new IllegalArgumentException("无权操作该订单");
        }
        if (!"MOCK".equalsIgnoreCase(order.getPayChannel())) {
            throw new IllegalArgumentException("该订单不是模拟支付订单");
        }

        markOrderPaid(new PaymentProvider.NotifyResult(orderNo, "MOCK-" + orderNo, null, true), "MOCK");
    }

    // ==================== 查询与关单 ====================

    /** 我的订单（分页） */
    @Transactional(readOnly = true)
    public Page<CreditOrder> listMyOrders(String username, int page, int size) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + username));
        return creditOrderRepository.findByUserIdOrderByCreatedAtDesc(
                user.getId(), PageRequest.of(Math.max(0, page), PageUtil.clampSize(size)));
    }

    /**
     * 查询单笔订单状态，供前端支付后轮询。
     * 支付宝跳转回来时通知可能还没到，前端需要靠轮询确认到账。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getOrderStatus(String orderNo, String username) {
        CreditOrder order = creditOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderNo));
        if (!order.getUsername().equals(username)) {
            throw new IllegalArgumentException("无权查看该订单");
        }
        Map<String, Object> map = new HashMap<>();
        map.put("orderNo", order.getOrderNo());
        map.put("status", order.getStatus());
        map.put("amount", order.getAmount());
        map.put("totalCredits", order.getTotalCredits());
        map.put("packageName", order.getPackageName());
        map.put("payChannel", order.getPayChannel());
        map.put("paidAt", order.getPaidAt());
        map.put("expireAt", order.getExpireAt());
        map.put("failReason", order.getFailReason());
        map.put("balance", creditService.getBalance(username));
        return map;
    }

    /** 用户主动取消未支付订单 */
    @Transactional
    public void cancelOrder(String orderNo, String username) {
        CreditOrder order = creditOrderRepository.findByOrderNoForUpdate(orderNo)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderNo));
        if (!order.getUsername().equals(username)) {
            throw new IllegalArgumentException("无权操作该订单");
        }
        if (CreditOrder.STATUS_PAID.equals(order.getStatus())) {
            throw new IllegalArgumentException("订单已支付，无法取消");
        }
        order.setStatus(CreditOrder.STATUS_CLOSED);
        order.setFailReason("用户主动取消订单");
        creditOrderRepository.save(order);
        log.info("用户取消充值订单: orderNo={}, user={}", orderNo, username);
    }

    /**
     * 定时关闭超时未支付订单（每 5 分钟）。
     *
     * 注意这里只改状态、不做任何退款动作 —— PENDING 意味着我方从未收到过支付成功通知。
     * 若关单后才收到迟到的成功通知，{@link #markOrderPaid} 会重新激活并入账，
     * 所以关单是安全的、可逆的。
     *
     * 竞态防护：列表查询不加锁，读到的状态可能已经过时 —— 回调可能恰好在
     * 「查列表」与「写状态」之间把订单置为 PAID 并完成入账。直接 saveAll 会用
     * 内存里的旧实体把 PAID 覆盖回 CLOSED，造成「已付款已到账但订单显示已关闭」
     * 的脏数据。因此逐单加锁重读并复核状态仍是 PENDING 才允许关闭；行锁会等
     * 并发回调事务先提交，复核必然读到最新状态。此外 CreditOrder.@Version 乐观锁
     * 作为最后防线：即使未来有写路径漏加行锁，并发覆盖也会以一方回滚告终。
     *
     * 事务与内存边界：
     * 1. 快照查询限量（{@link #CLOSE_SCAN_LIMIT}），不无上限全量加载积压订单；
     * 2. 本方法不标注 @Transactional——把「全量加载 + 逐单行锁关闭」整体包在
     *    一个长事务里时，订单积压下事务时长与持锁时间不可控，还会阻塞正常支付入账；
     * 3. 每 {@link #CLOSE_BATCH_SIZE} 单一个独立短事务（经 self 代理），
     *    单批失败只告警不中断，漏关的下一轮调度自然重试。
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void closeExpiredOrders() {
        // 无事务：限量快照（按过期时间升序取订单号），漏关项下轮自然重试，无需分页游标
        List<String> expiredOrderNos = creditOrderRepository.findExpiredOrderNos(
                CreditOrder.STATUS_PENDING, LocalDateTime.now(), PageRequest.of(0, CLOSE_SCAN_LIMIT));
        if (expiredOrderNos.isEmpty()) {
            return;
        }
        int totalClosed = 0;
        for (int i = 0; i < expiredOrderNos.size(); i += CLOSE_BATCH_SIZE) {
            List<String> batch = expiredOrderNos.subList(i, Math.min(i + CLOSE_BATCH_SIZE, expiredOrderNos.size()));
            try {
                totalClosed += self.closeExpiredBatch(batch);
            } catch (Exception e) {
                // 单批失败不影响其余批次，漏关项下一轮调度重试
                log.warn("批量关闭过期订单失败（size={}，下轮重试）: {}", batch.size(), e.getMessage());
            }
        }
        if (totalClosed > 0) {
            log.info("已关闭 {} 笔超时未支付订单（本轮扫描到 {} 笔）", totalClosed, expiredOrderNos.size());
        }
    }

    /**
     * 短事务：关闭一批过期订单（经 self 代理调用，保证事务生效）。
     * 行锁复核状态后再关；批与批之间事务独立提交。
     */
    @Transactional
    public int closeExpiredBatch(List<String> orderNos) {
        int closed = 0;
        for (String orderNo : orderNos) {
            CreditOrder order = creditOrderRepository.findByOrderNoForUpdate(orderNo).orElse(null);
            if (order == null || !CreditOrder.STATUS_PENDING.equals(order.getStatus())) {
                // 快照已过时：订单多半已被并发回调置为 PAID，绝不能覆盖
                continue;
            }
            order.setStatus(CreditOrder.STATUS_CLOSED);
            order.setFailReason("超时未支付，订单已自动关闭");
            order.setUpdatedAt(LocalDateTime.now());
            creditOrderRepository.save(order);
            closed++;
        }
        return closed;
    }

    // ==================== 后台管理 ====================

    /** 后台订单列表（status 为空表示全部） */
    @Transactional(readOnly = true)
    public Page<CreditOrder> listOrdersForAdmin(String status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(0, page), PageUtil.clampSize(size),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        if (status == null || status.isBlank()) {
            return creditOrderRepository.findAll(pageRequest);
        }
        return creditOrderRepository.findByStatus(status.trim().toUpperCase(), pageRequest);
    }

    private static String channelDisplayName(String channel) {
        return switch (channel == null ? "" : channel.toUpperCase()) {
            case "ALIPAY" -> "支付宝";
            case "WECHAT" -> "微信支付";
            case "MOCK" -> "模拟支付（开发）";
            default -> channel;
        };
    }

    /** 单轮关单最多扫描的订单数（限量快照，防积压时全量加载） */
    private static final int CLOSE_SCAN_LIMIT = 1000;
    /** 单个关单事务最多处理的订单数（短事务上限） */
    private static final int CLOSE_BATCH_SIZE = 100;
}
