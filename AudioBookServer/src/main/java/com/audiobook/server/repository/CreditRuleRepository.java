package com.audiobook.server.repository;

import com.audiobook.server.model.CreditRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CreditRuleRepository extends JpaRepository<CreditRule, Long> {
}
