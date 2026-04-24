package com.cryptomind.adapter;

import com.cryptomind.dto.NewsDTO;
import com.cryptomind.exception.NewsApiException;

import java.util.List;

/**
 * 新闻数据源适配器接口，抽象 CryptoPanic 等数据源的获取行为。
 * 通过此接口可无缝扩展其他新闻源，无需修改上层 Service。
 * 模块：News
 */
public interface NewsAdapter {

    /**
     * 拉取最新新闻列表。
     *
     * @param limit 最多返回条数，不超过50
     * @return 新闻DTO列表
     * @throws NewsApiException 接口调用失败时抛出
     */
    List<NewsDTO> fetchLatestNews(int limit) throws NewsApiException;

    /**
     * 按币种拉取相关新闻。
     *
     * @param currency 币种代码，如 BTC
     * @param limit    最多返回条数
     * @return 新闻DTO列表
     * @throws NewsApiException 接口调用失败时抛出
     */
    List<NewsDTO> fetchNewsByCurrency(String currency, int limit) throws NewsApiException;
}
