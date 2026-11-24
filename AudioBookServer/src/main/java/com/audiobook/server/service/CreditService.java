package com.audiobook.server.service;

import com.audiobook.server.exception.InsufficientCreditsException;
import com.audiobook.server.model.CreditOrder;
import com.audiobook.server.model.CreditRule;
import com.audiobook.server.model.CreditTransaction;
import com.audiobook.server.model.Task;
import com.audiobook.server.model.TaskStatus;
import com.audiobook.server.model.User;
import com.audiobook.server.repository.CreditRuleRepository;
import com.audiobook.server.repository.CreditTransactionRepository;
import com.audiobook.server.repository.TaskRepository;
import com.audiobook.server.repository.UserRepository;
import com.audiobook.server.util.PageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 积分核心服务：规则读写 + 余额变更 + 流水记账。
 *
 * 设计要点（都是为了不出资损）：
 *
 * 1. 余额的每一次读-改-写都必须先 {@link UserRepository#findByIdForUpdate} 拿行锁。
 *    不加锁时并发提交两个任务会各自读到同一余额、各扣一次再写回，后写覆盖先写，
 *    结果是「扣一次钱跑两个任务」。
 *
 * 2. 余额与流水必须在同一个事务里落库。流水的 balanceAfter 是变动后余额快照，
 *    对账时逐条回放即可定位差异，这是排查资损唯一可靠的手段。
 *
 * 3. 退款与充值入账都做双重幂等：
 *    - 主判据是业务字段（任务的 creditsRefunded / 订单的 status）；
 *    - 兜底判据是流水查重（同 taskId+REFUND / 同 orderNo+RECHARGE 是否已存在）。
 *    任一命中就直接返回，绝不重复加钱。
 *
 * 4. 计费口径：按「合成任务」固定扣费，与字数、段落数无关（产品决策）。
 *    任务整体失败（无任何片段成功）时按任务上记录的 creditsCost 全额退还；
 *    部分成功（partial）不退，因为用户已经拿到了可用音频。
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    private final UserRepository userRepository;
    private final TaskRepository taskRepository;
    private final CreditRuleRepository creditRuleRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final EntityManager entityManager;

    public CreditService(UserRepository userRepository,
                         TaskRepository taskRepository,
                         CreditRuleRepository creditRuleRepository,
                         CreditTransactionRepository creditTransactionRepository,
                         EntityManager entityManager) {
        this.userRepository = userRepository;
        this.taskRepository = taskRepository;
        this.creditRuleRepository = creditRuleRepository;
        this.creditTransactionRepository = creditTransactionRepository;
        this.entityManager = entityManager;
    }

    // ==================== 规则 ====================

    /**
     * 读取全局积分规则；首次访问时惰性创建默认行。
     *
     * 用 save()（底层 merge）而不是 persist()：主键固定为 1，merge 在并发下会退化为
     * SELECT + INSERT/UPDATE，即使两个请求同时初始化也不会因主键冲突炸掉启动流程。
     */
    @Transactional
    public CreditRule getRule() {
        return creditRuleRepository.findById(CreditRule.SINGLETON_ID)
                .orElseGet(() -> {
                    log.info("积分规则尚未初始化，写入默认规则行 (id={})", CreditRule.SINGLETON_ID);
                    return creditRuleRepository.save(new CreditRule());
                });
    }

    /**
     * 后台更新积分规则（AudioBookManager 调用）。
     * 只覆盖传入的非 null 字段，避免前端漏传某个开关就把它重置成默认值。
     */
    @Transactional
    public CreditRule updateRule(CreditRule form, String operator) {
        CreditRule rule = getRule();

        if (form.getEnabled() != null) {
            rule.setEnabled(form.getEnabled());
        }
        if (form.getCreditsPerTask() != null) {
            if (form.getCreditsPerTask() < 0) {
                throw new IllegalArgumentException("每任务消耗积分不能为负数");
            }
            rule.setCreditsPerTask(form.getCreditsPerTask());
        }
        if (form.getFreeCreditsOnRegister() != null) {
            if (form.getFreeCreditsOnRegister() < 0) {
                throw new IllegalArgumentException("注册赠送积分不能为负数");
            }
            rule.setFreeCreditsOnRegister(form.getFreeCreditsOnRegister());
        }
        if (form.getDailyFreeTasks() != null) {
            if (form.getDailyFreeTasks() < 0) {
                throw new IllegalArgumentException("每日免费试用次数不能为负数");
            }
            rule.setDailyFreeTasks(form.getDailyFreeTasks());
        }
        if (form.getRefundOnTaskFailure() != null) {
            rule.setRefundOnTaskFailure(form.getRefundOnTaskFailure());
        }
        if (form.getAdminExempt() != null) {
            rule.setAdminExempt(form.getAdminExempt());
        }
        if (form.getDescription() != null) {
            rule.setDescription(form.getDescription());
        }
        rule.setUpdatedBy(operator);
        rule.setUpdatedAt(LocalDateTime.now());

        CreditRule saved = creditRuleRepository.save(rule);
        log.info("积分规则已更新: operator={}, enabled={}, creditsPerTask={}, registerBonus={}, refundOnFail={}, adminExempt={}",
                operator, saved.getEnabledSafe(), saved.getCreditsPerTaskSafe(),
                saved.getFreeCreditsOnRegisterSafe(), saved.getRefundOnTaskFailureSafe(), saved.getAdminExemptSafe());
        return saved;
    }

    // ==================== 查询 ====================

    /** 当前余额（用户不存在时抛业务异常，交由全局处理器转 400） */
    @Transactional(readOnly = true)
    public int getBalance(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + username));
        return nz(user.getCredits());
    }

    /** 我的积分流水（分页，按时间倒序） */
    @Transactional(readOnly = true)
    public Page<CreditTransaction> listMyTransactions(String username, int page, int size) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + username));
        return creditTransactionRepository.findByUserIdOrderByCreatedAtDesc(
                user.getId(), PageRequest.of(Math.max(0, page), PageUtil.clampSize(size)));
    }

    /**
     * 后台流水查询：userId / type 均可为空，四种组合分别走对应的派生查询。
     * 统一按创建时间倒序，保证分页结果稳定（无排序的分页在 MySQL 下顺序不可预期）。
     */
    @Transactional(readOnly = true)
    public Page<CreditTransaction> listTransactionsForAdmin(Long userId, String type, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(0, page), PageUtil.clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        String normalizedType = type == null || type.isBlank() ? null : type.trim().toUpperCase();

        if (userId != null && normalizedType != null) {
            return creditTransactionRepository.findByUserIdAndType(userId, normalizedType, pageRequest);
        }
        if (userId != null) {
            return creditTransactionRepository.findByUserId(userId, pageRequest);
        }
        if (normalizedType != null) {
            return creditTransactionRepository.findByType(normalizedType, pageRequest);
        }
        return creditTransactionRepository.findAll(pageRequest);
    }

    /**
     * 后台积分概览。
     *
     * consumed 取 CONSUME 汇总的绝对值（该类型流水存的是负数）；
     * outstanding 是全平台余额之和，即「已发放但尚未消耗」的积分存量，
     * 相当于平台对用户的服务负债，是运营最该盯的一个数。
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getStats() {
        long recharged = creditTransactionRepository.sumCreditsByType(CreditTransaction.TYPE_RECHARGE);
        long consumed = Math.abs(creditTransactionRepository.sumCreditsByType(CreditTransaction.TYPE_CONSUME));
        long refunded = creditTransactionRepository.sumCreditsByType(CreditTransaction.TYPE_REFUND);
        long registerBonus = creditTransactionRepository.sumCreditsByType(CreditTransaction.TYPE_REGISTER_BONUS);
        long adminAdjusted = creditTransactionRepository.sumCreditsByType(CreditTransaction.TYPE_ADMIN_ADJUST);
        long outstanding = userRepository.sumAllCredits();

        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("rechargedCredits", recharged);
        stats.put("consumedCredits", consumed);
        stats.put("refundedCredits", refunded);
        stats.put("registerBonusCredits", registerBonus);
        stats.put("adminAdjustedCredits", adminAdjusted);
        stats.put("outstandingCredits", outstanding);
        return stats;
    }

    /**
     * 计算某用户提交一个合成任务应扣的积分。
     * 返回 0 表示本次免费（功能关闭 / 规则设为 0 / 管理员豁免）。
     */
    public int resolveCost(CreditRule rule, User user) {
        if (rule == null || !rule.getEnabledSafe()) {
            return 0;
        }
        if (rule.getAdminExemptSafe() && Boolean.TRUE.equals(user.getIsAdmin())) {
            return 0;
        }
        return Math.max(0, rule.getCreditsPerTaskSafe());
    }

    /**
     * 该用户当日剩余的免费试用合成次数。
     *
     * 用量以 credit_transactions 中 type=FREE_TRIAL 且创建于今日的流水条数计；
     * 规则里的 {@link CreditRule#getDailyFreeTasksSafe()} 为上限，超过即清零。
     * 返回 0 表示今日试用已用完（或规则未开启试用）。
     */
    @Transactional(readOnly = true)
    public int getFreeTrialsRemaining(User user) {
        int limit = getRule().getDailyFreeTasksSafe();
        if (limit <= 0) {
            return 0;
        }
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        long used = creditTransactionRepository.countByUserIdAndTypeAndCreatedAtBetween(
                user.getId(), CreditTransaction.TYPE_FREE_TRIAL, startOfDay, LocalDateTime.now());
        return (int) Math.max(0, limit - used);
    }

    /** 按用户名查剩余免费试用次数（供 controller 组装 /api/credits/me） */
    @Transactional(readOnly = true)
    public int getFreeTrialsRemaining(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + username));
        return getFreeTrialsRemaining(user);
    }

    /**
     * 消费一次免费试用额度：不扣积分，只记一条 FREE_TRIAL 流水（credits=0，
     * balanceAfter 取当前余额），并把任务的 creditsCost 置 0。
     * 必须在调用方事务内执行（与扣费同理），保证流水与任务状态原子落库。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeFreeTrial(Task task, User user) {
        User locked = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + user.getId()));
        int balance = nz(locked.getCredits());
        CreditTransaction tx = new CreditTransaction(locked.getId(), locked.getUsername(),
                CreditTransaction.TYPE_FREE_TRIAL, 0, balance, "免费试用合成");
        tx.setTaskId(task.getId());
        creditTransactionRepository.save(tx);

        task.setCreditsCost(0);
        task.setCreditsRefunded(0);

        log.info("免费试用合成: user={}, taskId={}, 当日剩余试用={}",
                locked.getUsername(), task.getId(), getFreeTrialsRemaining(locked));
    }

    // ==================== 扣费 ====================

    /**
     * 提交任务前的快速余额校验（不加锁）。
     *
     * 放在文件落盘之前调用：余额本来就不够的用户能立刻收到 402，
     * 不必先把几十 MB 的电子书写进磁盘再回滚删除。
     * 这里不加锁，所以只是「预检」，真正的原子扣减由 {@link #chargeForTask} 负责。
     */
    public void preCheckBalance(CreditRule rule, User user) {
        int cost = resolveCost(rule, user);
        if (cost <= 0) {
            return;
        }
        // 有免费试用额度时跳过余额校验：本次合成走试用通道，不会扣积分
        if (getFreeTrialsRemaining(user) > 0) {
            return;
        }
        int balance = nz(user.getCredits());
        if (balance < cost) {
            throw new InsufficientCreditsException(cost, balance);
        }
    }

    /**
     * 为合成任务原子扣费，并把扣费金额写到任务上（退款以此为准）。
     *
     * 必须在调用方的事务内执行（Propagation.MANDATORY 强制约束）：
     * 扣费、任务落库、文件落盘必须同生共死 —— 任一失败则一起回滚，
     * 既不会「扣了钱没建任务」，也不会「建了任务没扣钱」。
     *
     * @return 实际扣除的积分（0 表示本次免费）
     * @throws InsufficientCreditsException 余额不足，触发整体回滚
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int chargeForTask(Task task, User user) {
        CreditRule rule = getRule();
        int cost = resolveCost(rule, user);
        if (cost <= 0) {
            task.setCreditsCost(0);
            return 0;
        }

        // 关键：先取用户行锁，把并发的同用户扣费串行化。
        // 不能直接用入参 user 的 credits 做判断 —— 它是加锁前读到的旧值。
        User locked = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + user.getId()));

        // 免费试用额度：每日前 N 次合成免积分（降低首充门槛，让用户先尝鲜）。
        //
        // 判定必须排在取行锁之后：getFreeTrialsRemaining 是一次无锁的 SELECT COUNT，
        // 放在行锁之前时，N 个并发请求在 REPEATABLE READ 下各自读到
        // 「今日已用 0 条」的快照，会全部通过判定、随后排队各插一条 FREE_TRIAL 流水，
        // 导致每日免费额度被无上限打穿（等价于不限量免费合成）。
        // 现在 COUNT 与 INSERT 都在同一用户行锁保护下完成，额度严格生效。
        if (getFreeTrialsRemaining(locked) > 0) {
            consumeFreeTrial(task, locked);
            return 0;
        }

        int balance = nz(locked.getCredits());
        if (balance < cost) {
            throw new InsufficientCreditsException(cost, balance);
        }

        int after = balance - cost;
        locked.setCredits(after);
        locked.setUpdatedAt(LocalDateTime.now());
        userRepository.save(locked);

        task.setCreditsCost(cost);
        task.setCreditsRefunded(0);

        CreditTransaction tx = new CreditTransaction(locked.getId(), locked.getUsername(),
                CreditTransaction.TYPE_CONSUME, -cost, after, "合成任务扣费");
        tx.setTaskId(task.getId());
        creditTransactionRepository.save(tx);

        log.info("积分扣费成功: user={}, taskId={}, cost={}, balance {} -> {}",
                locked.getUsername(), task.getId(), cost, balance, after);
        return cost;
    }

    /**
     * 重试任务时的补扣费。
     *
     * 为什么需要：任务整体失败后积分已全额退还，如果重试不再扣费，用户就能靠
     * 「失败 → 退款 → 重试」无限次免费合成。只在「已退过款」的情况下重新扣费；
     * 若任务从未退款（如 partial 重试失败片段），说明本次合成的钱还在，不再重复收。
     *
     * @return 本次补扣的积分（0 表示无需补扣）
     */
    @Transactional
    public int chargeForRetry(Long taskId) {
        // 全局加锁顺序约定（防御性）：先任务行锁，后用户行锁（task → user）。
        // 本方法先锁任务（下方 findByIdForUpdate(taskId)），扣费时再由 chargeForTask
        // 锁用户，方向一致；refundForTask 同样遵循此顺序，避免并发反向加锁形成死锁。
        // 任务行锁：把「判定 creditsRefunded → 扣费 → 归零」整体串行化。
        // 无锁 findById 下，双击/前端超时重试等并发请求能同时读到
        // creditsRefunded>0 并各扣一次费，而任务再次失败只退一次——用户净亏一份积分。
        Task task = taskRepository.findByIdForUpdate(taskId).orElse(null);
        if (task == null) {
            return 0;
        }
        // 拿锁后复核状态：并发重试可能已把任务重新派发（queued/processing），
        // 此时不应再走收费-派发流程，交由调用方以 400 拒绝本次重试。
        String status = task.getStatus();
        if (TaskStatus.isUnfinished(status)) {
            throw new IllegalStateException("任务仍在处理中，无需重试");
        }
        // 「本次合成的钱还在，无需再收」的判据必须是「已有效扣费 且 未退款」，
        // 不能只看 creditsRefunded。
        // 只判 creditsRefunded <= 0 就放行，会让所有零成本任务（免费试用 /
        // 积分功能关闭 / 管理员豁免 —— 它们的 creditsCost 恒为 0，失败时 refundForTask
        // 因 cost<=0 直接 return，creditsRefunded 永远是 0）获得无限次免费重试。
        if (nz(task.getCreditsCost()) > 0 && nz(task.getCreditsRefunded()) <= 0) {
            // 已扣过费且未退款 → 这笔任务的积分仍然有效，重试不再收费
            return 0;
        }
        // 走到这里说明：要么任务失败后已全额退款（creditsRefunded > 0，必须补扣），
        // 要么本次是零成本任务（需重新消耗一次免费额度或按当前规则扣费）。

        User user = userRepository.findById(task.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + task.getUserId()));

        int cost = chargeForTask(task, user);
        // chargeForTask 已把 creditsRefunded 归零，这里落库
        taskRepository.save(task);
        if (cost > 0) {
            log.info("重试任务补扣积分: taskId={}, user={}, cost={}", taskId, user.getUsername(), cost);
        }
        return cost;
    }

    // ==================== 退款 ====================

    /**
     * 任务整体失败时全额退还积分（幂等）。
     *
     * 用 REQUIRES_NEW 独立事务：调用方是异步任务收敛流程（processTtsTaskAsync），
     * 它本身没有事务；即便将来被包进事务，退款也不应因为外层后续步骤失败而被回滚
     * —— 钱既然该退，就必须落地。
     *
     * 退还金额取任务上的 creditsCost 快照，而不是当前规则的 creditsPerTask：
     * 后台改了单价之后，历史任务必须按当初实际扣的金额退，否则会多退或少退。
     *
     * @return 实际退还的积分（0 表示无需退款）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int refundForTask(Long taskId, String reason) {
        // 全局加锁顺序约定（防御性）：先任务行锁，后用户行锁（task → user）。
        // 本方法先读取任务（普通 findById，未加行锁）再于下方对应用户加行锁：本路径无死锁风险，
        // 保持现状可避免为对齐加锁顺序而引入回归。chargeForRetry 已显式遵循 task → user 顺序。
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return 0;
        }

        int cost = nz(task.getCreditsCost());
        if (cost <= 0) {
            return 0;
        }

        // 幂等主判据：以「当前计费周期的已退金额」为准。
        // 重试会在 chargeForTask 中把 creditsRefunded 归零，从而重新开启退款；
        // 用「任务是否存在 REFUND 流水」做幂等，会让第二次失败（已有一笔历史退款流水）
        // 被误判为已退款而漏退，用户每重试失败一次净亏 cost 积分。
        if (nz(task.getCreditsRefunded()) >= cost) {
            log.debug("任务当前周期已退款，跳过: taskId={}", taskId);
            return 0;
        }

        if (!getRule().getRefundOnTaskFailureSafe()) {
            log.info("规则已关闭失败退款，不退还: taskId={}, cost={}", taskId, cost);
            return 0;
        }

        User locked = userRepository.findByIdForUpdate(task.getUserId()).orElse(null);
        if (locked == null) {
            log.error("退款失败：用户不存在, taskId={}, userId={}", taskId, task.getUserId());
            return 0;
        }

        // 拿到用户行锁后再次校验：防止两个并发退款回调同时通过上面的检查导致重复退款。
        entityManager.refresh(task);
        if (nz(task.getCreditsRefunded()) >= cost) {
            return 0;
        }

        int balance = nz(locked.getCredits());
        int after = balance + cost;
        locked.setCredits(after);
        locked.setUpdatedAt(LocalDateTime.now());
        userRepository.save(locked);

        CreditTransaction tx = new CreditTransaction(locked.getId(), locked.getUsername(),
                CreditTransaction.TYPE_REFUND, cost, after,
                reason == null || reason.isBlank() ? "合成失败退款" : reason);
        tx.setTaskId(taskId);
        creditTransactionRepository.save(tx);

        task.setCreditsRefunded(cost);
        taskRepository.save(task);

        log.info("合成失败退款成功: user={}, taskId={}, refund={}, balance {} -> {}",
                locked.getUsername(), taskId, cost, balance, after);
        return cost;
    }

    // ==================== 赠送 / 调整 / 充值入账 ====================

    /**
     * 注册赠送积分。失败只告警不抛异常 —— 赠送积分不该导致注册失败，
     * 用户注册成功才是主流程，赠送异常由管理员事后手动补。
     */
    @Transactional
    public int grantRegisterBonus(Long userId) {
        int bonus = getRule().getFreeCreditsOnRegisterSafe();
        if (bonus <= 0) {
            return 0;
        }
        User locked = userRepository.findByIdForUpdate(userId).orElse(null);
        if (locked == null) {
            return 0;
        }

        int balance = nz(locked.getCredits());
        int after = balance + bonus;
        locked.setCredits(after);
        locked.setUpdatedAt(LocalDateTime.now());
        userRepository.save(locked);

        CreditTransaction tx = new CreditTransaction(locked.getId(), locked.getUsername(),
                CreditTransaction.TYPE_REGISTER_BONUS, bonus, after, "新用户注册赠送");
        creditTransactionRepository.save(tx);

        log.info("注册赠送积分: user={}, bonus={}", locked.getUsername(), bonus);
        return bonus;
    }

    /**
     * 管理员手动调整积分（可正可负），用于客诉补偿、异常纠正。
     * 扣减时不允许把余额扣成负数，避免出现无法解释的负余额。
     *
     * @return 调整后的余额
     */
    @Transactional
    public int adminAdjust(Long userId, int delta, String remark, String operator) {
        if (delta == 0) {
            throw new IllegalArgumentException("调整数量不能为 0");
        }
        User locked = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));

        int balance = nz(locked.getCredits());
        int after = balance + delta;
        if (after < 0) {
            throw new IllegalArgumentException("扣减后余额不能为负数，当前余额 " + balance);
        }

        locked.setCredits(after);
        locked.setUpdatedAt(LocalDateTime.now());
        userRepository.save(locked);

        CreditTransaction tx = new CreditTransaction(locked.getId(), locked.getUsername(),
                CreditTransaction.TYPE_ADMIN_ADJUST, delta, after,
                remark == null || remark.isBlank() ? "管理员调整" : remark);
        tx.setOperator(operator);
        creditTransactionRepository.save(tx);

        log.info("管理员调整积分: operator={}, user={}, delta={}, balance {} -> {}",
                operator, locked.getUsername(), delta, balance, after);
        return after;
    }

    /**
     * 充值订单入账：给用户加积分并写 RECHARGE 流水。
     *
     * 强制 MANDATORY：调用方必须已持有订单行锁（findByOrderNoForUpdate）并在同一事务内
     * 把订单改为 PAID。订单状态与积分到账必须原子，否则支付平台重复回调会导致重复到账。
     * 本方法只负责加钱，订单状态机由调用方维护，职责边界清晰。
     *
     * @return 实际到账积分
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int applyRecharge(CreditOrder order) {
        int total = order.getTotalCredits();
        if (total <= 0) {
            return 0;
        }

        // 幂等兜底：同一订单号只允许有一条 RECHARGE 流水
        List<CreditTransaction> existing = creditTransactionRepository
                .findByOrderNoAndType(order.getOrderNo(), CreditTransaction.TYPE_RECHARGE);
        if (!existing.isEmpty()) {
            log.warn("订单已入账过，跳过重复入账: orderNo={}", order.getOrderNo());
            return 0;
        }

        User locked = userRepository.findByIdForUpdate(order.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + order.getUserId()));

        int balance = nz(locked.getCredits());
        int after = balance + total;
        locked.setCredits(after);
        locked.setUpdatedAt(LocalDateTime.now());
        userRepository.save(locked);

        String remark = "充值到账：" + order.getPackageName()
                + (nz(order.getBonusCredits()) > 0 ? "（含赠送 " + order.getBonusCredits() + "）" : "");
        CreditTransaction tx = new CreditTransaction(locked.getId(), locked.getUsername(),
                CreditTransaction.TYPE_RECHARGE, total, after, remark);
        tx.setOrderNo(order.getOrderNo());
        creditTransactionRepository.save(tx);

        log.info("充值入账成功: user={}, orderNo={}, credits={}, balance {} -> {}",
                locked.getUsername(), order.getOrderNo(), total, balance, after);
        return total;
    }

    /** null 安全的整数取值：历史数据可能为 null，直接参与运算会 NPE */
    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
