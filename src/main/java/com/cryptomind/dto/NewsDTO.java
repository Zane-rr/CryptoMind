package com.cryptomind.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 新闻数据传输对象，用于 NewsAdapter 返回值和 Agent 消费。
 * 模块：News
 */
@Data
public class NewsDTO {

    /** CryptoPanic 新闻唯一ID */
    private String id;

    /** 新闻标题 */
    private String title;

    /** 新闻摘要（Finnhub: summary） */
    private String summary;

    /** 原文链接 */
    private String url;

    /** 来源名称，如 Reuters */
    private String source;

    /** 相关币种代码列表，解析自 Finnhub related 字段，如 [BTC, ETH] */
    private List<String> currencies;

    /** 情绪标签：positive / negative / neutral，Finnhub数据默认为 neutral */
    private String sentiment;

    /** 新闻发布时间（Finnhub: datetime unix秒级时间戳转换） */
    private LocalDateTime publishedAt;
}
