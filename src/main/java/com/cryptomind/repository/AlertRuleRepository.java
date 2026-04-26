package com.cryptomind.repository;

import com.cryptomind.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 自选监控规则 JPA Repository。
 * 模块：Alert
 */
public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    /** 按 symbol 查找，用于添加前去重检查 */
    Optional<AlertRule> findBySymbol(String symbol);
}
