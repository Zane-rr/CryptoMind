package com.cryptomind.controller;

import com.cryptomind.dto.NewsDTO;
import com.cryptomind.service.INewsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * 新闻模块 REST 接口，提供最新加密货币新闻查询。
 * 按 publishedAt 降序直接查 ES，不走向量搜索，避免 Embedding API 依赖。
 * 模块：News
 */
@Slf4j
@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final INewsService newsService;

    /**
     * 获取最新加密货币新闻列表，用于前端 News 面板展示。
     * 直接按时间倒序查询，无需 Embedding API，不受向量搜索影响。
     *
     * @param limit 返回条数，默认10条
     * @return 新闻列表，按 publishedAt 降序排列
     */
    @GetMapping("/latest")
    public ResponseEntity<List<NewsDTO>> getLatestNews(
            @RequestParam(defaultValue = "10") int limit) {
        try {
            return ResponseEntity.ok(newsService.getLatestNews(limit));
        } catch (Exception e) {
            log.warn("获取最新新闻失败：{}", e.getMessage());
            return ResponseEntity.ok(Collections.emptyList());
        }
    }
}
