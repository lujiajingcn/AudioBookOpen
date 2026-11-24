package com.audiobook.server.repository;

import com.audiobook.server.model.CreditPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditPackageRepository extends JpaRepository<CreditPackage, Long> {

    /** 用户端可见的套餐（仅上架），按排序号升序、同序号按价格升序 */
    List<CreditPackage> findByEnabledTrueOrderBySortOrderAscPriceAsc();

    /** 后台管理列表（含下架），排序规则与用户端一致，便于对照 */
    List<CreditPackage> findAllByOrderBySortOrderAscPriceAsc();
}
