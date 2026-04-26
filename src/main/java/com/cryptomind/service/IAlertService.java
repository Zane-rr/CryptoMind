package com.cryptomind.service;

import com.cryptomind.entity.AlertRule;

import java.util.List;

/**
 * Alert 模块服务接口，定义自选监控管理和手动触发检查能力。
 * 模块：Alert
 */
public interface IAlertService {

    /**
     * 添加自选监控 symbol，重复添加时静默忽略。
     *
     * @param symbol 交易对，如 SOLUSDT（自动转大写）
     * @return 新建或已存在的 AlertRule
     */
    AlertRule addWatchlist(String symbol);

    /**
     * 查询所有自选监控规则，按创建时间倒序。
     *
     * @return AlertRule 列表
     */
    List<AlertRule> listWatchlist();

    /**
     * 删除指定自选监控规则。
     *
     * @param id AlertRule 主键
     */
    void removeWatchlist(Long id);

    /**
     * 手动触发一次全量预警检查（定时任务同逻辑，可用于测试）。
     */
    void checkAlerts();
}
