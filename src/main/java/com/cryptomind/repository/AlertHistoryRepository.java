package com.cryptomind.repository;

import com.cryptomind.entity.AlertHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 预警推送历史 JPA Repository。
 * 模块：Alert
 */
public interface AlertHistoryRepository extends JpaRepository<AlertHistory, Long> {

    /**
     * 查询指定 symbol + ruleType 在某时间点之后的最近一条记录。
     * 用于 Redis 重启后的冷却兜底检查。
     *
     * @param symbol      交易对
     * @param ruleType    规则类型
     * @param triggeredAt 时间下限
     * @return 最近一条推送记录（如存在）
     */
    Optional<AlertHistory> findTopBySymbolAndRuleTypeAndTriggeredAtAfterOrderByTriggeredAtDesc(
            String symbol, String ruleType, LocalDateTime triggeredAt);
}
