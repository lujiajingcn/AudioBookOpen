package com.audiobook.server.service;

import com.audiobook.server.model.CreditPackage;
import com.audiobook.server.repository.CreditPackageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 充值套餐管理（后台维护 + 用户端读取）。
 *
 * 删除策略是这里唯一需要斟酌的点：套餐被历史订单引用（订单里存了 packageId），
 * 但订单已把名称/积分/金额全部快照下来，所以物理删除套餐不会让历史订单失去信息。
 * 即便如此，仍建议管理员优先「下架」而非删除 —— 下架可逆，删除不可逆。
 */
@Service
public class CreditPackageService {

    private static final Logger log = LoggerFactory.getLogger(CreditPackageService.class);

    private final CreditPackageRepository creditPackageRepository;

    public CreditPackageService(CreditPackageRepository creditPackageRepository) {
        this.creditPackageRepository = creditPackageRepository;
    }

    /** 用户端：仅上架套餐 */
    @Transactional(readOnly = true)
    public List<CreditPackage> listEnabled() {
        return creditPackageRepository.findByEnabledTrueOrderBySortOrderAscPriceAsc();
    }

    /** 后台：全部套餐（含下架） */
    @Transactional(readOnly = true)
    public List<CreditPackage> listAll() {
        return creditPackageRepository.findAllByOrderBySortOrderAscPriceAsc();
    }

    @Transactional
    public CreditPackage create(CreditPackage form) {
        validate(form, true);

        CreditPackage entity = new CreditPackage();
        entity.setName(form.getName().trim());
        entity.setCredits(form.getCredits());
        entity.setBonusCredits(form.getBonusCredits() == null ? 0 : form.getBonusCredits());
        entity.setPrice(form.getPrice().setScale(2, RoundingMode.HALF_UP));
        entity.setDescription(form.getDescription());
        entity.setEnabled(form.getEnabled() == null || form.getEnabled());
        entity.setSortOrder(form.getSortOrder() == null ? 0 : form.getSortOrder());

        CreditPackage saved = creditPackageRepository.save(entity);
        log.info("新增充值套餐: id={}, name={}, credits={}(+{}), price={}",
                saved.getId(), saved.getName(), saved.getCredits(), saved.getBonusCredits(), saved.getPrice());
        return saved;
    }

    /**
     * 更新套餐，只覆盖非 null 字段。
     *
     * 为什么不整体替换：后台表单可能只改一个「是否上架」开关就提交，
     * 整体替换会把没提交的字段重置成 null / 默认值，直接把套餐配置弄坏。
     */
    @Transactional
    public CreditPackage update(Long id, CreditPackage form) {
        CreditPackage entity = creditPackageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("套餐不存在: " + id));
        validate(form, false);

        if (form.getName() != null && !form.getName().isBlank()) {
            entity.setName(form.getName().trim());
        }
        if (form.getCredits() != null) {
            entity.setCredits(form.getCredits());
        }
        if (form.getBonusCredits() != null) {
            entity.setBonusCredits(form.getBonusCredits());
        }
        if (form.getPrice() != null) {
            entity.setPrice(form.getPrice().setScale(2, RoundingMode.HALF_UP));
        }
        if (form.getDescription() != null) {
            entity.setDescription(form.getDescription());
        }
        if (form.getEnabled() != null) {
            entity.setEnabled(form.getEnabled());
        }
        if (form.getSortOrder() != null) {
            entity.setSortOrder(form.getSortOrder());
        }
        entity.setUpdatedAt(LocalDateTime.now());

        CreditPackage saved = creditPackageRepository.save(entity);
        log.info("更新充值套餐: id={}, name={}, credits={}(+{}), price={}, enabled={}",
                saved.getId(), saved.getName(), saved.getCredits(),
                saved.getBonusCredits(), saved.getPrice(), saved.getEnabled());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        CreditPackage entity = creditPackageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("套餐不存在: " + id));
        creditPackageRepository.delete(entity);
        log.warn("删除充值套餐: id={}, name={}（历史订单已保存快照，不受影响）", id, entity.getName());
    }

    /**
     * 校验套餐参数。
     *
     * @param requireAll 新增时必填字段都要有；更新时允许部分字段缺省
     */
    private void validate(CreditPackage form, boolean requireAll) {
        if (form == null) {
            throw new IllegalArgumentException("套餐参数不能为空");
        }
        if (requireAll) {
            if (form.getName() == null || form.getName().isBlank()) {
                throw new IllegalArgumentException("套餐名称不能为空");
            }
            if (form.getCredits() == null) {
                throw new IllegalArgumentException("套餐积分不能为空");
            }
            if (form.getPrice() == null) {
                throw new IllegalArgumentException("套餐价格不能为空");
            }
        }
        if (form.getCredits() != null && form.getCredits() <= 0) {
            throw new IllegalArgumentException("套餐积分必须大于 0");
        }
        if (form.getBonusCredits() != null && form.getBonusCredits() < 0) {
            throw new IllegalArgumentException("赠送积分不能为负数");
        }
        if (form.getPrice() != null && form.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("套餐价格必须大于 0");
        }
        // 支付渠道的金额单位是「分」，超过两位小数在下单时会被四舍五入，
        // 与后台展示不一致，直接在入口拦掉更清晰
        if (form.getPrice() != null && form.getPrice().scale() > 2) {
            throw new IllegalArgumentException("套餐价格最多保留两位小数");
        }
    }
}
