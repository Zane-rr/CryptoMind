package com.cryptomind.controller;

import com.cryptomind.entity.AlertRule;
import com.cryptomind.service.IAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Alert 模块 REST 接口，提供自选监控 symbol 的增删查和手动触发检查。
 * 模块：Alert
 */
@Slf4j
@RestController
@RequestMapping("/api/alert")
@RequiredArgsConstructor
public class AlertController {

    private final IAlertService alertService;

    /**
     * 添加自选监控 symbol，重复添加时幂等返回已存在记录。
     *
     * @param body 请求体，包含 symbol 字段，如 {"symbol":"SOLUSDT"}
     * @return 创建或已存在的 AlertRule
     */
    @PostMapping("/watchlist")
    public ResponseEntity<?> addWatchlist(@RequestBody Map<String, String> body) {
        String symbol = body.get("symbol");
        if (symbol == null || symbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol 不能为空"));
        }
        try {
            return ResponseEntity.ok(alertService.addWatchlist(symbol));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 查询所有自选监控规则，按创建时间倒序。
     *
     * @return AlertRule 列表
     */
    @GetMapping("/watchlist")
    public ResponseEntity<List<AlertRule>> listWatchlist() {
        return ResponseEntity.ok(alertService.listWatchlist());
    }

    /**
     * 删除指定自选监控规则。
     *
     * @param id AlertRule 主键
     * @return 204 No Content
     */
    @DeleteMapping("/watchlist/{id}")
    public ResponseEntity<Void> removeWatchlist(@PathVariable Long id) {
        alertService.removeWatchlist(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 手动触发一次全量预警检查，用于测试或即时检查。
     *
     * @return 触发确认消息
     */
    @PostMapping("/check")
    public ResponseEntity<Map<String, String>> triggerCheck() {
        log.info("手动触发预警检查");
        alertService.checkAlerts();
        return ResponseEntity.ok(Map.of("status", "ok", "message", "预警检查已触发"));
    }
}
