package com.cryptomind.repository;

import com.cryptomind.entity.AnalysisRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * AnalysisRecord 数据访问层。
 * 模块：Chat / Analysis
 */
public interface AnalysisRecordRepository extends JpaRepository<AnalysisRecord, Long> {

    /**
     * 查询指定会话的所有分析记录，按时间倒序排列。
     *
     * @param sessionId 会话唯一标识
     * @return 分析记录列表（降序）
     */
    List<AnalysisRecord> findBySessionIdOrderByCreatedAtDesc(String sessionId);
}
