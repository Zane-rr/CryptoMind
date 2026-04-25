package com.cryptomind.service;

import com.cryptomind.dto.NewsDTO;
import com.cryptomind.dto.NewsSearchRequest;
import com.cryptomind.dto.NewsSearchResponse;
import com.cryptomind.exception.NewsApiException;

import java.util.List;

/**
 * News模块服务接口，提供新闻拉取入库和语义检索能力。
 * 仅供 NewsAgent 内部调用，不对外暴露 HTTP 接口。
 * 模块：News
 */
public interface INewsService {

    /**
     * 定时拉取最新新闻并向量化存入 Elasticsearch。
     * 由 @Scheduled 每30分钟自动调用，使用 ES Upsert 去重，无需手动过滤。
     *
     * @throws NewsApiException 外部API调用失败时抛出
     */
    void fetchAndStore() throws NewsApiException;

    /**
     * 混合检索新闻（语义向量 + 关键词），返回最相关的N条。
     *
     * @param request 检索请求，含query文本、symbol过滤、limit条数
     * @return 检索结果，含命中新闻列表和总数
     * @throws NewsApiException 向量化或ES查询失败时抛出
     */
    NewsSearchResponse searchNews(NewsSearchRequest request) throws NewsApiException;

    /**
     * 按发布时间倒序返回最新新闻，不走向量搜索，适用于前端新闻面板展示。
     *
     * @param limit 返回条数
     * @return 新闻列表，按 publishedAt 降序排列
     */
    List<NewsDTO> getLatestNews(int limit);

    /**
     * 启动时检查 crypto_news 索引是否存在，不存在则创建（含dense_vector映射）。
     * 由 @PostConstruct 自动调用，外部无需关心。
     */
    void buildEsIndex();
}
